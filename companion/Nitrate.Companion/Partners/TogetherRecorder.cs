using System.Collections.Concurrent;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Controller.SyncPlay;
using MediaBrowser.Controller.SyncPlay.Requests;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Partners;

/// <summary>
/// Keeps together-only groups' own watched records from the server's playback reports, in any
/// app: a part played to the end (Jellyfin's played threshold, <see cref="PlaybackStopEventArgs.PlayedToCompletion"/>)
/// in a live (SyncPlay) session counts for each member of a together-only group who was in that
/// session, when at least two of the group's members were (<see cref="PartnerHub.RecordLive"/>);
/// played to the end anywhere, it counts as catching up on what the group already watched
/// together (<see cref="PartnerHub.RecordCatchUp"/>). The session's participants are noted while
/// it plays too, so someone leaving the session as the part ends still counts.
/// </summary>
public class TogetherRecorder : IHostedService
{
    /// <summary>How long participants noted during playback still count when it stops.</summary>
    public static readonly TimeSpan Remember = TimeSpan.FromMinutes(5);

    private readonly ISessionManager _sessions;
    private readonly ISyncPlayManager _syncPlay;
    private readonly IUserManager _users;
    private readonly PartnerHub _partners;
    private readonly ConcurrentDictionary<string, (Guid Item, HashSet<Guid> Present, DateTimeOffset At)> _seen = new();

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

    private void OnProgress(object? sender, PlaybackProgressEventArgs e)
    {
        try
        {
            if (e.Session is not { } session || e.Item is null || Present(session) is not { Count: >= 2 } present)
            {
                return;
            }

            var now = DateTimeOffset.UtcNow;
            _seen.AddOrUpdate(
                session.Id,
                _ => (e.Item.Id, present, now),
                (_, old) => old.Item == e.Item.Id ? (old.Item, old.Present.Union(present).ToHashSet(), now) : (e.Item.Id, present, now));
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

            var present = (e.Session is { } session ? Present(session) : null) ?? new HashSet<Guid>();
            if (seen.Present is not null && seen.Item == e.Item.Id && DateTimeOffset.UtcNow - seen.At < Remember)
            {
                present.UnionWith(seen.Present);
            }

            if (present.Count >= 2)
            {
                _partners.RecordLive(e.Item.Id, present);
            }

            foreach (var user in e.Users ?? new())
            {
                _partners.RecordCatchUp(user.Id, e.Item.Id);
            }
        }
        catch (Exception)
        {
            // Never in the way of playback.
        }
    }

    /// <summary>Everyone in the SyncPlay group this session's user is in (null outside one).</summary>
    private HashSet<Guid>? Present(SessionInfo session)
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
        return ids;
    }
}
