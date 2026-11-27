using System.Collections.Concurrent;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.Extensions.Hosting;
using Nitrate.Companion.Partners;

namespace Nitrate.Companion.Playlists;

/// <summary>A playback that is a sitting of a playlist, kept until it stops.</summary>
/// <param name="UserId">Who's watching.</param>
/// <param name="ItemId">What.</param>
/// <param name="PlaylistId">The playlist it's watched from.</param>
/// <param name="Before">Their own state before it started.</param>
/// <param name="At">When it was said.</param>
public record PlaylistSittingState(Guid UserId, Guid ItemId, Guid PlaylistId, OwnState Before, DateTimeOffset At);

/// <summary>
/// Keeps a playlist sitting out of the person's own watch state and in the playlist's own record.
/// The app says, as a playback starts, that it's a sitting of a playlist (with the person's own state
/// from before); when Jellyfin says it stopped (whichever app, and even if the app was closed
/// meanwhile and the server ended the session), the playlist's record moves on (watched, or where it
/// left off) and then the person's own state is put back as it was. Kept in memory (handed over
/// between logic versions); one not stopped within <see cref="Keep"/> is forgotten.
/// </summary>
public class PlaylistSittings : IHostedService
{
    /// <summary>How long a sitting is waited for to stop.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromHours(12);

    private readonly ISessionManager _sessions;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IUserDataManager _userData;
    private readonly PlaylistRecordBook _book;
    private readonly TimeProvider _time;
    private readonly ConcurrentDictionary<(Guid User, Guid Item), PlaylistSittingState> _sittings = new();

    /// <summary>Initializes a new instance of the <see cref="PlaylistSittings"/> class.</summary>
    public PlaylistSittings(ISessionManager sessions, ILibraryManager library, IUserManager users, IUserDataManager userData, PlaylistRecordBook book, TimeProvider time)
    {
        _sessions = sessions;
        _library = library;
        _users = users;
        _userData = userData;
        _book = book;
        _time = time;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStopped += OnStopped;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStopped -= OnStopped;
        return Task.CompletedTask;
    }

    /// <summary>
    /// [user]'s playback of [itemId] is a sitting of [playlistId], their own state before it being
    /// [before]. Said again for the same playback, the state from the first time is kept.
    /// </summary>
    public void Playing(Guid user, Guid itemId, Guid playlistId, OwnState before)
    {
        Prune();
        _sittings.AddOrUpdate(
            (user, itemId),
            _ => new PlaylistSittingState(user, itemId, playlistId, before, _time.GetUtcNow()),
            (_, old) => old with { PlaylistId = playlistId, At = _time.GetUtcNow() });
    }

    /// <summary>[user]'s playback of [itemId] is theirs after all: nothing is put back or recorded.</summary>
    public void NotInPlaylist(Guid user, Guid itemId) => _sittings.TryRemove((user, itemId), out _);

    /// <summary>The playlist [user]'s playback of [itemId] is a sitting of, if it is.</summary>
    public Guid? For(Guid user, Guid itemId) =>
        _sittings.TryGetValue((user, itemId), out var s) && _time.GetUtcNow() - s.At < Keep ? s.PlaylistId : null;

    /// <summary>Everything kept, for the next logic.</summary>
    public IReadOnlyList<PlaylistSittingState> ExportState() => _sittings.Values.ToList();

    /// <summary>Takes what the previous logic kept.</summary>
    public void ImportState(IEnumerable<PlaylistSittingState> sittings)
    {
        foreach (var s in sittings)
        {
            _sittings[(s.UserId, s.ItemId)] = s;
        }
    }

    /// <summary>
    /// [user]'s sitting of [itemId] stopped at [positionMs] ([ended]: played to the end): the playlist's
    /// record moves on, then their own state is put back. False when it wasn't a sitting.
    /// </summary>
    public bool Stopped(Guid user, Guid itemId, long positionMs, bool ended)
    {
        if (!_sittings.TryRemove((user, itemId), out var sitting) || _time.GetUtcNow() - sitting.At >= Keep)
        {
            return false;
        }

        var item = _library.GetItemById(itemId);
        var runtime = item?.RunTimeTicks is { } t ? t / TimeSpan.TicksPerMillisecond : (long?)null;
        // The record first: an app waiting for the own state to come back then reads it moved on.
        _book.Stopped(user, sitting.PlaylistId, itemId, positionMs, runtime, ended, _time.GetUtcNow().ToUnixTimeMilliseconds());
        if (_users.GetUserById(user) is { } person && item is not null && _userData.GetUserData(person, item) is { } data)
        {
            GroupPlays.Apply(data, sitting.Before);
            _userData.SaveUserData(person, item, data, MediaBrowser.Model.Entities.UserDataSaveReason.UpdateUserData, CancellationToken.None);
        }

        return true;
    }

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        try
        {
            if (e.Item is null)
            {
                return;
            }

            var position = (e.PlaybackPositionTicks ?? 0) / TimeSpan.TicksPerMillisecond;
            foreach (var user in e.Users ?? new List<Jellyfin.Database.Implementations.Entities.User>())
            {
                Stopped(user.Id, e.Item.Id, position, e.PlayedToCompletion);
            }
        }
        catch (Exception)
        {
            // Never in the way of playback.
        }
    }

    private void Prune()
    {
        var now = _time.GetUtcNow();
        foreach (var (key, s) in _sittings)
        {
            if (now - s.At >= Keep)
            {
                _sittings.TryRemove(key, out _);
            }
        }
    }
}
