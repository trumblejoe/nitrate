using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Controller.SyncPlay;
using Nitrate.Companion.Insights;

namespace Nitrate.Companion.Chat;

/// <summary>
/// Live sessions are Group Watch Sessions (<see cref="Partners.WatchSessions"/>, by their own ids),
/// else the server's SyncPlay groups (older apps), whose participants come as user names.
/// </summary>
public class JellyfinLiveSessions : ILiveSessions
{
    private readonly ISyncPlayManager _syncPlay;
    private readonly ISessionManager _sessions;
    private readonly IUserManager _users;
    private readonly Partners.WatchSessions? _watchSessions;

    /// <summary>Initializes a new instance of the <see cref="JellyfinLiveSessions"/> class.</summary>
    public JellyfinLiveSessions(ISyncPlayManager syncPlay, ISessionManager sessions, IUserManager users, Partners.WatchSessions? watchSessions = null)
    {
        _watchSessions = watchSessions;
        _syncPlay = syncPlay;
        _sessions = sessions;
        _users = users;
    }

    /// <inheritdoc />
    public IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller)
    {
        if (_watchSessions?.Knows(sessionId) == true)
        {
            return _watchSessions.Participants(sessionId, caller);
        }

        // Watching in the group on any of the caller's devices, so a phone can chat for the TV it belongs with.
        var name = _users.GetUserById(caller)?.Username;
        var session = _sessions.Sessions.FirstOrDefault(s => s.UserId.Equals(caller));
        var group = session is null ? null : _syncPlay.GetGroup(session, sessionId);
        if (name is null || group?.Participants is not { } names || !names.Contains(name, StringComparer.OrdinalIgnoreCase))
        {
            return null;
        }

        var ids = names.Select(n => _users.GetUserByName(n)?.Id).OfType<Guid>().ToList();
        if (!ids.Contains(caller))
        {
            ids.Add(caller);
        }

        return ids.Distinct().ToList();
    }

    /// <inheritdoc />
    public Guid? Playing(Guid sessionId, Guid caller)
    {
        if (_watchSessions?.Knows(sessionId) == true)
        {
            return _watchSessions.PlayingIn(sessionId);
        }

        var session = _sessions.Sessions.FirstOrDefault(s => s.UserId.Equals(caller) && s.NowPlayingItem != null)
            ?? _sessions.Sessions.FirstOrDefault(s => s.UserId.Equals(caller));
        var group = session is null ? null : _syncPlay.GetGroup(session, sessionId);
        if (group?.Participants is not { Count: > 0 } names)
        {
            return null;
        }

        return _sessions.Sessions.Where(s => s.NowPlayingItem != null && names.Contains(s.UserName, StringComparer.OrdinalIgnoreCase))
            .OrderByDescending(s => s.UserId.Equals(caller))
            .Select(s => (Guid?)s.NowPlayingItem.Id).FirstOrDefault();
    }
}
