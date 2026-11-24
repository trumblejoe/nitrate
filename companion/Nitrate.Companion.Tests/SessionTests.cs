using System.Globalization;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Xunit;

namespace Nitrate.Companion.Tests;

public class SessionTests
{
    private static readonly Guid Joe = Guid.NewGuid();
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Out = Guid.NewGuid();
    private static readonly Guid Show = Guid.NewGuid();
    private static readonly Guid Ep1 = Guid.NewGuid();
    private static readonly Guid Ep2 = Guid.NewGuid();

    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-19T20:00:00Z", CultureInfo.InvariantCulture));
    private readonly List<SessionAnnouncement> _told = new();

    private sealed class Storage : IPartnerStorage
    {
        private string _json = System.Text.Json.JsonSerializer.Serialize(new PartnerState());

        public PartnerState Load() => System.Text.Json.JsonSerializer.Deserialize<PartnerState>(_json)!;

        public void Save(PartnerState state) => _json = System.Text.Json.JsonSerializer.Serialize(state);
    }

    private sealed class People : IPartnerDirectory
    {
        private readonly Dictionary<Guid, string> _names = new() { [Joe] = "joe", [Sam] = "sam", [Kim] = "kim", [Out] = "out" };

        public IReadOnlyList<(Guid Id, string Name)> Users() => _names.Select(p => (p.Key, p.Value)).ToList();

        public (Guid Id, string Name)? Find(string name) => _names.Where(p => p.Value == name).Select(p => ((Guid, string)?)(p.Key, p.Value)).FirstOrDefault();

        public string? NameOf(Guid id) => _names.GetValueOrDefault(id);

        public bool AdminPrivate(Guid id) => false;
    }

    private sealed class Watch : IWatchState
    {
        public ShowProgress Progress(Guid userId, Guid titleId) => new(new List<Guid>(), null, null, new[] { Ep1, Ep2 }, null);

        public bool Belongs(Guid titleId, Guid itemId, Guid userId) => titleId == Show && (itemId == Ep1 || itemId == Ep2);

        public string? Label(Guid itemId) => itemId == Ep1 ? "S1 E1" : itemId == Ep2 ? "S1 E2" : null;
    }

    private (PartnerHub Partners, WatchSessions Sessions, Guid Group) Setup(bool three = false)
    {
        var hub = new PartnerHub(new Storage(), new People(), new Watch(), _time);
        hub.Request(Joe, Sam, null, Show, "Bear");
        var g = hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        if (three)
        {
            hub.Request(Joe, Kim, null, Show, "Bear", g.Id);
            hub.Accept(Kim, hub.Incoming(Kim).Single().Id);
        }

        var sessions = new WatchSessions(hub, _time);
        sessions.Announced += _told.Add;
        return (hub, sessions, g.Id);
    }

    private void Wait(double seconds) => _time.Advance(TimeSpan.FromSeconds(seconds));

    [Fact]
    public void EveryoneInTheFirstMinuteStartsTogetherAtACountdown()
    {
        var (_, sessions, gid) = Setup(three: true);
        var start = sessions.Start(Joe, gid, Ep1);
        var id = start.Session.Id;
        Assert.Equal(WatchSessions.Waiting, start.Session.State);
        Assert.True(start.Together);
        Assert.Equal("S1:E1", start.Session.PartLabel);
        Assert.Equal(_time.GetUtcNow(), start.Session.ServerTime);

        // The whole group hears it started.
        var started = Assert.Single(_told);
        Assert.Equal(WatchSessions.EventStarted, started.Event);
        Assert.True(started.ToGroup);

        // Sam joins 20 s in: still gathering, together, at the start position.
        Wait(20);
        var sam = sessions.Join(Sam, id, fromStart: false);
        Assert.True(sam.Together);
        Assert.Equal(0, sam.StartMs);
        Assert.Null(sam.Session.StartAt);

        // The minute is up: the start moment is set a few seconds ahead, and who's in hears it.
        Wait(40);
        sessions.Due();
        var view = sessions.Get(Joe, id);
        Assert.Equal(WatchSessions.Starting, view.State);
        Assert.Equal(_time.GetUtcNow() + WatchSessions.Countdown, view.StartAt);
        Assert.Contains(_told, a => a.Event == WatchSessions.EventCountdown);

        // Before the moment the group stands at its start; after it, it moves on.
        Assert.Equal(0, view.PositionMs);
        Wait(15);
        Assert.Equal(WatchSessions.Playing, sessions.Get(Sam, id).State);
        Assert.Equal(10_000, sessions.Get(Sam, id).PositionMs);

        // Kim, late, starts where the group is now, or from the beginning if she chooses.
        var kim = sessions.Join(Kim, id, fromStart: false);
        Assert.False(kim.Together);
        Assert.Equal(10_000, kim.StartMs);
        Assert.Equal(0, sessions.Join(Kim, id, fromStart: true).StartMs);
    }

