using System.Text.Json;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Session;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Partners;

/// <summary>A connected app session, as the party announcements need it.</summary>
public record LiveApp(string SessionId, Guid UserId, string? Client);

/// <summary>What one member's apps are sent about a group's party (the text of a "Nitrate.Party" message).</summary>
public record PartyMessage(string Kind, Guid GroupId, string SeriesName, IReadOnlyList<string> Others, string? ByName, bool ByMe, int Minutes, PartyView? Party);

/// <summary>
/// What one member's apps are sent about a group's schedule change (also under the header
/// <see cref="PartyPush.Header"/>; the first fields are a <see cref="PartyMessage"/>'s, the member's
/// own party view included, so older apps read it as party news that changes nothing): a change to
/// agree to (<see cref="ScheduleNews.Ask"/>) or how one went (<see cref="ScheduleNews.Done"/>, with
/// <paramref name="Outcome"/> and the schedule as it is now in <paramref name="Current"/>).
/// </summary>
public record ScheduleMessage(
    string Kind, Guid GroupId, string SeriesName, IReadOnlyList<string> Others, string? ByName, bool ByMe, int Minutes, PartyView? Party,
    Guid ChangeId, IReadOnlyList<string> Titles, string? FromName, bool FromMe, GroupSchedule Proposed, GroupSchedule? Current, string? Outcome);

/// <summary>
/// What one member's apps are sent about a Group Watch Session (<see cref="WatchSessions"/>), also
/// under <see cref="PartyPush.Header"/> with the kind <see cref="PartyPush.SessionKind"/>: the first
/// fields are a <see cref="PartyMessage"/>'s, so older apps read it as party news that changes
/// nothing. <paramref name="Event"/> says what happened (started, countdown, joined, left, pause,
/// play, seek, ended), <paramref name="PositionMs"/> where, and <paramref name="Session"/> is the
/// session as that member sees it (with the server's clock).
/// </summary>
public record SessionMessage(
    string Kind, Guid GroupId, string SeriesName, IReadOnlyList<string> Others, string? ByName, bool ByMe, int Minutes, PartyView? Party,
    string Event, long PositionMs, SessionView Session);

/// <summary>
/// Tells the members' open Nitrate apps about their groups' watch parties at once, over Jellyfin's
/// own session connection (a DisplayMessage with the header <see cref="Header"/>, which the apps
/// never show as a message): the reminder 15 minutes before, postponements, requests to agree to,
/// tonight being off, and at start time whether to start without someone. Nothing goes through
/// any outside service; phones with the app closed set their own reminders. Checks the clock
/// every half minute. Schedule changes go the same way (<see cref="ScheduleMessage"/>): asked for,
/// to everyone in the group but the asker; settled (passed, failed or lapsed), to everyone in it.
/// </summary>
public class PartyPush : IHostedService, IDisposable
{
    /// <summary>The message header the apps listen for.</summary>
    public const string Header = "Nitrate.Party";

    /// <summary>The kind of a Group Watch Session's news.</summary>
    public const string SessionKind = "session";

