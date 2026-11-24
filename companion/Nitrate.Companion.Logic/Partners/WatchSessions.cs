namespace Nitrate.Companion.Partners;

/// <summary>
/// One member of a Group Watch Session as the members see it.
/// </summary>
/// <param name="Id">The member.</param>
/// <param name="Name">Their name.</param>
/// <param name="Me">Whether it's the caller.</param>
/// <param name="PositionMs">Where they are now (their last report, moved on by the time since while playing).</param>
/// <param name="Paused">Whether they're paused.</param>
/// <param name="Leader">Whether they set the group's pace (the starter while there, else who joined first).</param>
/// <param name="Together">Whether they joined in time to start together.</param>
public record SessionMemberView(Guid Id, string Name, bool Me, long PositionMs, bool Paused, bool Leader, bool Together);

/// <summary>
/// A Group Watch Session as one member (or a member of its group who hasn't joined) sees it, with
/// the server's clock (<paramref name="ServerTime"/>) so apps can correct for their own.
/// </summary>
/// <param name="Id">The session (also the id group chat and notes use for it).</param>
/// <param name="GroupId">The watch group.</param>
/// <param name="ItemId">The part it plays.</param>
/// <param name="PartLabel">"S1:E4", or a film's name.</param>
/// <param name="Title">The group's titles' names.</param>
/// <param name="StartedBy">Who started it.</param>
/// <param name="StartedByMe">Whether the caller did.</param>
/// <param name="State"><see cref="WatchSessions.Waiting"/>, <see cref="WatchSessions.Starting"/>, <see cref="WatchSessions.Playing"/> or <see cref="WatchSessions.Ended"/>.</param>
/// <param name="StartAt">The moment every player starts (null while it gathers).</param>
/// <param name="StartMs">Where in the part they all start.</param>
/// <param name="PositionMs">Where the group is now (start moment + elapsed, minus pauses, as the leader plays).</param>
/// <param name="Paused">Whether the group (its leader) is paused.</param>
/// <param name="GatherUntil">Until when joining still starts together.</param>
/// <param name="Members">Who's in it now (not those who left).</param>
/// <param name="Joined">Whether the caller is in it.</param>
/// <param name="ServerTime">The server's clock as it answered.</param>
public record SessionView(
    Guid Id, Guid GroupId, Guid ItemId, string? PartLabel, string Title, string StartedBy, bool StartedByMe, string State,
    DateTimeOffset? StartAt, long StartMs, long PositionMs, bool Paused, DateTimeOffset GatherUntil,
    IReadOnlyList<SessionMemberView> Members, bool Joined, DateTimeOffset ServerTime);

/// <summary>A join's answer: the session, and where the caller's player starts.</summary>
/// <param name="Session">The session as the caller sees it now.</param>
/// <param name="StartMs">Where the caller's player starts.</param>
/// <param name="Together">Whether they start together with the rest (at <see cref="SessionView.StartAt"/>, once set), rather than straight away.</param>
public record SessionJoinView(SessionView Session, long StartMs, bool Together);

/// <summary>Something the session's people hear about at once.</summary>
/// <param name="SessionId">The session.</param>
/// <param name="GroupId">Its group.</param>
/// <param name="Event">What happened (<see cref="WatchSessions"/>' event names).</param>
/// <param name="By">Who did it (null: the clock).</param>
/// <param name="PositionMs">Where, for a pause or a jump.</param>
/// <param name="ToGroup">Whether the whole group hears it (a start, an end), not only who's in it.</param>
public record SessionAnnouncement(Guid SessionId, Guid GroupId, string Event, Guid? By, long PositionMs, bool ToGroup);

