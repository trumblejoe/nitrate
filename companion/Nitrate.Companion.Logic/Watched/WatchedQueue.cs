using System.Text.Json;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Watched;

/// <summary>Marking a title watched or unwatched for someone, as Jellyfin's own Watched button does.</summary>
public interface IWatchMarker
{
    /// <summary>Whether [user] can see [itemId] (Jellyfin's own Watched says "not found" otherwise).</summary>
    bool Visible(Guid user, Guid itemId);

    /// <summary>Marks it; false when the person or the title is gone (nothing to do). Throws when the save failed.</summary>
    bool Mark(Guid user, Guid itemId, bool played);
}

/// <summary>Jellyfin's own Watched/Unwatched: the title looked up as the person (their libraries and parental limits), then marked.</summary>
public class JellyfinWatchMarker : IWatchMarker
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;

    /// <summary>Initializes a new instance of the <see cref="JellyfinWatchMarker"/> class.</summary>
    public JellyfinWatchMarker(IUserManager users, ILibraryManager library)
    {
        _users = users;
        _library = library;
    }

    /// <inheritdoc />
    public bool Visible(Guid user, Guid itemId) => _users.GetUserById(user) is { } person && _library.GetItemById<BaseItem>(itemId, person) is not null;

    /// <inheritdoc />
    public bool Mark(Guid user, Guid itemId, bool played)
    {
        if (_users.GetUserById(user) is not { } person || _library.GetItemById<BaseItem>(itemId, person) is not { } item)
        {
            return false;
        }

        if (played)
        {
            item.MarkPlayed(person, null, true);
        }
        else
        {
            item.MarkUnplayed(person);
        }

        return true;
    }
}

/// <summary>A Watched/Unwatched press waiting to be saved.</summary>
/// <param name="Played">Watched (true) or Unwatched.</param>
/// <param name="At">When it was pressed, epoch ms.</param>
/// <param name="Tries">Saves that failed so far.</param>
/// <param name="NextTry">When to try again, epoch ms.</param>
public record PendingMark(bool Played, long At, int Tries = 0, long NextTry = 0);

/// <summary>
/// Watched and Unwatched answered at once, saved to Jellyfin a moment later. A press is kept in the
/// plugin's database as soon as it arrives (so a closed app, a restart or a new logic loses nothing),
/// then saved through Jellyfin's own mark, one at a time in the order pressed, tried again when a save
/// fails. A newer press for the same title replaces one still waiting, and one still waiting gives way
/// when the title's watch state changes some other way meanwhile (another app, a playback): the newest
/// change wins, as it would have with Jellyfin alone.
/// </summary>
public class WatchedQueue : IHostedService
{
    /// <summary>Saves tried this many times are given up (and logged).</summary>
    public const int MaxTries = 8;

    /// <summary>The most titles one request may mark.</summary>
    public const int MaxPerRequest = 500;

    private const string Kind = "watchq";

    [ThreadStatic]
    private static bool _saving;

    private readonly CompanionDb _db;
    private readonly IWatchMarker _marker;
    private readonly IUserDataManager? _userData;
    private readonly TimeProvider _time;
    private readonly ILogger _log;
    private readonly SemaphoreSlim _wake = new(0);
    private readonly object _lock = new();
    private CancellationTokenSource? _stop;
    private Task? _worker;

    /// <summary>Initializes a new instance of the <see cref="WatchedQueue"/> class ([userData]: the server's, to hear changes made elsewhere).</summary>
    public WatchedQueue(CompanionDb db, IWatchMarker marker, IUserDataManager? userData, TimeProvider time, ILogger log)
    {
        _db = db;
        _marker = marker;
        _userData = userData;
        _time = time;
        _log = log;
    }

    /// <summary>
    /// [user] pressed Watched ([played]) or Unwatched on [itemIds]: each one they can see is kept to be
    /// saved (the rest come back, as Jellyfin's "not found").
    /// </summary>
    public IReadOnlyList<Guid> Press(Guid user, IReadOnlyList<Guid> itemIds, bool played)
    {
        var missing = new List<Guid>();
        var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
        foreach (var id in itemIds.Distinct().Take(MaxPerRequest))
        {
            if (!_marker.Visible(user, id))
            {
                missing.Add(id);
                continue;
            }

            lock (_lock)
            {
                _db.PutRecord(Kind, user.ToString("N"), id.ToString("N"), JsonSerializer.Serialize(new PendingMark(played, now)), now);
            }
        }

        _wake.Release();
        return missing;
    }

    /// <summary>What [user] has waiting, by title.</summary>
    public IReadOnlyDictionary<Guid, PendingMark> Waiting(Guid user)
    {
        lock (_lock)
        {
            return _db.Records(Kind, user.ToString("N"))
                .Select(r => (Ok: Guid.TryParseExact(r.Key, "N", out var id), Id: id, Mark: Read(r.Value)))
                .Where(r => r.Ok && r.Mark is not null)
                .ToDictionary(r => r.Id, r => r.Mark!);
        }
    }