    private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = null };
    private readonly ISessionManager _sessions;
    private readonly PartnerHub _partners;
    private readonly Func<Guid, string?> _names;
    private readonly WatchSessions? _watchSessions;
    private Timer? _timer;
    private Timer? _sessionTimer;

    /// <summary>Initializes a new instance of the <see cref="PartyPush"/> class.</summary>
    public PartyPush(ISessionManager sessions, PartnerHub partners, MediaBrowser.Controller.Library.IUserManager users, WatchSessions? watchSessions = null)
    {
        _sessions = sessions;
        _watchSessions = watchSessions;
        _partners = partners;
        _names = id => users.GetUserById(id)?.Username;
    }

    /// <summary>Whether a session is one of Nitrate's apps (TV and iPhone "Nitrate", phones "Nitrate Mobile").</summary>
    public static bool IsNitrate(string? client) => client is not null && (client == "Nitrate" || client.StartsWith("Nitrate ", StringComparison.Ordinal));

    /// <summary>The sessions an announcement for a group with [members] goes to: only those members' Nitrate apps.</summary>
    public static IReadOnlyList<LiveApp> Targets(IReadOnlyCollection<Guid> members, IEnumerable<LiveApp> sessions) =>
        sessions.Where(s => members.Contains(s.UserId) && IsNitrate(s.Client)).ToList();

    /// <summary>The message for [member] about [a] (null when they're no longer in the group, or it has no party).</summary>
    public static PartyMessage? MessageFor(PartyAnnouncement a, Guid member, PartnerHub partners, Func<Guid, string?> names)
    {
        PartyView? party;
        (string Title, IReadOnlyList<string> Others)? group;
        try
        {
            party = partners.Party(member, a.GroupId);
            group = partners.Names(member, a.GroupId);
        }
        catch (PartnerException)
        {
            return null;
        }

        if (party is null || group is not { } g)
        {
            return null;
        }

        return new PartyMessage(a.Kind, a.GroupId, g.Title, g.Others, a.By is { } by ? names(by) : null, a.By == member, a.Minutes, party);
    }

    /// <summary>The sessions a schedule announcement goes to: the group's members' Nitrate apps, but not the asker's for an ask.</summary>
    public static IReadOnlyList<LiveApp> Targets(ScheduleAnnouncement a, IReadOnlyCollection<Guid> members, IEnumerable<LiveApp> sessions) =>
        Targets(members.Where(m => a.Kind != ScheduleNews.Ask || m != a.From).ToList(), sessions);

    /// <summary>The message for [member] about schedule change [a] (null when they're no longer in the group).</summary>
    public static ScheduleMessage? MessageFor(ScheduleAnnouncement a, Guid member, PartnerHub partners, Func<Guid, string?> names)
    {
        PartyView? party;
        (string Title, IReadOnlyList<string> Others)? group;
        IReadOnlyList<string>? titles;
        try
        {
            group = partners.Names(member, a.GroupId);
            titles = partners.TitlesOf(member, a.GroupId);
            party = group is null ? null : partners.Party(member, a.GroupId);
        }
        catch (PartnerException)
        {
            return null;
        }

        if (group is not { } g || titles is null)
        {
            return null;
        }

        var current = a.Kind == ScheduleNews.Done ? partners.ScheduleOf(member, a.GroupId) : null;
        return new ScheduleMessage(
            a.Kind, a.GroupId, g.Title, g.Others, a.By is { } by ? names(by) : null, a.By == member, 0, party,
            a.ChangeId, titles, names(a.From), a.From == member, a.Proposed, current, a.Outcome);
    }

    /// <summary>
    /// Who a session's news goes to: the whole group's Nitrate apps for a start or an end, else
    /// only those in the session ([inSession]); never the one who did it, except their other devices for a start.
    /// </summary>
    public static IReadOnlyList<LiveApp> Targets(SessionAnnouncement a, IReadOnlyCollection<Guid> members, IReadOnlyCollection<Guid> inSession, IEnumerable<LiveApp> sessions) =>
        Targets(
            (a.ToGroup ? members : inSession.Where(members.Contains).ToList())
                .Where(m => a.ToGroup || a.Event == WatchSessions.EventCountdown || m != a.By).ToList(),
            sessions);

    /// <summary>The message for [member] about session news [a] (null when they can't see the session any more).</summary>
    public static SessionMessage? MessageFor(SessionAnnouncement a, Guid member, PartnerHub partners, WatchSessions sessions, Func<Guid, string?> names)
    {
        SessionView view;
        (string Title, IReadOnlyList<string> Others)? group;
        PartyView? party = null;
        try
        {
            view = sessions.Get(member, a.SessionId);
            group = partners.Names(member, a.GroupId);
            if (group is not null)
            {
                try
                {
                    party = partners.Party(member, a.GroupId);
                }
                catch (PartnerException)
                {
                    // No party to pass along.
                }
            }
        }
        catch (PartnerException)
        {
            return null;
        }

        if (group is not { } g)
        {
            return null;
        }

        return new SessionMessage(SessionKind, a.GroupId, g.Title, g.Others, a.By is { } by ? names(by) : null, a.By == member, 0, party, a.Event, a.PositionMs, view);
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _partners.PartyAnnounced += OnAnnounced;
        _partners.ScheduleAnnounced += OnScheduleAnnounced;
        _timer = new Timer(_ => Tick(), null, TimeSpan.FromSeconds(20), TimeSpan.FromSeconds(30));
        if (_watchSessions is not null)
        {
            _watchSessions.Announced += OnSessionAnnounced;
            // Gatherings that are up start within a couple of seconds; nothing to do while no session is live.
            _sessionTimer = new Timer(_ => SessionTick(), null, TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(2));
        }

        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _partners.PartyAnnounced -= OnAnnounced;
        _partners.ScheduleAnnounced -= OnScheduleAnnounced;
        _timer?.Change(Timeout.Infinite, Timeout.Infinite);
        if (_watchSessions is not null)
        {
            _watchSessions.Announced -= OnSessionAnnounced;
        }

        _sessionTimer?.Change(Timeout.Infinite, Timeout.Infinite);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _timer?.Dispose();
        _sessionTimer?.Dispose();
        GC.SuppressFinalize(this);
    }

    private void SessionTick()
    {
        try
        {
            if (_watchSessions is { AnyLive: true } s)
            {
                s.Due();
            }
        }
        catch (Exception)
        {
            // Try again next time.
        }
    }

    private void OnSessionAnnounced(SessionAnnouncement a)
    {
        var sessions = _watchSessions!;
        _ = Task.Run(() => SendAsync(
            Targets(a, _partners.MembersOf(a.GroupId), sessions.PeopleIn(a.SessionId), Live()),
            member => MessageFor(a, member, _partners, sessions, _names)));
    }

    private void Tick()
    {
        try
        {
            // Raises PartyAnnounced for whatever's due.
            _partners.DueParties();

            // Raises ScheduleAnnounced for changes that lapsed.
            _partners.DueChanges();
        }
        catch (Exception)
        {
            // Try again next time.
        }
    }

    private void OnAnnounced(PartyAnnouncement a) => _ = Task.Run(() =>
        SendAsync(Targets(_partners.MembersOf(a.GroupId), Live()), member => MessageFor(a, member, _partners, _names)));

    private void OnScheduleAnnounced(ScheduleAnnouncement a) => _ = Task.Run(() =>
        SendAsync(Targets(a, _partners.MembersOf(a.GroupId), Live()), member => MessageFor(a, member, _partners, _names)));

    private List<LiveApp> Live() => _sessions.Sessions.Where(s => s.IsActive).Select(s => new LiveApp(s.Id, s.UserId, s.Client)).ToList();

    private async Task SendAsync(IReadOnlyList<LiveApp> targets, Func<Guid, object?> messageFor)
    {
        foreach (var byMember in targets.GroupBy(s => s.UserId))
        {
            object? message;
            try
            {
                message = messageFor(byMember.Key);
            }
            catch (Exception)
            {
                continue;
            }

            if (message is null)
            {
                continue;
            }

            var text = JsonSerializer.Serialize(message, message.GetType(), Json);
            foreach (var s in byMember)
            {
                try
                {
                    await _sessions.SendMessageCommand(null, s.SessionId, new MessageCommand { Header = Header, Text = text }, CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception)
                {
                    // The app went away meanwhile.
                }
            }
        }
    }
}