/// <summary>A session as one logic hands it to the next (<c>watchsessions.v1</c>).</summary>
/// <param name="Id">The session.</param>
/// <param name="GroupId">Its group.</param>
/// <param name="ItemId">What it plays.</param>
/// <param name="StartedBy">Its starter.</param>
/// <param name="Created">When it was started.</param>
/// <param name="StartAt">The start moment, once set.</param>
/// <param name="StartMs">Where it starts.</param>
/// <param name="AnchorAt">When the group clock was last set.</param>
/// <param name="AnchorMs">The position then.</param>
/// <param name="Paused">Whether the group clock stands.</param>
/// <param name="Ended">When it ended (null: live).</param>
/// <param name="Members">Its members.</param>
public record WatchSessionState(
    Guid Id, Guid GroupId, Guid ItemId, Guid StartedBy, DateTimeOffset Created, DateTimeOffset? StartAt, long StartMs,
    DateTimeOffset AnchorAt, long AnchorMs, bool Paused, DateTimeOffset? Ended, IReadOnlyList<SessionMemberState> Members);

/// <summary>One member as handed over.</summary>
/// <param name="UserId">The member.</param>
/// <param name="JoinedAt">When they joined.</param>
/// <param name="PositionMs">Their last reported position.</param>
/// <param name="Paused">Whether they were paused.</param>
/// <param name="ReportedAt">When they last reported.</param>
/// <param name="Left">Whether they left.</param>
/// <param name="Together">Whether they joined in time to start together.</param>
public record SessionMemberState(Guid UserId, DateTimeOffset JoinedAt, long PositionMs, bool Paused, DateTimeOffset ReportedAt, bool Left, bool Together);

/// <summary>
/// Group Watch Sessions: a watch group watching one part (an episode or film) at the same moment,
/// each on their own player. Someone starts one (at the group's session time, or any time with
/// "Watch now together"); the group's open apps are told at once ("Tommy started the session ·
/// Join"). Everyone who joins within the first minute (<see cref="Gather"/>) starts together: the
/// start moment is set a few seconds ahead (<see cref="Countdown"/>) once everyone in the group is
/// in, the starter says "Start now", or the minute is up, and every player starts then from the
/// same position. Later joiners start where the group is (the start moment plus the time since,
/// less pauses, as its leader plays), or from the beginning if they choose. While watching each
/// player is its own: pausing or jumping is only told to the others (who may do the same), and each
/// app reports where it is every ten seconds and on a pause or a jump. A part counts for each member
/// who played it to the end (<see cref="TogetherRecorder"/>). Only the group's members see its
/// sessions. Kept in memory (handed to the next logic), gone a few hours after they end.
/// </summary>
public class WatchSessions
{
    /// <summary>Gathering: started, the start moment not set yet.</summary>
    public const string Waiting = "waiting";

    /// <summary>The start moment is set and hasn't come yet.</summary>
    public const string Starting = "starting";

    /// <summary>Playing.</summary>
    public const string Playing = "playing";

    /// <summary>Over.</summary>
    public const string Ended = "ended";

    /// <summary>Events: someone started a session (to the whole group).</summary>
    public const string EventStarted = "started";

    /// <summary>The start moment was set (to who's in).</summary>
    public const string EventCountdown = "countdown";

    /// <summary>Someone joined.</summary>
    public const string EventJoined = "joined";

    /// <summary>Someone left.</summary>
    public const string EventLeft = "left";

    /// <summary>Someone paused.</summary>
    public const string EventPause = "pause";

    /// <summary>Someone played on after a pause.</summary>
    public const string EventPlay = "play";

    /// <summary>Someone jumped.</summary>
    public const string EventSeek = "seek";

    /// <summary>The session ended (to the whole group).</summary>
    public const string EventEnded = "ended";

    /// <summary>How long after it starts joining still starts together.</summary>
    public static readonly TimeSpan Gather = TimeSpan.FromMinutes(1);

    /// <summary>How far ahead the start moment is set.</summary>
    public static readonly TimeSpan Countdown = TimeSpan.FromSeconds(5);

    /// <summary>With nobody reporting for this long, a session is over.</summary>
    public static readonly TimeSpan Idle = TimeSpan.FromMinutes(15);

    /// <summary>How long an ended session is kept (for the recorder and late reports).</summary>
    public static readonly TimeSpan KeepEnded = TimeSpan.FromHours(3);

    private readonly PartnerHub _partners;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, Session> _sessions = new();

