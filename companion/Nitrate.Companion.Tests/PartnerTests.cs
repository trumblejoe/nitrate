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

        public bool Belongs(Guid titleId, Guid itemId, Guid userId) => titleId == Bear && (itemId == Ep1 || itemId == Ep2);

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
    public void AcceptingMakesAGroupAndAPrivatePersonCanSayYes()
    {
        var hub = Hub();
        hub.Request(Joe, null, "Quiet", Bear, "The Bear");
        var incoming = hub.Incoming(Hidden).Single();
        Assert.Equal(new[] { "joe" }, incoming.WillSee);
        var view = hub.Accept(Hidden, incoming.Id);
        Assert.Equal("joe", view.Others.Single().Name);
        // Now Joe knows them, and only Joe.
        Assert.Equal("quiet", hub.Groups(Joe).Single().Others.Single().Name);
        Assert.Empty(hub.Groups(Sam));
        Assert.DoesNotContain(hub.VisibleTo(Sam), u => u.Id == Hidden);
        Assert.Empty(hub.Outgoing(Joe));
        // Joe can now ask them by id (they share a group), e.g. for another show.
        hub.Request(Joe, Hidden, null, Guid.NewGuid(), "Slow Horses");
        Assert.Single(hub.Incoming(Hidden));
    }

    [Fact]
    public void APrivatePersonCanAskSomeoneVisible()
    {
        var hub = Hub();
        hub.Request(Hidden, Joe, null, Bear, "The Bear");
        var incoming = hub.Incoming(Joe).Single();
        Assert.Equal("quiet", incoming.FromName);
        hub.Accept(Joe, incoming.Id);
        Assert.Single(hub.Groups(Hidden));
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

    private Guid Pair(PartnerHub hub, Guid a, Guid b) =>
        hub.Accept(b, hub.Incoming(b).Single(i => i.FromId == a && i.GroupId is null).Id).Id;

    [Fact]
    public void SeparateGroupsOnTheSameShowStaySeparate()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var withSam = Pair(hub, Joe, Sam);
        hub.Request(Joe, Kid, null, Bear, "The Bear");
        var withKid = Pair(hub, Joe, Kid);
        Assert.Equal(2, hub.Groups(Joe).Count);
        // Joe sees both, each under its own group; Sam and Kid don't see each other.
        var progress = hub.Progress(Joe, Bear);
        Assert.Equal(new[] { (withSam, Sam), (withKid, Kid) }, progress.Select(p => (p.GroupId, p.UserId)).OrderBy(x => x.Item2 == Kid));
        Assert.Equal(new[] { Joe }, hub.Progress(Sam, Bear).Select(p => p.UserId));
        // Watching with Sam's group marks Sam only.
        Assert.Equal(new[] { Sam }, hub.MarkTogether(Joe, Ep1, withSam));
        Assert.Contains((Sam, Ep1), _watch.Played);
        Assert.DoesNotContain((Kid, Ep1), _watch.Played);
    }

    [Fact]
    public void AnyMemberInvitesAndEveryoneSeesEveryone()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var group = Pair(hub, Joe, Sam);
        // Sam (a member) brings Kid in; Kid sees who will know them.
        hub.Request(Sam, Kid, null, Guid.Empty, string.Empty, group);
        var invite = hub.Incoming(Kid).Single();
        Assert.Equal(group, invite.GroupId);
        Assert.Equal(new[] { "joe", "sam" }, invite.WillSee.OrderBy(x => x));
        hub.Accept(Kid, invite.Id);
        Assert.Equal(new[] { "joe", "sam" }, hub.Groups(Kid).Single().Others.Select(o => o.Name).OrderBy(x => x));
        Assert.Equal(new[] { Joe, Sam }, hub.Progress(Kid, Bear).Select(p => p.UserId).OrderBy(x => x == Sam));
        // Watching together marks everyone else in the group.
        Assert.Equal(2, hub.MarkTogether(Kid, Ep1, group).Count);
        // Someone outside it can't invite into it.
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.Request(Hidden, Joe, null, Guid.Empty, string.Empty, group)).Error);
        Assert.Throws<PartnerException>(() => hub.Request(Joe, Kid, null, Guid.Empty, string.Empty, group));
    }

    [Fact]
    public void LeavingEndsAPairAndItsInvites()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var group = Pair(hub, Joe, Sam);
        hub.Request(Sam, Kid, null, Guid.Empty, string.Empty, group);
        hub.Leave(Sam, group);
        Assert.Empty(hub.Groups(Joe));
        Assert.Empty(hub.Incoming(Kid));
        Assert.Empty(hub.Progress(Joe, Bear));
        Assert.Empty(hub.MarkTogether(Joe, Ep1, group));
    }

    [Fact]
    public void KeptBetweenRestarts()
    {
        var hub = Hub();
        hub.SetPrivate(Kid, true);
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        Pair(hub, Joe, Sam);
        hub.Request(Joe, null, "kid", Bear, "The Bear");
        var again = Hub();
        Assert.True(again.IsPrivate(Kid));
        Assert.Single(again.Groups(Sam));
        Assert.Single(again.Incoming(Kid));
    }
}