    [Fact]
    public void EveryoneInStartsAtOnceAndTheStarterCanStartWithoutTheRest()
    {
        var (_, sessions, gid) = Setup(three: true);
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        Wait(5);
        sessions.Join(Sam, id, false);
        Assert.Null(sessions.Get(Joe, id).StartAt);

        // Joe doesn't wait for Kim.
        var view = sessions.StartNow(Joe, id);
        Assert.Equal(_time.GetUtcNow() + WatchSessions.Countdown, view.StartAt);

        // In a pair, the second one in sets the countdown.
        var (_, pair, pid) = Setup();
        var p = pair.Start(Joe, pid, Ep2).Session.Id;
        Assert.Null(pair.Get(Sam, p).StartAt);
        Assert.NotNull(pair.Join(Sam, p, false).Session.StartAt);
    }

    [Fact]
    public void PausesAndJumpsAreToldAndTheLeadersPaceIsTheGroups()
    {
        var (_, sessions, gid) = Setup();
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        sessions.Join(Sam, id, false);
        Wait(5 + 60);
        _told.Clear();

        // Sam (not the leader) pauses: the others are told, the group carries on.
        sessions.Report(Sam, id, 60_000, paused: true, WatchSessions.EventPause);
        var told = Assert.Single(_told);
        Assert.Equal((WatchSessions.EventPause, (Guid?)Sam, 60_000L), (told.Event, told.By, told.PositionMs));
        Assert.False(sessions.Get(Joe, id).Paused);

        // Joe (who started it) pauses at 61 s: the group's clock stands there.
        sessions.Report(Joe, id, 61_000, paused: true, WatchSessions.EventPause);
        Wait(30);
        var view = sessions.Get(Sam, id);
        Assert.True(view.Paused);
        Assert.Equal(61_000, view.PositionMs);
        Assert.Equal(60_000, view.Members.Single(m => m.Me).PositionMs);
        Assert.True(view.Members.Single(m => !m.Me).Leader);

        // A jump is told with where to.
        sessions.Report(Joe, id, 120_000, paused: false, WatchSessions.EventSeek);
        Assert.Equal(120_000, _told.Last().PositionMs);
        Wait(10);
        Assert.Equal(130_000, sessions.Get(Sam, id).PositionMs);

        // Regular reports tell nobody.
        var before = _told.Count;
        sessions.Report(Sam, id, 125_000, false, null);
        Assert.Equal(before, _told.Count);
    }