    /// <summary>Initializes a new instance of the <see cref="WatchSessions"/> class.</summary>
    public WatchSessions(PartnerHub partners, TimeProvider time)
    {
        _partners = partners;
        _time = time;
    }

    /// <summary>Raised (outside the lock) when the session's people should hear something at once.</summary>
    public event Action<SessionAnnouncement>? Announced;

    /// <summary>Whether any session is live (the clock only needs checking then).</summary>
    public bool AnyLive
    {
        get
        {
            lock (_lock)
            {
                return _sessions.Values.Any(s => s.Ended is null);
            }
        }
    }

    /// <summary>
    /// Starts a session of group <paramref name="groupId"/> on <paramref name="itemId"/> (a part
    /// of its titles), the caller in it; the group's live session if it has one already (the
    /// caller joins that instead). The rest of the group is told at once.
    /// </summary>
    public SessionJoinView Start(Guid user, Guid groupId, Guid itemId, long startMs = 0)
    {
        var group = _partners.SessionGroup(user, groupId, itemId);
        Guid id;
        lock (_lock)
        {
            if (LiveOf(groupId) is { } live)
            {
                id = live.Id;
            }
            else
            {
                var now = _time.GetUtcNow();
                var s = new Session
                {
                    Id = Guid.NewGuid(), GroupId = groupId, ItemId = itemId, StartedBy = user, Created = now,
                    StartMs = Math.Max(0, startMs), AnchorMs = Math.Max(0, startMs), AnchorAt = now,
                };
                _sessions[s.Id] = s;
                id = s.Id;
                Prune(now);
                s.Members[user] = new Member { UserId = user, JoinedAt = now, ReportedAt = now, PositionMs = s.StartMs, Together = true };
                // What it plays to the end counts for the group (the recorder asks by this id).
                _partners.SessionFor(user, groupId, s.Id.ToString());
                Tell(new SessionAnnouncement(s.Id, groupId, EventStarted, user, s.StartMs, true));
                if (group.Members.Count <= 1)
                {
                    SetStart(s, now, null);
                }

                return JoinView(s, user, now);
            }
        }

        return Join(user, id, false);
    }

    /// <summary>
    /// Joins a session of one of the caller's groups: while it gathers (or before its start
    /// moment) to start together; later at where the group is now, or from its start when
    /// <paramref name="fromStart"/>. Once everyone in the group is in, the start moment is set.
    /// </summary>
    public SessionJoinView Join(Guid user, Guid sessionId, bool fromStart)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var s = Visible(user, sessionId, now);
            if (s.Ended is not null)
            {
                throw new PartnerException(PartnerError.Invalid, "That Group Watch Session has ended.");
            }

            var together = s.StartAt is null || now < s.StartAt;
            var position = together ? s.StartMs : fromStart ? 0 : Position(s, now);
            var already = s.Members.TryGetValue(user, out var m) && !m.Left;
            s.Members[user] = new Member { UserId = user, JoinedAt = already ? m!.JoinedAt : now, ReportedAt = now, PositionMs = position, Together = together };
            if (!already)
            {
                Tell(new SessionAnnouncement(s.Id, s.GroupId, EventJoined, user, position, false));
            }

            if (s.StartAt is null && _partners.MembersOf(s.GroupId).All(g => s.Members.TryGetValue(g, out var x) && !x.Left))
            {
                SetStart(s, now, null);
            }

