using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Xunit;

namespace Nitrate.Companion.Tests;

public class PartnerTests
{
    private static readonly Guid Joe = Guid.NewGuid();
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Hidden = Guid.NewGuid();
    private static readonly Guid Kid = Guid.NewGuid();
    private static readonly Guid Bear = Guid.NewGuid();
    private static readonly Guid Ep1 = Guid.NewGuid();
    private static readonly Guid Ep2 = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly MemoryStorage _storage = new();
    private readonly Directory _directory = new();
    private readonly Watch _watch = new();

    private sealed class MemoryStorage : IPartnerStorage
    {
        public PartnerState State { get; set; } = new();

        public int Saves { get; private set; }

        public PartnerState Load() => System.Text.Json.JsonSerializer.Deserialize<PartnerState>(System.Text.Json.JsonSerializer.Serialize(State))!;

        public void Save(PartnerState state)
        {
            State = System.Text.Json.JsonSerializer.Deserialize<PartnerState>(System.Text.Json.JsonSerializer.Serialize(state))!;
            Saves++;
        }
    }

    private sealed class Directory : IPartnerDirectory
    {
        public Dictionary<Guid, string> People { get; } = new() { [Joe] = "joe", [Sam] = "sam", [Hidden] = "quiet", [Kid] = "kid" };

        public HashSet<Guid> AdminPrivateIds { get; } = new() { Hidden };

        public IReadOnlyList<(Guid Id, string Name)> Users() => People.Select(p => (p.Key, p.Value)).ToList();

        public (Guid Id, string Name)? Find(string name) =>
            People.Where(p => string.Equals(p.Value, name.Trim(), StringComparison.OrdinalIgnoreCase)).Select(p => ((Guid, string)?)(p.Key, p.Value)).FirstOrDefault();

        public string? NameOf(Guid id) => People.GetValueOrDefault(id);

        public bool AdminPrivate(Guid id) => AdminPrivateIds.Contains(id);
    }

    private sealed class Watch : IWatchState
    {
        public HashSet<(Guid User, Guid Item)> Played { get; } = new();

        public ShowProgress Progress(Guid userId, Guid seriesId)
        {
            var watched = new[] { Ep1, Ep2 }.Where(e => Played.Contains((userId, e))).ToList();
            return new ShowProgress(watched, watched.Count == 0 ? null : $"S1 E{watched.Count}", watched.Count < 2 ? new[] { Ep1, Ep2 }[watched.Count] : null);
        }

        public Guid? SeriesOf(Guid itemId) => itemId == Ep1 || itemId == Ep2 ? Bear : null;

        public void MarkWatched(Guid userId, Guid itemId) => Played.Add((userId, itemId));
    }

    private PartnerHub Hub() => new(_storage, _directory, _watch, _time);

    [Fact]
    public void PrivateAccountsAreNeverListedOrFound()
    {
        var hub = Hub();
        Assert.Equal(new[] { "kid", "sam" }, hub.VisibleTo(Joe).Select(u => u.Name));
        Assert.Null(hub.FindVisible("quiet"));
        Assert.NotNull(hub.FindVisible("SAM"));
        // Making yourself private works the same way, and can be undone.
        hub.SetPrivate(Kid, true);
        Assert.Equal(new[] { "sam" }, hub.VisibleTo(Joe).Select(u => u.Name));
        Assert.Equal((true, false), hub.Privacy(Kid));
        Assert.Equal((false, true), hub.Privacy(Hidden));
        hub.SetPrivate(Kid, false);
        Assert.Contains(hub.VisibleTo(Joe), u => u.Name == "kid");
        // A private person can still see others.
        Assert.Contains(hub.VisibleTo(Hidden), u => u.Name == "joe");
    }

    [Fact]
    public void ARequestToAPrivateNameLooksTheSameAsToNobody()
    {
        var hub = Hub();
        var toPrivate = hub.Request(Joe, null, "quiet", Bear, "The Bear");
        var toNobody = hub.Request(Joe, null, "no-such-person", Bear, "The Bear");
        // Same shape for the asker: the typed name, waiting.
        Assert.Equal("quiet", toPrivate.To);
        Assert.Equal("no-such-person", toNobody.To);
        Assert.Equal(2, hub.Outgoing(Joe).Count);
        // Only the real person sees one.
        Assert.Single(hub.Incoming(Hidden));
        // Asking by id for someone you can't see goes nowhere either.
        hub.Request(Sam, Hidden, null, Bear, "The Bear");
        Assert.Single(hub.Incoming(Hidden));
        Assert.Equal("Someone", hub.Outgoing(Sam).Single().To);
    }

