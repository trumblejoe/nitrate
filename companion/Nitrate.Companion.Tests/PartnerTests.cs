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
    private static readonly Guid Ep3 = Guid.NewGuid();
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

        public Dictionary<(Guid User, Guid Item), DateTimeOffset> At { get; } = new();

        public ShowProgress Progress(Guid userId, Guid seriesId)
        {
            var order = new[] { Ep1, Ep2, Ep3 };
            var watched = order.Where(e => Played.Contains((userId, e))).ToList();
            var furthest = watched.LastOrDefault();
            return new ShowProgress(
                watched, watched.Count == 0 ? null : $"S1 E{watched.Count}", watched.Count < 3 ? order[watched.Count] : null,
                order, watched.Count == 0 ? null : At.GetValueOrDefault((userId, furthest)));
        }

        public bool Belongs(Guid titleId, Guid itemId, Guid userId) => titleId == Bear && (itemId == Ep1 || itemId == Ep2 || itemId == Ep3);

        public void MarkWatched(Guid userId, Guid itemId) => Played.Add((userId, itemId));

        public void Watched(Guid userId, Guid itemId, DateTimeOffset at)
        {
            Played.Add((userId, itemId));
            At[(userId, itemId)] = at;
        }
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
    public void NotesGoToTheGroupsWhoseTitleHoldsTheItem()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var withSam = Pair(hub, Joe, Sam);
        // An episode of the group's show, or the show itself: the group with everyone in it.
        var holding = Assert.Single(hub.GroupsHolding(Joe, Ep2));
        Assert.Equal(withSam, holding.Id);
        Assert.Equal(new[] { Joe, Sam }, holding.Members.OrderBy(m => m == Sam));
        Assert.Single(hub.GroupsHolding(Sam, Bear));
        // Something else, or someone outside the group: nobody to write to.
        Assert.Empty(hub.GroupsHolding(Joe, Guid.NewGuid()));
        Assert.Empty(hub.GroupsHolding(Kid, Ep2));
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

    // ---- Schedules ----

    private static GroupSchedule Weekly(bool soft = false) => new() { Cadence = GroupSchedule.Weekly, Day = 4, Hour = 19, TimeZone = "UTC", SoftLock = soft };

    [Fact]
    public void ReleaseTimes()
    {
        // 2026-11-15 is a Sunday.
        var sunday = DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(DateTimeOffset.Parse("2026-11-19T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture), ScheduleRules.NextRelease(Weekly(), sunday));
        // Every 2 days counts from the day it was set, at the time chosen.
        var every2 = new GroupSchedule { Cadence = GroupSchedule.Days2, Hour = 19, TimeZone = "UTC", Since = sunday };
        Assert.Equal(DateTimeOffset.Parse("2026-11-17T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture), ScheduleRules.NextRelease(every2, sunday));
        Assert.Equal(DateTimeOffset.Parse("2026-11-17T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture), ScheduleRules.NextRelease(every2, sunday.AddHours(-2).AddDays(1)));
        // Majority: both of two, two of three.
        Assert.False(ScheduleRules.Passes(1, 2));
        Assert.True(ScheduleRules.Passes(2, 2));
        Assert.True(ScheduleRules.Passes(2, 3));
        Assert.True(ScheduleRules.Fails(1, 2));
        Assert.True(ScheduleRules.Fails(2, 3));
        Assert.False(ScheduleRules.Fails(1, 3));
    }

    [Fact]
    public void OneNewPartOpensAtATime()
    {
        var since = _time.GetUtcNow();
        var s = Weekly();
        s.Since = since;
        var order = new[] { Ep1, Ep2, Ep3 };
        // Set after the group last watched: the next one is open straight away, the one after waits.
        Assert.Equal(new ScheduleLock(Ep3, null), ScheduleRules.Of(s, order, 0, since.AddDays(-3), since));
        // Someone watched it after: the next waits for Thursday 7pm.
        var thursday = DateTimeOffset.Parse("2026-11-19T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(new ScheduleLock(Ep3, thursday), ScheduleRules.Of(s, order, 1, since.AddHours(1), since.AddHours(2)));
        // Thursday: it's open.
        Assert.Equal(new ScheduleLock(null, null), ScheduleRules.Of(s, order, 1, since.AddHours(1), thursday));
        // Binge locks nothing.
        Assert.Equal(new ScheduleLock(null, null), ScheduleRules.Of(new GroupSchedule(), order, 0, since, since));
    }

    [Fact]
    public void TheInviterChoosesTheScheduleAndAcceptingAgreesToIt()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: Weekly());
        var invite = hub.Incoming(Sam).Single();
        Assert.Equal(GroupSchedule.Weekly, invite.Schedule?.Cadence);
        hub.Accept(Sam, invite.Id);
        _watch.Watched(Joe, Ep1, _time.GetUtcNow().AddHours(1));
        _time.Advance(TimeSpan.FromHours(2));
        var g = hub.Groups(Sam).Single();
        Assert.Equal(GroupSchedule.Weekly, g.Schedule?.Cadence);
        // Joe watched episode 1 after the group started: episode 2 waits for Thursday (Sam can still watch 1).
        Assert.Equal(Ep2, g.LockedFromId);
        Assert.Equal(DayOfWeek.Thursday, g.OpensAt?.DayOfWeek);
    }

    [Fact]
    public void ChangesNeedMostOfTheGroup()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: Weekly());
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        var gid = hub.Groups(Joe).Single().Id;
        // In a pair both must agree: Joe asking isn't enough.
        hub.ProposeSchedule(Joe, gid, new GroupSchedule { Cadence = GroupSchedule.Binge });
        var waiting = hub.Groups(Sam).Single().Pending!;
        Assert.Equal("joe", waiting.FromName);
        Assert.False(waiting.Voted);
        Assert.Equal(GroupSchedule.Weekly, hub.Groups(Joe).Single().Schedule?.Cadence);
        // Sam can't ask for something else meanwhile.
        Assert.Throws<PartnerException>(() => hub.ProposeSchedule(Sam, gid, Weekly(soft: true)));
        hub.Vote(Sam, gid, waiting.Id, yes: true);
        Assert.Null(hub.Groups(Joe).Single().Schedule);
        Assert.Null(hub.Groups(Joe).Single().Pending);

        // A soft lock is a change like any other; a no ends it.
        hub.ProposeSchedule(Sam, gid, Weekly(soft: true));
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: false);
        Assert.Null(hub.Groups(Joe).Single().Pending);
        Assert.Null(hub.Groups(Joe).Single().Schedule);

        // Unanswered, it lapses after a week.
        hub.ProposeSchedule(Sam, gid, Weekly());
        _time.Advance(TimeSpan.FromDays(8));
        Assert.Null(hub.Groups(Joe).Single().Pending);
    }

    [Fact]
    public void InThreeTwoIsEnough()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        var gid = hub.Groups(Joe).Single().Id;
        hub.Request(Joe, Kid, null, Bear, "The Bear", gid);
        hub.Accept(Kid, hub.Incoming(Kid).Single().Id);
        hub.ProposeSchedule(Kid, gid, Weekly());
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        Assert.Equal(GroupSchedule.Weekly, hub.Groups(Joe).Single().Schedule?.Cadence);
    }

    // ---- Together only ----

    [Fact]
    public void TogetherOnlyMarksWhatNobodyHasSeen()
    {
        var since = _time.GetUtcNow();
        var order = new[] { Ep1, Ep2, Ep3 };
        // Binge, together only: nothing is rationed, but what nobody has seen is for watching together.
        var binge = new GroupSchedule { Together = true, Since = since };
        Assert.Equal(new ScheduleLock(null, null, Ep2), ScheduleRules.Of(binge, order, 0, since.AddHours(1), since.AddHours(2)));
        Assert.Equal(new ScheduleLock(null, null, Ep1), ScheduleRules.Of(binge, order, -1, null, since));
        // Everyone's seen everything: nothing held back.
        Assert.Equal(new ScheduleLock(null, null), ScheduleRules.Of(binge, order, 2, since.AddHours(1), since.AddHours(2)));
        // Weekly and together: the next waits for Thursday, and then is for watching together.
        var weekly = Weekly();
        weekly.Together = true;
        weekly.Since = since;
        var thursday = DateTimeOffset.Parse("2026-11-19T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(new ScheduleLock(Ep3, thursday, Ep3), ScheduleRules.Of(weekly, order, 1, since.AddHours(1), since.AddHours(2)));
        Assert.Equal(new ScheduleLock(null, null, Ep3), ScheduleRules.Of(weekly, order, 1, since.AddHours(1), thursday));
        // Separately (the default) marks nothing for watching together.
        Assert.Null(ScheduleRules.Of(Weekly(), order, 1, since.AddHours(1), thursday).TogetherFromId);
    }

    [Fact]
    public void SchedulesStoredBeforeItReadAsSeparately()
    {
        var old = System.Text.Json.JsonSerializer.Deserialize<GroupSchedule>("{\"Cadence\":\"weekly\",\"Day\":4,\"Hour\":19,\"Minute\":0,\"SoftLock\":false}")!;
        Assert.False(old.Together);
        Assert.True(old.Active);
        var kept = System.Text.Json.JsonSerializer.Deserialize<GroupSchedule>(System.Text.Json.JsonSerializer.Serialize(new GroupSchedule { Together = true }))!;
        Assert.True(kept.Together);
        Assert.False(kept.Limits);
        Assert.True(kept.Active);
    }

    [Fact]
    public void TheInviterCanAskToWatchTogetherOnly()
    {
        var hub = Hub();
        // Binge but together only is kept (a plain binge invite carries no schedule).
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: new GroupSchedule { Together = true });
        var invite = hub.Incoming(Sam).Single();
        Assert.True(invite.Schedule?.Together);
        hub.Accept(Sam, invite.Id);
        _watch.Watched(Joe, Ep1, _time.GetUtcNow().AddHours(1));
        _time.Advance(TimeSpan.FromHours(2));
        var g = hub.Groups(Sam).Single();
        Assert.True(g.Schedule?.Together);
        Assert.True(g.Record);
        Assert.Null(g.LockedFromId);
        // Joe watching episode 1 on his own doesn't count for a group that watches together only.
        Assert.Equal(Ep1, g.TogetherFromId);
        // Watched together (a live session with both), it does: episode 2 is the next nobody has seen.
        hub.RecordLive(Ep1, new[] { Joe, Sam });
        Assert.Equal(Ep2, hub.Groups(Sam).Single().TogetherFromId);
        Assert.Equal(Ep2, hub.Groups(Joe).Single().TogetherFromId);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        // Kept between restarts.
        Assert.True(Hub().Groups(Joe).Single().Schedule?.Together);
        Assert.Equal(Ep2, Hub().Groups(Joe).Single().TogetherFromId);
    }

    // ---- A together-only group's own watched record ----

    /// <summary>Joe, Sam and Kid in one together-only group on The Bear.</summary>
    private (PartnerHub Hub, Guid Group) TogetherTrio(GroupSchedule? schedule = null)
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: schedule ?? new GroupSchedule { Together = true });
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        var gid = hub.Groups(Joe).Single().Id;
        hub.Request(Joe, Kid, null, Bear, "The Bear", gid);
        hub.Accept(Kid, hub.Incoming(Kid).Single().Id);
        return (hub, gid);
    }

    [Fact]
    public void ALiveSessionCountsForTheMembersWhoWereThere()
    {
        var (hub, gid) = TogetherTrio();
        // Joe and Sam watch episode 1 together; Kid wasn't there (and a stranger in the session counts for nothing).
        var stranger = Guid.NewGuid();
        Assert.Equal(new[] { gid }, hub.RecordLive(Ep1, new[] { Joe, Sam, stranger }));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Sam).Single().Mine);
        Assert.Empty(hub.Groups(Kid).Single().Mine!);
        // Kid simply falls behind: the others are a part ahead of Kid, and nothing asks.
        var kidSees = hub.Progress(Kid, Bear);
        Assert.All(kidSees, p => Assert.Equal(new[] { Ep1 }, p.Watched));
        Assert.Equal(Ep2, hub.Groups(Kid).Single().TogetherFromId);
        // One member alone in a session isn't watching with the group.
        Assert.Empty(hub.RecordLive(Ep2, new[] { Joe, stranger }));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        // Something outside the group's title counts for nothing.
        Assert.Empty(hub.RecordLive(Guid.NewGuid(), new[] { Joe, Sam }));
        // Kid catching up alone on what the group watched together counts; going further alone doesn't.
        Assert.Equal(new[] { gid }, hub.RecordCatchUp(Kid, Ep1));
        Assert.Empty(hub.RecordCatchUp(Kid, Ep2));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
    }

    [Fact]
    public void SeparatelyGoesByJellyfinAndTogetherOnlyByItsRecord()
    {
        var hub = Hub();
        // Joe watches The Bear with Sam, together only, and with Kid, separately.
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: new GroupSchedule { Together = true });
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        hub.Request(Joe, Kid, null, Bear, "The Bear");
        hub.Accept(Kid, hub.Incoming(Kid).Single().Id);
        var together = hub.Groups(Joe).Single(g => g.Others.Any(o => o.Id == Sam));
        var apart = hub.Groups(Joe).Single(g => g.Others.Any(o => o.Id == Kid));
        // Episode 1 with Sam (live), then episodes 2 and 3 with Kid, from separate homes.
        hub.RecordLive(Ep1, new[] { Joe, Sam });
        _watch.Watched(Joe, Ep1, _time.GetUtcNow());
        _watch.Watched(Sam, Ep1, _time.GetUtcNow());
        _watch.Watched(Joe, Ep2, _time.GetUtcNow());
        _watch.Watched(Joe, Ep3, _time.GetUtcNow());
        _watch.Watched(Kid, Ep2, _time.GetUtcNow());
        _watch.Watched(Kid, Ep3, _time.GetUtcNow());
        // Sam sees Joe where they are together: episode 1, not 3.
        var joeForSam = hub.Progress(Sam, Bear).Single(p => p.UserId == Joe);
        Assert.Equal(new[] { Ep1 }, joeForSam.Watched);
        Assert.Equal(Ep2, joeForSam.NextId);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single(g => g.Id == together.Id).Mine);
        Assert.Equal(Ep2, hub.Groups(Sam).Single().TogetherFromId);
        // Kid sees Joe by Jellyfin's watched state: all three.
        Assert.Equal(new[] { Ep1, Ep2, Ep3 }, hub.Progress(Kid, Bear).Single(p => p.UserId == Joe).Watched);
        Assert.False(apart.Record);
        Assert.Null(hub.Groups(Joe).Single(g => g.Id == apart.Id).Mine);
        // Joe sees each partner per group.
        var mine = hub.Progress(Joe, Bear);
        Assert.Equal(new[] { Ep1 }, mine.Single(p => p.UserId == Sam).Watched);
        Assert.Equal(new[] { Ep2, Ep3 }, mine.Single(p => p.UserId == Kid).Watched);
    }

    [Fact]
    public void ScheduleLocksGoByTheGroupsRecord()
    {
        var weekly = Weekly();
        weekly.Together = true;
        var (hub, gid) = TogetherTrio(weekly);
        // Everyone watched episode 1 on their own: nothing counts for the group, so episode 1 is the next and open.
        _watch.Watched(Joe, Ep1, _time.GetUtcNow());
        _watch.Watched(Sam, Ep1, _time.GetUtcNow());
        var g = hub.Groups(Joe).Single();
        Assert.Equal(Ep2, g.LockedFromId);
        Assert.Null(g.OpensAt);
        Assert.Equal(Ep1, g.TogetherFromId);
        // Watched together an hour later: episode 2 waits for Thursday 7pm (by when it was watched with the group).
        _time.Advance(TimeSpan.FromHours(1));
        hub.RecordLive(Ep1, new[] { Joe, Sam });
        _time.Advance(TimeSpan.FromHours(1));
        g = hub.Groups(Kid).Single();
        Assert.Equal(Ep2, g.LockedFromId);
        Assert.Equal(DateTimeOffset.Parse("2026-11-19T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture), g.OpensAt);
        Assert.Equal(Ep2, g.TogetherFromId);
    }

    [Fact]
    public void SwitchingToTogetherOnlyStartsFromWhatEveryoneHasWatched()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: Weekly());
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        var gid = hub.Groups(Joe).Single().Id;
        _watch.Watched(Joe, Ep1, _time.GetUtcNow());
        _watch.Watched(Joe, Ep2, _time.GetUtcNow());
        _watch.Watched(Sam, Ep1, _time.GetUtcNow());
        Assert.False(hub.Groups(Joe).Single().Record);
        _time.Advance(TimeSpan.FromHours(1));
        hub.ProposeSchedule(Joe, gid, new GroupSchedule { Together = true });
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        // Only episode 1 is everyone's: the record starts there, and episode 2 is for watching together.
        var g = hub.Groups(Joe).Single();
        Assert.True(g.Record);
        Assert.Equal(new[] { Ep1 }, g.Mine);
        Assert.Equal(Ep2, g.TogetherFromId);
        Assert.Equal(new[] { Ep1 }, hub.Progress(Sam, Bear).Single().Watched);
        // What anyone watches on their own after the switch doesn't move it.
        _watch.Watched(Sam, Ep2, _time.GetUtcNow());
        Assert.Equal(Ep2, hub.Groups(Joe).Single().TogetherFromId);
        // Back to separately: Jellyfin's watched state again, and no record kept.
        hub.ProposeSchedule(Joe, gid, Weekly());
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        Assert.False(hub.Groups(Joe).Single().Record);
        Assert.Null(hub.Groups(Joe).Single().Mine);
        Assert.Empty(_storage.State.Groups.Single().Watched);
    }

    [Fact]
    public void TheSameRoomCountsOnlyMembersWhoWereThere()
    {
        var (hub, gid) = TogetherTrio();
        // Someone outside the group can't be counted, and nobody outside it can count anything.
        var e = Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, new[] { Ep1 }, new[] { Sam, Hidden }));
        Assert.Equal(PartnerError.Invalid, e.Error);
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.WatchedWith(Hidden, gid, new[] { Ep1 }, new[] { Joe })).Error);
        // Only parts of the group's title, and something must be chosen.
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, new[] { Guid.NewGuid() }, new[] { Sam }));
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, Array.Empty<Guid>(), new[] { Sam }));
        // Alone, only catching up on what the group already watched counts.
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, new[] { Ep1 }, Array.Empty<Guid>()));
        Assert.Empty(hub.Groups(Joe).Single().Mine!);
        // Joe and Sam in the same room: counted for both, marked watched for Sam, Kid not.
        Assert.Equal(new[] { Joe, Sam }.OrderBy(x => x), hub.WatchedWith(Joe, gid, new[] { Ep1, Ep2 }, new[] { Sam }).OrderBy(x => x));
        Assert.Equal(new[] { Ep1, Ep2 }, hub.Groups(Sam).Single().Mine);
        Assert.Contains((Sam, Ep2), _watch.Played);
        Assert.Empty(hub.Groups(Kid).Single().Mine!);
        Assert.Equal(Ep3, hub.Groups(Joe).Single().TogetherFromId);
        // Now Kid can count episode 1 alone, as catching up.
        Assert.Equal(new[] { Kid }, hub.WatchedWith(Kid, gid, new[] { Ep1 }, Array.Empty<Guid>()));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
        // A group that watches separately has no record: alone there's nothing to count.
        hub.Request(Sam, Kid, null, Bear, "The Bear");
        hub.Accept(Kid, hub.Incoming(Kid).Single().Id);
        var apart = hub.Groups(Sam).Single(g => !g.Record).Id;
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Sam, apart, new[] { Ep3 }, Array.Empty<Guid>()));
        Assert.Equal(2, hub.WatchedWith(Sam, apart, new[] { Ep3 }, new[] { Kid }).Count);
        Assert.Contains((Kid, Ep3), _watch.Played);
    }

    [Fact]
    public void WatchedWithMeCountsInTheRecord()
    {
        var (hub, gid) = TogetherTrio();
        hub.MarkTogether(Joe, Ep1, gid);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
        Assert.Equal(Ep2, hub.Groups(Sam).Single().TogetherFromId);
    }

    [Fact]
    public void LeavingTakesTheLeaversRecordAlong()
    {
        var (hub, gid) = TogetherTrio();
        hub.RecordLive(Ep1, new[] { Joe, Sam, Kid });
        hub.Leave(Kid, gid);
        Assert.DoesNotContain(_storage.State.Groups.Single().Watched, w => w.Member == Kid);
        Assert.Equal(2, _storage.State.Groups.Single().Watched.Count);
    }

    [Fact]
    public void HowWeWatchIsChangedByVoteLikeTheRest()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: Weekly());
        hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        var gid = hub.Groups(Joe).Single().Id;
        var together = Weekly();
        together.Together = true;
        hub.ProposeSchedule(Joe, gid, together);
        // Joe alone isn't a majority of two.
        Assert.False(hub.Groups(Joe).Single().Schedule?.Together);
        Assert.True(hub.Groups(Sam).Single().Pending?.Proposed.Together);
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        Assert.True(hub.Groups(Joe).Single().Schedule?.Together);
        // Binge but still together only keeps the schedule.
        hub.ProposeSchedule(Sam, gid, new GroupSchedule { Together = true });
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: true);
        var s = hub.Groups(Joe).Single().Schedule;
        Assert.Equal(GroupSchedule.Binge, s?.Cadence);
        Assert.True(s?.Together);
        // Back to separately, binge: no schedule at all.
        hub.ProposeSchedule(Sam, gid, new GroupSchedule());
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: true);
        Assert.Null(hub.Groups(Joe).Single().Schedule);
    }
}