            return new SessionJoinView(View(s, user, now), position, together);
        }
    }

    /// <summary>The starter's "Start now": the start moment is set without waiting for the others.</summary>
    public SessionView StartNow(Guid user, Guid sessionId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var s = Visible(user, sessionId, now);
            if (s.StartAt is null && s.Ended is null)
            {
                if (!In(s, user))
                {
                    throw new PartnerException(PartnerError.Invalid, "Join the Group Watch Session first.");
                }

                SetStart(s, now, user);
            }

            return View(s, user, now);
        }
    }

    /// <summary>
    /// Where the caller is (every ten seconds while in a session, and at once on a pause, a play
    /// after one, or a jump: <paramref name="happened"/>). The leader's reports move the group's
    /// clock; events are told to the others, who are offered the same (never made to).
    /// </summary>
    public SessionView Report(Guid user, Guid sessionId, long positionMs, bool paused, string? happened)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var s = Visible(user, sessionId, now);
            if (s.Ended is null && s.Members.TryGetValue(user, out var m) && !m.Left)
            {
                m.PositionMs = Math.Max(0, positionMs);
                m.Paused = paused;
                m.ReportedAt = now;
                if (s.StartAt is { } at && now >= at && Leader(s) == user)
                {
                    s.AnchorAt = now;
                    s.AnchorMs = m.PositionMs;
                    s.Paused = paused;
                }

                if (happened is EventPause or EventPlay or EventSeek)
                {
                    Tell(new SessionAnnouncement(s.Id, s.GroupId, happened, user, m.PositionMs, false));
                }
            }

            return View(s, user, now);
        }
    }

    /// <summary>The caller leaves; the last one out ends it.</summary>
    public void Leave(Guid user, Guid sessionId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var s = Visible(user, sessionId, now);
            if (s.Ended is not null || !s.Members.TryGetValue(user, out var m) || m.Left)
            {
                return;
            }

            m.Left = true;
            Tell(new SessionAnnouncement(s.Id, s.GroupId, EventLeft, user, m.PositionMs, false));
            if (s.Members.Values.All(x => x.Left))
            {
                End(s, now, user);
            }
        }
    }

    /// <summary>The starter ends it for everyone (the others may carry on watching on their own).</summary>
    public void EndSession(Guid user, Guid sessionId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var s = Visible(user, sessionId, now);
            if (s.Ended is not null)
            {
                return;
            }

            if (s.StartedBy != user && In(s, s.StartedBy))
            {
                throw new PartnerException(PartnerError.Invalid, "Only whoever started it can end it for everyone. Leave it instead.");
            }

            End(s, now, user);
        }
    }

    /// <summary>A session as the caller (a member of its group) sees it.</summary>
    public SessionView Get(Guid user, Guid sessionId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            return View(Visible(user, sessionId, now), user, now);
        }
    }

    /// <summary>The live sessions of the caller's groups (to join from a card, or after missing the news).</summary>
    public IReadOnlyList<SessionView> Mine(Guid user)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            Due(now);
            return _sessions.Values.Where(s => s.Ended is null && _partners.MembersOf(s.GroupId).Contains(user))
                .Select(s => View(s, user, now)).ToList();
        }
    }

    /// <summary>
    /// Whoever was in a session on <paramref name="itemId"/> with <paramref name="user"/> (live,
    /// or ended lately): the session and its group, for counting what they played to the end.
    /// </summary>
    public (Guid Id, Guid GroupId)? SessionOn(Guid user, Guid itemId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            return _sessions.Values
                .Where(s => s.ItemId == itemId && s.Members.ContainsKey(user) && (s.Ended is null || now - s.Ended < KeepEnded))
                .OrderBy(s => s.Ended is null ? 0 : 1).ThenByDescending(s => s.Created)
                .Select(s => ((Guid, Guid)?)(s.Id, s.GroupId)).FirstOrDefault();
        }
    }

    /// <summary>Who is in a live session now (user ids), when <paramref name="caller"/> is; null when they aren't or there's no such session.</summary>
    public IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller)
    {
        lock (_lock)
        {
            if (!_sessions.TryGetValue(sessionId, out var s) || s.Ended is not null || !In(s, caller))
            {
                return null;
            }

            return s.Members.Values.Where(m => !m.Left).Select(m => m.UserId).ToList();
        }
    }

    /// <summary>Who is in a session now (user ids; empty for none).</summary>
    public IReadOnlyList<Guid> PeopleIn(Guid sessionId)
    {
        lock (_lock)
        {
            return _sessions.TryGetValue(sessionId, out var s) ? s.Members.Values.Where(m => !m.Left).Select(m => m.UserId).ToList() : new List<Guid>();
        }
    }

    /// <summary>Whether <paramref name="sessionId"/> is a Group Watch Session (live or ended lately).</summary>
    public bool Knows(Guid sessionId)
    {
        lock (_lock)
        {
            return _sessions.ContainsKey(sessionId);
        }
    }

    /// <summary>What a session plays.</summary>
    public Guid? PlayingIn(Guid sessionId)
    {
        lock (_lock)
        {
            return _sessions.TryGetValue(sessionId, out var s) && s.Ended is null ? s.ItemId : null;
        }
    }

    /// <summary>What the clock brings (called every couple of seconds while any is live): gatherings that are up start, idle sessions end.</summary>
    public void Due()
    {
        lock (_lock)
        {
            Due(_time.GetUtcNow());
        }
    }

    /// <summary>The sessions, for the next logic.</summary>
    public IReadOnlyList<WatchSessionState> ExportState()
    {
        lock (_lock)
        {
            return _sessions.Values.Select(s => new WatchSessionState(
                s.Id, s.GroupId, s.ItemId, s.StartedBy, s.Created, s.StartAt, s.StartMs, s.AnchorAt, s.AnchorMs, s.Paused, s.Ended,
                s.Members.Values.Select(m => new SessionMemberState(m.UserId, m.JoinedAt, m.PositionMs, m.Paused, m.ReportedAt, m.Left, m.Together)).ToList())).ToList();
        }
    }

    /// <summary>Takes the previous logic's sessions.</summary>
    public void ImportState(IEnumerable<WatchSessionState> sessions)
    {
        lock (_lock)
        {
            foreach (var x in sessions)
            {
                var s = new Session
                {
                    Id = x.Id, GroupId = x.GroupId, ItemId = x.ItemId, StartedBy = x.StartedBy, Created = x.Created, StartAt = x.StartAt,
                    StartMs = x.StartMs, AnchorAt = x.AnchorAt, AnchorMs = x.AnchorMs, Paused = x.Paused, Ended = x.Ended,
                };
                foreach (var m in x.Members)
                {
                    s.Members[m.UserId] = new Member { UserId = m.UserId, JoinedAt = m.JoinedAt, PositionMs = m.PositionMs, Paused = m.Paused, ReportedAt = m.ReportedAt, Left = m.Left, Together = m.Together };
                }

                _sessions[s.Id] = s;
            }
        }
    }

    /// <summary>The group position: the start position until the start moment, then the anchor moved on by the time since (unless paused).</summary>
    public static long PositionAt(DateTimeOffset? startAt, long startMs, DateTimeOffset anchorAt, long anchorMs, bool paused, DateTimeOffset now)
    {
        if (startAt is not { } at || now < at)
        {
            return startMs;
        }

        var from = anchorAt < at ? at : anchorAt;
        return paused ? anchorMs : anchorMs + (long)(now - from).TotalMilliseconds;
    }

    private static long Position(Session s, DateTimeOffset now) => PositionAt(s.StartAt, s.StartMs, s.AnchorAt, s.AnchorMs, s.Paused, now);

    private static bool In(Session s, Guid user) => s.Members.TryGetValue(user, out var m) && !m.Left;

    /// <summary>Who sets the pace: the starter while they're in it, else whoever joined first.</summary>
    private static Guid? Leader(Session s) =>
        In(s, s.StartedBy) ? s.StartedBy : s.Members.Values.Where(m => !m.Left).OrderBy(m => m.JoinedAt).Select(m => (Guid?)m.UserId).FirstOrDefault();

    private Session? LiveOf(Guid groupId) => _sessions.Values.FirstOrDefault(s => s.GroupId == groupId && s.Ended is null);

    /// <summary>A session of one of the caller's groups (others: no such session, so nobody learns it exists).</summary>
    private Session Visible(Guid user, Guid sessionId, DateTimeOffset now)
    {
        Due(now);
        if (!_sessions.TryGetValue(sessionId, out var s) || !(_partners.MembersOf(s.GroupId).Contains(user) || s.Members.ContainsKey(user)))
        {
            throw new PartnerException(PartnerError.NotFound, "No such Group Watch Session.");
        }

        return s;
    }

    private void SetStart(Session s, DateTimeOffset now, Guid? by)
    {
        s.StartAt = now + Countdown;
        s.AnchorAt = s.StartAt.Value;
        s.AnchorMs = s.StartMs;
        Tell(new SessionAnnouncement(s.Id, s.GroupId, EventCountdown, by, s.StartMs, false));
    }

    private void End(Session s, DateTimeOffset now, Guid? by)
    {
        s.Ended = now;
        Tell(new SessionAnnouncement(s.Id, s.GroupId, EventEnded, by, Position(s, now), true));
    }

    private void Due(DateTimeOffset now)
    {
        foreach (var s in _sessions.Values.Where(s => s.Ended is null).ToList())
        {
            var members = _partners.MembersOf(s.GroupId);
            if (members.Count == 0 || !s.Members.Values.Any(m => !m.Left && members.Contains(m.UserId)))
            {
                End(s, now, null);
            }
            else if (s.Members.Values.Where(m => !m.Left).Select(m => m.ReportedAt).DefaultIfEmpty(s.Created).Max() < now - Idle)
            {
                End(s, now, null);
            }
            else if (s.StartAt is null && now - s.Created >= Gather)
            {
                SetStart(s, now, null);
            }
        }

        Prune(now);
    }

    private void Prune(DateTimeOffset now)
    {
        foreach (var old in _sessions.Values.Where(s => s.Ended is { } e && now - e > KeepEnded).Select(s => s.Id).ToList())
        {
            _sessions.Remove(old);
        }
    }

    private SessionJoinView JoinView(Session s, Guid user, DateTimeOffset now) => new(View(s, user, now), s.StartMs, true);

    private SessionView View(Session s, Guid user, DateTimeOffset now)
    {
        var leader = Leader(s);
        var started = s.StartAt is { } at && now >= at;
        var members = s.Members.Values.Where(m => !m.Left).OrderBy(m => m.JoinedAt)
            .Select(m => new SessionMemberView(
                m.UserId, _partners.NameOf(m.UserId) ?? "Former account", m.UserId == user,
                started && !m.Paused ? m.PositionMs + Math.Max(0, (long)(now - (m.ReportedAt < s.StartAt!.Value ? s.StartAt.Value : m.ReportedAt)).TotalMilliseconds) : m.PositionMs,
                m.Paused, m.UserId == leader, m.Together))
            .ToList();
        var state = s.Ended is not null ? Ended : s.StartAt is null ? Waiting : now < s.StartAt ? Starting : Playing;
        var title = _partners.Names(user, s.GroupId)?.Title ?? string.Empty;
        return new SessionView(
            s.Id, s.GroupId, s.ItemId, _partners.PartLabel(s.ItemId), title, _partners.NameOf(s.StartedBy) ?? "Former account", s.StartedBy == user, state,
            s.StartAt, s.StartMs, Position(s, now), s.Paused, s.Created + Gather, members, In(s, user), now);
    }

    private void Tell(SessionAnnouncement a)
    {
        // Raised after the change; listeners hand it to another thread (they never call back in under the lock).
        try
        {
            Announced?.Invoke(a);
        }
        catch (Exception)
        {
            // Telling people is never in the way of the change.
        }
    }

    private sealed class Session
    {
        public Guid Id { get; init; }

        public Guid GroupId { get; init; }

        public Guid ItemId { get; init; }

        public Guid StartedBy { get; init; }

        public DateTimeOffset Created { get; init; }

        public DateTimeOffset? StartAt { get; set; }

        public long StartMs { get; init; }

        public DateTimeOffset AnchorAt { get; set; }

        public long AnchorMs { get; set; }

        public bool Paused { get; set; }

        public DateTimeOffset? Ended { get; set; }

        public Dictionary<Guid, Member> Members { get; } = new();
    }

    private sealed class Member
    {
        public Guid UserId { get; init; }

        public DateTimeOffset JoinedAt { get; init; }

        public long PositionMs { get; set; }

        public bool Paused { get; set; }

        public DateTimeOffset ReportedAt { get; set; }

        public bool Left { get; set; }

        public bool Together { get; init; }
    }
}