    [Fact]
    public void OnlyTheGroupSeesItsSessionsAndOnlyItsTitlesPlay()
    {
        var (_, sessions, gid) = Setup();
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => sessions.Get(Out, id)).Error);
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => sessions.Join(Out, id, false)).Error);
        Assert.Empty(sessions.Mine(Out));
        Assert.Single(sessions.Mine(Sam));
        Assert.Equal(PartnerError.Invalid, Assert.Throws<PartnerException>(() => sessions.Start(Sam, gid, Guid.NewGuid())).Error);
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => sessions.Start(Out, gid, Ep1)).Error);

        // A group has one live session: starting again joins it.
        Assert.Equal(id, sessions.Start(Sam, gid, Ep2).Session.Id);
    }

    [Fact]
    public void ThePartCountsForWhoPlayedItInTheSessionsGroup()
    {
        var (hub, sessions, gid) = Setup();
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        sessions.Join(Sam, id, false);

        // The recorder asks which session someone played it in, then counts it there for them alone.
        var on = sessions.SessionOn(Sam, Ep1);
        Assert.Equal((id, gid), on);
        Assert.Null(sessions.SessionOn(Sam, Ep2));
        Assert.Null(sessions.SessionOn(Kim, Ep1));
        Assert.Equal(new[] { gid }, hub.RecordLive(Ep1, new[] { Sam }, on!.Value.Id.ToString()));
        var record = hub.Groups(Sam).Single().Mine!;
        Assert.Contains(Ep1, record);
        Assert.DoesNotContain(Ep1, hub.Groups(Joe).Single().Mine!);
    }

    [Fact]
    public void TheLastOneOutOrTheStarterOrTheClockEndsIt()
    {
        var (_, sessions, gid) = Setup(three: true);
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        sessions.Join(Sam, id, false);

        // Only the starter ends it for everyone.
        Assert.Throws<PartnerException>(() => sessions.EndSession(Sam, id));
        sessions.Leave(Joe, id);
        Assert.Equal(WatchSessions.Waiting, sessions.Get(Sam, id).State);
        Assert.True(sessions.Get(Sam, id).Members.Single().Leader);
        sessions.Leave(Sam, id);
        Assert.Equal(WatchSessions.Ended, sessions.Get(Kim, id).State);
        var ended = _told.Last();
        Assert.Equal(WatchSessions.EventEnded, ended.Event);
        Assert.True(ended.ToGroup);
        Assert.Throws<PartnerException>(() => sessions.Join(Kim, id, false));

        // Nobody reporting for a while ends it too; a new one can start.
        var next = sessions.Start(Kim, gid, Ep2).Session.Id;
        Assert.NotEqual(id, next);
        Wait(WatchSessions.Idle.TotalSeconds + 60);
        sessions.Due();
        Assert.Equal(WatchSessions.Ended, sessions.Get(Kim, next).State);
        Assert.False(sessions.AnyLive);
    }

    [Fact]
    public void ChatAndNotesSeeOnlyWhoIsInIt()
    {
        var (_, sessions, gid) = Setup(three: true);
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        sessions.Join(Sam, id, false);
        Assert.True(sessions.Knows(id));
        Assert.Equal(new[] { Joe, Sam }.Order(), sessions.Participants(id, Sam)!.Order());
        Assert.Null(sessions.Participants(id, Kim));
        Assert.Equal(Ep1, sessions.PlayingIn(id));
        Assert.False(sessions.Knows(Guid.NewGuid()));
    }

    [Fact]
    public void NewsGoesToTheGroupOrToWhoIsInIt()
    {
        var apps = new[]
        {
            new LiveApp("joe-tv", Joe, "Nitrate"), new LiveApp("sam-phone", Sam, "Nitrate Mobile"), new LiveApp("kim-tv", Kim, "Nitrate"),
            new LiveApp("sam-web", Sam, "Jellyfin Web"), new LiveApp("out-tv", Out, "Nitrate"),
        };
        var members = new[] { Joe, Sam, Kim };
        var s = Guid.NewGuid();
        var g = Guid.NewGuid();

        // A start goes to the whole group's Nitrate apps (the starter's other devices too), never outside it.
        var started = PartyPush.Targets(new SessionAnnouncement(s, g, WatchSessions.EventStarted, Joe, 0, true), members, new[] { Joe }, apps);
        Assert.Equal(new[] { "joe-tv", "sam-phone", "kim-tv" }, started.Select(a => a.SessionId));

        // A pause goes to the others in the session only.
        var paused = PartyPush.Targets(new SessionAnnouncement(s, g, WatchSessions.EventPause, Joe, 1000, false), members, new[] { Joe, Sam }, apps);
        Assert.Equal(new[] { "sam-phone" }, paused.Select(a => a.SessionId));

        // The countdown goes to everyone in it, whoever set it.
        var countdown = PartyPush.Targets(new SessionAnnouncement(s, g, WatchSessions.EventCountdown, Joe, 0, false), members, new[] { Joe, Sam }, apps);
        Assert.Equal(new[] { "joe-tv", "sam-phone" }, countdown.Select(a => a.SessionId));
    }

    [Fact]
    public void TheMessageCarriesTheSessionAndTheServersClock()
    {
        var (hub, sessions, gid) = Setup();
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        var message = PartyPush.MessageFor(_told.Single(), Sam, hub, sessions, u => u == Joe ? "joe" : "sam")!;
        Assert.Equal(PartyPush.SessionKind, message.Kind);
        Assert.Equal(WatchSessions.EventStarted, message.Event);
        Assert.Equal("joe", message.ByName);
        Assert.False(message.ByMe);
        Assert.Equal(id, message.Session.Id);
        Assert.False(message.Session.Joined);
        Assert.Equal(_time.GetUtcNow(), message.Session.ServerTime);
        Assert.Null(PartyPush.MessageFor(_told.Single(), Out, hub, sessions, _ => null));
    }

    [Fact]
    public void HandedOverSessionsCarryOn()
    {
        var (hub, sessions, gid) = Setup();
        var id = sessions.Start(Joe, gid, Ep1).Session.Id;
        sessions.Join(Sam, id, false);
        Wait(10);
        var state = System.Text.Json.JsonSerializer.Deserialize<List<WatchSessionState>>(System.Text.Json.JsonSerializer.Serialize(sessions.ExportState()))!;
        var next = new WatchSessions(hub, _time);
        next.ImportState(state);
        var view = next.Get(Sam, id);
        Assert.Equal(WatchSessions.Playing, view.State);
        Assert.Equal(5_000, view.PositionMs);
        Assert.Equal(2, view.Members.Count);
    }
}