    [Fact]
    public void DecliningIsNeverToldToTheAsker()
    {
        var hub = Hub();
        var r = hub.Request(Joe, Sam, null, Bear, "The Bear");
        hub.Decline(Sam, hub.Incoming(Sam).Single().Id);
        Assert.Empty(hub.Incoming(Sam));
        // Still waiting, as far as Joe can tell, until it lapses.
        Assert.Equal(r.Id, hub.Outgoing(Joe).Single().Id);
        _time.Advance(PartnerHub.RequestLifetime + TimeSpan.FromMinutes(1));
        Assert.Empty(hub.Outgoing(Joe));
    }

    [Fact]
    public void AcceptingMakesPartnersAndAPrivatePersonCanSayYes()
    {
        var hub = Hub();
        hub.Request(Joe, null, "Quiet", Bear, "The Bear");
        var view = hub.Accept(Hidden, hub.Incoming(Hidden).Single().Id);
        Assert.Equal("joe", view.UserName);
        // Now Joe knows them, and only Joe.
        Assert.Equal("quiet", hub.Partners(Joe).Single().UserName);
        Assert.Empty(hub.Partners(Sam));
        Assert.DoesNotContain(hub.VisibleTo(Sam), u => u.Id == Hidden);
        Assert.Empty(hub.Outgoing(Joe));
        // A second show joins the same partnership.
        var other = Guid.NewGuid();
        hub.AddShow(Hidden, view.Id, other, "Slow Horses");
        Assert.Equal(2, hub.Partners(Joe).Single().Shows.Count);
        Assert.Throws<PartnerException>(() => hub.Request(Joe, Hidden, null, Bear, "The Bear"));
    }

    [Fact]
    public void APrivatePersonCanAskSomeoneVisible()
    {
        var hub = Hub();
        hub.Request(Hidden, Joe, null, Bear, "The Bear");
        var incoming = hub.Incoming(Joe).Single();
        Assert.Equal("quiet", incoming.FromName);
        hub.Accept(Joe, incoming.Id);
        Assert.Single(hub.Partners(Hidden));
    }

    [Fact]
    public void OnlyTheRightPeopleAnswerOrCancel()
    {
        var hub = Hub();
        var r = hub.Request(Joe, Sam, null, Bear, "The Bear");
        Assert.Throws<PartnerException>(() => hub.Accept(Kid, r.Id));
        Assert.Throws<PartnerException>(() => hub.Accept(Joe, r.Id));
        Assert.Throws<PartnerException>(() => hub.Cancel(Sam, r.Id));
        hub.Cancel(Joe, r.Id);
        Assert.Empty(hub.Incoming(Sam));
    }

    [Fact]
    public void ProgressAndWatchingTogetherOnlyForSharedShows()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var p = hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        _watch.Played.Add((Sam, Ep1));
        var progress = hub.Progress(Joe, Bear).Single();
        Assert.Equal("sam", progress.UserName);
        Assert.Equal("S1 E1", progress.Last);
        Assert.Equal(Ep2, progress.NextId);
        // Kid isn't Joe's partner: not marked even if asked.
        Assert.Equal(new[] { Sam }, hub.MarkTogether(Joe, Ep2, new[] { Sam, Kid }));
        Assert.Contains((Sam, Ep2), _watch.Played);
        Assert.DoesNotContain((Kid, Ep2), _watch.Played);
        // After the show leaves the partnership, nothing.
        hub.RemoveShow(Sam, p.Id, Bear);
        Assert.Empty(hub.Progress(Joe, Bear));
        Assert.Empty(hub.MarkTogether(Joe, Ep1, new[] { Sam }));
    }

    [Fact]
    public void KeptBetweenRestarts()
    {
        var hub = Hub();
        hub.SetPrivate(Kid, true);
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var again = Hub();
        Assert.True(again.IsPrivate(Kid));
        Assert.Single(again.Incoming(Sam));
    }
}