    /// <summary>
    /// Saves what's due, in the order pressed: each through Jellyfin's own mark; one that fails waits
    /// longer each time and is given up after <see cref="MaxTries"/>. Returns how many were saved.
    /// </summary>
    public int SaveDue()
    {
        List<(Guid User, Guid Item, PendingMark Mark)> due;
        var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
        lock (_lock)
        {
            due = _db.Records(Kind, null)
                .Select(r => (Parts: r.Key.Split('|'), Mark: Read(r.Value)))
                .Where(r => r.Parts.Length == 2 && r.Mark is not null && r.Mark.NextTry <= now)
                .Select(r => (User: Guid.ParseExact(r.Parts[0], "N"), Item: Guid.ParseExact(r.Parts[1], "N"), r.Mark!))
                .OrderBy(r => r.Item3.At)
                .ToList();
        }

        var saved = 0;
        foreach (var (user, item, mark) in due)
        {
            bool done;
            try
            {
                _saving = true;
                // Saved, or nothing to save (the person or the title is gone): either way it's done.
                _marker.Mark(user, item, mark.Played);
                done = true;
            }
            catch (Exception e)
            {
                done = false;
                var tries = mark.Tries + 1;
                lock (_lock)
                {
                    // Only if it's still the same press (not replaced or given way meanwhile).
                    if (Current(user, item) is { } still && still.At == mark.At)
                    {
                        if (tries >= MaxTries)
                        {
                            _log.LogWarning(e, "Nitrate Companion: gave up marking {Item} {State} after {Tries} tries", item, mark.Played ? "watched" : "unwatched", tries);
                            _db.DeleteRecord(Kind, user.ToString("N"), item.ToString("N"));
                        }
                        else
                        {
                            var wait = Math.Min(300_000L, 1000L << Math.Min(tries, 9));
                            _db.PutRecord(Kind, user.ToString("N"), item.ToString("N"), JsonSerializer.Serialize(mark with { Tries = tries, NextTry = now + wait }), mark.At);
                        }
                    }
                }
            }
            finally
            {
                _saving = false;
            }

            if (done)
            {
                saved++;
                lock (_lock)
                {
                    if (Current(user, item) is { } still && still.At == mark.At)
                    {
                        _db.DeleteRecord(Kind, user.ToString("N"), item.ToString("N"));
                    }
                }
            }
        }

        return saved;
    }

    /// <summary>
    /// [user]'s press for [itemId], if one is waiting, saved now (before a playback of it is counted
    /// apart from their own, so the state put back afterwards is the one they pressed). The press saved,
    /// else null.
    /// </summary>
    public PendingMark? SaveNow(Guid user, Guid itemId)
    {
        PendingMark? mark;
        lock (_lock)
        {
            mark = Current(user, itemId);
        }

        if (mark is null)
        {
            return null;
        }

        try
        {
            _saving = true;
            _marker.Mark(user, itemId, mark.Played);
        }
        finally
        {
            _saving = false;
        }

        lock (_lock)
        {
            if (Current(user, itemId) is { } still && still.At == mark.At)
            {
                _db.DeleteRecord(Kind, user.ToString("N"), itemId.ToString("N"));
            }
        }

        return mark;
    }

    /// <summary>
    /// A title's watch state was saved for [reason]: when it wasn't this queue saving it and it set the
    /// watched mark itself (Watched/Unwatched in another app, a playback that finished, a state put back),
    /// a press still waiting for it gives way (the newer change wins). A playback starting or moving on
    /// doesn't: the press is still saved, as it would have been before it with Jellyfin alone.
    /// </summary>
    public void SavedElsewhere(Guid user, Guid itemId, long at, UserDataSaveReason reason)
    {
        if (_saving || reason is not (UserDataSaveReason.TogglePlayed or UserDataSaveReason.UpdateUserData or UserDataSaveReason.PlaybackFinished))
        {
            return;
        }

        lock (_lock)
        {
            if (Current(user, itemId) is { } waiting && waiting.At <= at)
            {
                _db.DeleteRecord(Kind, user.ToString("N"), itemId.ToString("N"));
            }
        }
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (_userData is not null)
        {
            _userData.UserDataSaved += OnSaved;
        }

        _stop = new CancellationTokenSource();
        var token = _stop.Token;
        _worker = Task.Run(() => Run(token), CancellationToken.None);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_userData is not null)
        {
            _userData.UserDataSaved -= OnSaved;
        }

        _stop?.Cancel();
        if (_worker is { } w)
        {
            // What's waiting stays in the database for the next logic.
            await Task.WhenAny(w, Task.Delay(TimeSpan.FromSeconds(10), CancellationToken.None)).ConfigureAwait(false);
        }
    }

    private async Task Run(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                SaveDue();
            }
            catch (Exception e)
            {
                _log.LogWarning(e, "Nitrate Companion: saving Watched/Unwatched");
            }

            try
            {
                // Woken by a press, else looking again for ones waiting to try again.
                await _wake.WaitAsync(TimeSpan.FromSeconds(5), token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private void OnSaved(object? sender, UserDataSaveEventArgs e)
    {
        try
        {
            if (e.Item is not null)
            {
                SavedElsewhere(e.UserId, e.Item.Id, _time.GetUtcNow().ToUnixTimeMilliseconds(), e.SaveReason);
            }
        }
        catch (Exception)
        {
            // Never in the way of a save.
        }
    }

    private PendingMark? Current(Guid user, Guid item) =>
        _db.Record(Kind, user.ToString("N"), item.ToString("N")) is { } json ? Read(json) : null;

    private static PendingMark? Read(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<PendingMark>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
