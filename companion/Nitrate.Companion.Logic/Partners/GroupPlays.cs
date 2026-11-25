using System.Collections.Concurrent;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Partners;

/// <summary>Someone's own watch state for a title, as it was before a playback.</summary>
/// <param name="Played">Watched.</param>
/// <param name="PositionMs">Their resume point, ms.</param>
/// <param name="PlayCount">How many times they'd played it.</param>
/// <param name="LastPlayedMs">When they last played it, epoch ms.</param>
public record OwnState(bool Played, long PositionMs, int PlayCount, long? LastPlayedMs);

/// <summary>A playback watched for groups, kept until it stops.</summary>
/// <param name="UserId">Who's watching.</param>
/// <param name="ItemId">What.</param>
/// <param name="GroupIds">The groups it's watched for.</param>
/// <param name="Before">Their own state before it started.</param>
/// <param name="At">When it was said.</param>
public record GroupPlayState(Guid UserId, Guid ItemId, IReadOnlyList<Guid> GroupIds, OwnState Before, DateTimeOffset At);

/// <summary>
/// Keeps a group watch out of the person's own history. Every playback reports to Jellyfin under
/// the person's account, and Jellyfin marks it played, moves its resume point, play count and
/// last-played date; for a playback watched for a group (said by the app as it starts, with the
/// person's own state from before), that state is put back when Jellyfin says the playback
/// stopped, whichever app or device it was and even if the app was closed meanwhile. The group's
/// side is its own record and resume point. Kept in memory (handed over between logic versions);
/// one not stopped within <see cref="Keep"/> is forgotten.
/// </summary>
public class GroupPlays : IHostedService
{
    /// <summary>How long a group playback is waited for to stop.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromHours(12);

    private readonly ISessionManager _sessions;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IUserDataManager _userData;
    private readonly TimeProvider _time;
    private readonly ConcurrentDictionary<(Guid User, Guid Item), GroupPlayState> _plays = new();

    /// <summary>Initializes a new instance of the <see cref="GroupPlays"/> class.</summary>
    public GroupPlays(ISessionManager sessions, ILibraryManager library, IUserManager users, IUserDataManager userData, TimeProvider time)
    {
        _sessions = sessions;
        _library = library;
        _users = users;
        _userData = userData;
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
    /// [user] is watching [itemId] for [groupIds], their own state before it being [before]. Said again
    /// for the same playback (the groups changed), the state from the first time is kept.
    /// </summary>
    public void Playing(Guid user, Guid itemId, IReadOnlyList<Guid> groupIds, OwnState before)
    {
        Prune();
        _plays.AddOrUpdate(
            (user, itemId),
            _ => new GroupPlayState(user, itemId, groupIds, before, _time.GetUtcNow()),
            (_, old) => old with { GroupIds = groupIds, At = _time.GetUtcNow() });
    }

    /// <summary>[user]'s playback of [itemId] is theirs after all ("Change" to just them): nothing is put back.</summary>
    public void NotForGroups(Guid user, Guid itemId) => _plays.TryRemove((user, itemId), out _);

    /// <summary>The groups [user]'s playback of [itemId] is watched for, if it is.</summary>
    public IReadOnlyList<Guid>? For(Guid user, Guid itemId) =>
        _plays.TryGetValue((user, itemId), out var p) && _time.GetUtcNow() - p.At < Keep ? p.GroupIds : null;

    /// <summary>Everything kept, for the next logic.</summary>
    public IReadOnlyList<GroupPlayState> ExportState() => _plays.Values.ToList();

    /// <summary>Takes what the previous logic kept.</summary>
    public void ImportState(IEnumerable<GroupPlayState> plays)
    {
        foreach (var p in plays)
        {
            _plays[(p.UserId, p.ItemId)] = p;
        }
    }

    /// <summary>
    /// Puts [user]'s own state for [itemId] back as it was before their group playback (and forgets the
    /// playback). False when there was none.
    /// </summary>
    public bool Restore(Guid user, Guid itemId)
    {
        if (!_plays.TryRemove((user, itemId), out var play) || _time.GetUtcNow() - play.At >= Keep)
        {
            return false;
        }

        if (_users.GetUserById(user) is not { } person || _library.GetItemById(itemId) is not { } item || _userData.GetUserData(person, item) is not { } data)
        {
            return false;
        }

        Apply(data, play.Before);
        _userData.SaveUserData(person, item, data, MediaBrowser.Model.Entities.UserDataSaveReason.UpdateUserData, CancellationToken.None);
        return true;
    }

    /// <summary>[before] laid over [data] (only what a playback changes: watched, resume point, play count, last played).</summary>
    public static void Apply(MediaBrowser.Controller.Entities.UserItemData data, OwnState before)
    {
        data.Played = before.Played;
        data.PlaybackPositionTicks = Math.Max(0, before.PositionMs) * TimeSpan.TicksPerMillisecond;
        data.PlayCount = Math.Max(0, before.PlayCount);
        data.LastPlayedDate = before.LastPlayedMs is { } ms ? DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime : null;
    }

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        try
        {
            if (e.Item is null)
            {
                return;
            }

            foreach (var user in e.Users ?? new List<Jellyfin.Database.Implementations.Entities.User>())
            {
                Restore(user.Id, e.Item.Id);
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
        foreach (var (key, play) in _plays)
        {
            if (now - play.At >= Keep)
            {
                _plays.TryRemove(key, out _);
            }
        }
    }
}
