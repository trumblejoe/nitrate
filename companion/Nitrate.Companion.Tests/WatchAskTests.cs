using System.Globalization;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Asks to watch together: the group is asked, the first to say "Start it" hosts.</summary>
public class WatchAskTests
{
    private static readonly Guid Joe = Guid.NewGuid();
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Out = Guid.NewGuid();
    private static readonly Guid Show = Guid.NewGuid();
    private static readonly Guid Ep1 = Guid.NewGuid();
    private static readonly Guid Ep2 = Guid.NewGuid();

    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-19T20:00:00Z", CultureInfo.InvariantCulture));
    private readonly List<AskAnnouncement> _told = new();

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

    private (PartnerHub Partners, WatchAsks Asks, Guid Group) Setup(bool three = false)
    {
        var hub = new PartnerHub(new Storage(), new People(), new Watch(), _time);
        hub.Request(Joe, Sam, null, Show, "Bear");
        var g = hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        if (three)
        {
            hub.Request(Joe, Kim, null, Show, "Bear", g.Id);
            hub.Accept(Kim, hub.Incoming(Kim).Single().Id);
        }

        var asks = new WatchAsks(hub, _time);
        asks.Announced += _told.Add;
        return (hub, asks, g.Id);
    }

    private void Wait(double seconds) => _time.Advance(TimeSpan.FromSeconds(seconds));

    [Fact]
    public void EveryoneElseIsAskedAndTheFirstToTakeItHostsIt()
    {
        var (_, asks, gid) = Setup(three: true);
        var ask = asks.Create(Kim, gid, Ep1, 0);
        var told = Assert.Single(_told);
        Assert.Equal((WatchAsks.EventAsked, (Guid?)Kim), (told.Event, told.By));
        Assert.Null(told.To);
        Assert.Single(asks.Mine(Joe));
        Assert.True(asks.Mine(Kim).Single().FromMe);

        // Sam takes it; Joe is too late, and everyone hears it's taken (their questions go away).
        var taken = asks.Take(Sam, ask.Id);
        Assert.Equal((WatchAsks.Taken, true), (taken.State, taken.TakenByMe));
        Assert.Equal(new[] { Joe, Sam, Kim }.OrderBy(x => x), _told.Last().To!.OrderBy(x => x));
        var late = Assert.Throws<PartnerException>(() => asks.Take(Joe, ask.Id));
        Assert.Contains("sam is already starting it", late.Message, StringComparison.Ordinal);
        Assert.Empty(asks.Mine(Joe));
        Assert.Equal("sam", asks.Mine(Kim).Single().TakenBy);

        // Nobody takes their own; nobody outside the group sees it.
        var mine = asks.Create(Kim, gid, Ep2, 0);
        Assert.Throws<PartnerException>(() => asks.Take(Kim, mine.Id));
        Assert.Throws<PartnerException>(() => asks.Take(Out, mine.Id));
        Assert.Empty(asks.Mine(Out));
    }

    [Fact]
    public void NotNowIsToldToTheAskerAndWhenEveryoneSaysItTheAskIsOver()
    {
        var (_, asks, gid) = Setup(three: true);
        var ask = asks.Create(Kim, gid, Ep1, 0);
        asks.Decline(Joe, ask.Id);
        var told = _told.Last();
        Assert.Equal((WatchAsks.EventDeclined, (Guid?)Joe), (told.Event, told.By));
        Assert.Equal(new[] { Kim }, told.To);
        Assert.Empty(asks.Mine(Joe));
        Assert.Equal(WatchAsks.Open, asks.Mine(Sam).Single().State);

        asks.Decline(Sam, ask.Id);
        var seen = asks.Mine(Kim).Single();
        Assert.Equal(WatchAsks.Declined, seen.State);
        Assert.Equal(new[] { "joe", "sam" }, seen.Declined);
    }

    [Fact]
    public void ALaterAskRemindsWhoTookItWhenItsTimeAndOneNobodyTakesLapses()
    {
        var (_, asks, gid) = Setup();
        Assert.Throws<PartnerException>(() => asks.Create(Joe, gid, Ep1, 20));
        var later = asks.Create(Joe, gid, Ep1, 30);
        Assert.Equal(_time.GetUtcNow().AddMinutes(30), later.At);
        asks.Take(Sam, later.Id);
        Wait(29 * 60);
        asks.Due();
        Assert.NotEqual(WatchAsks.EventDue, _told.Last().Event);
        Wait(61);
        asks.Due();
        var due = _told.Last();
        Assert.Equal(WatchAsks.EventDue, due.Event);
        Assert.Equal(new[] { Sam }, due.To);
        asks.Due();
        Assert.Single(_told, t => t.Event == WatchAsks.EventDue);

        // Taken now: nothing to remind.
        var now = asks.Create(Joe, gid, Ep2, 0);
        asks.Take(Sam, now.Id);
        asks.Due();
        Assert.Single(_told, t => t.Event == WatchAsks.EventDue);

        // Nobody takes it: three hours after its time, the asker hears it's over.
        var nobody = asks.Create(Sam, gid, Ep1, 0);
        Wait(3 * 3600 + 1);
        asks.Due();
        Assert.Equal(WatchAsks.EventExpired, _told.Last().Event);
        Assert.Equal(new[] { Sam }, _told.Last().To);
        Assert.Empty(asks.Mine(Joe));
        Assert.DoesNotContain(asks.Mine(Sam), a => a.Id == nobody.Id && a.State == WatchAsks.Open);
    }

    [Fact]
    public void TheAskerCallsItOffOrReplacesItAndAsksSurviveALogicUpdate()
    {
        var (hub, asks, gid) = Setup();
        var first = asks.Create(Joe, gid, Ep1, 0);
        var second = asks.Create(Joe, gid, Ep2, 15);
        Assert.Contains(_told, t => t.AskId == first.Id && t.Event == WatchAsks.EventCancelled);
        Assert.Equal(new[] { second.Id }, asks.Mine(Sam).Select(a => a.Id));
        Assert.Throws<PartnerException>(() => asks.Cancel(Sam, second.Id));

        var next = new WatchAsks(hub, _time);
        next.ImportState(asks.ExportState());
        Assert.Equal(second.At, next.Mine(Sam).Single().At);
        next.Cancel(Joe, second.Id);
        Assert.Empty(next.Mine(Sam));
    }
}
