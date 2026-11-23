using System.Collections.Concurrent;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Controller.SyncPlay;
using MediaBrowser.Controller.SyncPlay.Requests;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Partners;

/// <summary>
/// Keeps groups' own watched records from the server's playback reports of live (SyncPlay)
/// sessions, in any app: a part played to the end (Jellyfin's played threshold,
/// <see cref="PlaybackStopEventArgs.PlayedToCompletion"/>) counts for each member there, in the
/// groups the session was started for, else where everyone there is in the group and it's the
/// next part for each of them (<see cref="PartnerHub.RecordLive"/>). Outside a live session the
/// apps count by where playback started; nothing is counted here. The session's participants are
/// noted while it plays too, so someone leaving the session as the part ends still counts.
/// </summary>
public class TogetherRecorder : IHostedService
{
    /// <summary>How long participants noted during playback still count when it stops.</summary>
    public static readonly TimeSpan Remember = TimeSpan.FromMinutes(5);

    private readonly ISessionManager _sessions;
    private readonly ISyncPlayManager _syncPlay;
    private readonly IUserManager _users;
    private readonly PartnerHub _partners;
    private readonly ConcurrentDictionary<string, (Guid Item, string Group, HashSet<Guid> Present, DateTimeOffset At)> _seen = new();

    /// <summary>Initializes a new instance of the <see cref="TogetherRecorder"/> class.</summary>
    public TogetherRecorder(ISessionManager sessions, ISyncPlayManager syncPlay, IUserManager users, PartnerHub partners)
    {
        _sessions = sessions;
        _syncPlay = syncPlay;
        _users = users;
        _partners = partners;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart += OnProgress;
        _sessions.PlaybackProgress += OnProgress;
        _sessions.PlaybackStopped += OnStopped;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart -= OnProgress;
        _sessions.PlaybackProgress -= OnProgress;
        _sessions.PlaybackStopped -= OnStopped;
        return Task.CompletedTask;
    }

    /// <summary>Who was seen together in each live session, for the next logic.</summary>
    public IReadOnlyList<TogetherSeenState> ExportState() =>
        _seen.Select(s => new TogetherSeenState(s.Key, s.Value.Item, s.Value.Present.ToList(), s.Value.At, s.Value.Group)).ToList();

    /// <summary>Takes who the previous logic saw together.</summary>
    public void ImportState(IEnumerable<TogetherSeenState> seen)
    {
        foreach (var s in seen.Where(s => s.Group is not null))
        {
            _seen[s.SessionId] = (s.ItemId, s.Group!, s.Present.ToHashSet(), s.At);
        }
    }

    private void OnProgress(object? sender, PlaybackProgressEventArgs e)
    {
        try
        {
            if (e.Session is not { } session || e.Item is null || Present(session) is not { } live)
            {
                return;
            }

            var now = DateTimeOffset.UtcNow;
            _seen.AddOrUpdate(
                session.Id,
                _ => (e.Item.Id, live.Group, live.Present, now),
                (_, old) => old.Item == e.Item.Id && old.Group == live.Group ? (old.Item, old.Group, old.Present.Union(live.Present).ToHashSet(), now) : (e.Item.Id, live.Group, live.Present, now));
        }
        catch (Exception)
        {
            // Never in the way of playback.
        }
    }

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        try
        {
            var seen = e.Session is { } s && _seen.TryRemove(s.Id, out var x) ? x : default;
            if (!e.PlayedToCompletion || e.Item is null)
            {
                return;
            }

            var live = e.Session is { } session ? Present(session) : null;
            var group = live?.Group;
            var present = live?.Present ?? new HashSet<Guid>();
            if (seen.Present is not null && seen.Item == e.Item.Id && DateTimeOffset.UtcNow - seen.At < Remember)
            {
                present.UnionWith(seen.Present);
                group ??= seen.Group;
            }

            if (present.Count > 0 && group is not null)
            {
                _partners.RecordLive(e.Item.Id, present, group);
            }
        }
        catch (Exception)
        {
            // Never in the way of playback.
        }
    }

    /// <summary>The SyncPlay group this session's user is in, and everyone in it (null outside one).</summary>
    private (string Group, HashSet<Guid> Present)? Present(SessionInfo session)
    {
        var name = session.UserName;
        if (string.IsNullOrEmpty(name))
        {
            return null;
        }

        var group = _syncPlay.ListGroups(session, new ListGroupsRequest())
            .FirstOrDefault(g => g.Participants?.Contains(name, StringComparer.OrdinalIgnoreCase) == true);
        if (group is null)
        {
            return null;
        }

        var ids = group.Participants.Select(n => _users.GetUserByName(n)?.Id).OfType<Guid>().ToHashSet();
        ids.Add(session.UserId);
        return (PartnerHub.SessionKey(group.GroupId.ToString()), ids);
    }
}
