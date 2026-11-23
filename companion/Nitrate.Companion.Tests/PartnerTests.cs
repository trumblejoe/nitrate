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
    private static readonly Guid Sev = Guid.NewGuid();
    private static readonly Guid Sv1 = Guid.NewGuid();
    private static readonly Guid Sv2 = Guid.NewGuid();
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

        /// <summary>The Bear (three episodes) and Severance (two); anything else isn't in the library.</summary>
        public Dictionary<Guid, Guid[]> Titles { get; } = new() { [Bear] = new[] { Ep1, Ep2, Ep3 }, [Sev] = new[] { Sv1, Sv2 } };

        public ShowProgress Progress(Guid userId, Guid seriesId)
        {
            var order = Titles.GetValueOrDefault(seriesId) ?? Array.Empty<Guid>();
            var watched = order.Where(e => Played.Contains((userId, e))).ToList();
            var furthest = watched.LastOrDefault();
            return new ShowProgress(
                watched, watched.Count == 0 ? null : $"S1 E{watched.Count}", order.FirstOrDefault(e => !watched.Contains(e)) is { } n && n != Guid.Empty ? n : null,
                order, watched.Count == 0 ? null : At.GetValueOrDefault((userId, furthest)));
        }

        public bool Belongs(Guid titleId, Guid itemId, Guid userId) => Titles.TryGetValue(titleId, out var order) && order.Contains(itemId);

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
        // Watching with Sam's group counts in that group's record only, and changes nobody's own watched state.
        Assert.Equal(new[] { Sam }, hub.MarkTogether(Joe, Ep1, withSam));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Sam).Single().Mine);
        Assert.Empty(hub.Groups(Kid).Single().Mine!);
        Assert.Empty(hub.Groups(Joe).Single(g => g.Id == withKid).Mine!);
        Assert.Empty(_watch.Played);
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
        var gid = hub.Accept(Sam, invite.Id).Id;
        _time.Advance(TimeSpan.FromHours(1));
        hub.Mark(Joe, new[] { gid }, new[] { Ep1 });
        _time.Advance(TimeSpan.FromHours(1));
        var g = hub.Groups(Sam).Single();
        Assert.Equal(GroupSchedule.Weekly, g.Schedule?.Cadence);
        // Joe watched episode 1 with the group after it started: episode 2 waits for Thursday (Sam can still watch 1).
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

    // ---- Together (stored as "together only") ----

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
        Assert.False(g.Schedule?.SoftLock);
        Assert.True(g.Record);
        Assert.Null(g.LockedFromId);
        // Joe watching episode 1 on his own doesn't count for the group.
        Assert.Equal(Ep1, g.TogetherFromId);
        // Watched together (a live session with both, for whom it was next), it does: episode 2 is the next nobody has seen.
        hub.RecordLive(Ep1, new[] { Joe, Sam });
        Assert.Equal(Ep2, hub.Groups(Sam).Single().TogetherFromId);
        Assert.Equal(Ep2, hub.Groups(Joe).Single().TogetherFromId);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        // Kept between restarts.
        Assert.True(Hub().Groups(Joe).Single().Schedule?.Together);
        Assert.Equal(Ep2, Hub().Groups(Joe).Single().TogetherFromId);
    }

    // ---- Every group's own watched record ----

    /// <summary>Joe, Sam and Kid in one group on The Bear (Together on unless another schedule is given).</summary>
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
        // Joe and Sam watch episode 1 in a session started for the group; Kid wasn't there, and a stranger in it counts for nothing.
        var stranger = Guid.NewGuid();
        hub.SessionFor(Joe, gid, "0f8fad5b-d9cb-469f-a165-70867728950e");
        Assert.Equal(new[] { gid }, hub.RecordLive(Ep1, new[] { Joe, Sam, stranger }, "0f8fad5bd9cb469fa16570867728950e"));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Joe).Single().Mine);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Sam).Single().Mine);
        Assert.Empty(hub.Groups(Kid).Single().Mine!);
        // Kid simply falls behind: the others are a part ahead of Kid, and nothing asks.
        Assert.All(hub.Progress(Kid, Bear), p => Assert.Equal(new[] { Ep1 }, p.Watched));
        Assert.Equal(Ep2, hub.Groups(Kid).Single().TogetherFromId);
        // A session nobody said was for the group, with a stranger in it: it can't tell, and counts for nobody.
        Assert.Empty(hub.RecordLive(Ep2, new[] { Joe, Sam, stranger }, "other"));
        // Something outside the group's titles counts for nothing.
        Assert.Empty(hub.RecordLive(Guid.NewGuid(), new[] { Joe, Sam }, "0f8fad5b-d9cb-469f-a165-70867728950e"));
        // Only members say what a session is for.
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.SessionFor(Hidden, gid, "mine")).Error);
        // Kid catching up counts once Kid marks it for the group (playing from the group's card).
        hub.Mark(Kid, new[] { gid }, new[] { Ep1 });
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
    }

    [Fact]
    public void ASessionNobodyNamedCountsWhereItIsEveryonesNext()
    {
        var hub = Hub();
        // Joe and Sam share two groups on The Bear: in one they're at episode 2, in the other at episode 1.
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var early = Pair(hub, Joe, Sam);
        hub.Request(Sam, Joe, null, Bear, "The Bear");
        var later = Pair(hub, Sam, Joe);
        hub.Mark(Joe, new[] { later }, new[] { Ep1 });
        hub.Mark(Sam, new[] { later }, new[] { Ep1 });
        // Episode 1 in a session nobody said was for a group: only where it's the next part for both.
        Assert.Equal(new[] { early }, hub.RecordLive(Ep1, new[] { Joe, Sam }, "s"));
        // Episode 2 is next for both in each group now.
        Assert.Equal(new[] { early, later }.OrderBy(x => x), hub.RecordLive(Ep2, new[] { Joe, Sam }, "s").OrderBy(x => x));
        // One person alone in a session nobody named counts for nothing.
        Assert.Empty(hub.RecordLive(Ep3, new[] { Joe }, "s"));
        // A Group Watch Session's own session counts for its group.
        hub.ProposeSchedule(Joe, early, new GroupSchedule { TimeZone = "UTC", Party = new PartyTime { Days = new() { 0, 1, 2, 3, 4, 5, 6 }, Hour = 21 } });
        hub.Vote(Sam, early, hub.Groups(Sam).Single(g => g.Id == early).Pending!.Id, yes: true);
        _time.SetUtcNow(DateTimeOffset.Parse("2026-11-15T20:55:00Z", System.Globalization.CultureInfo.InvariantCulture));
        Assert.True(hub.JoinParty(Joe, early).Start);
        hub.PartySession(Joe, early, "night");
        Assert.Equal(new[] { early }, hub.RecordLive(Ep3, new[] { Joe }, "night"));
        Assert.Contains(Ep3, hub.Groups(Joe).Single(g => g.Id == early).Mine!);
        Assert.DoesNotContain(Ep3, hub.Groups(Joe).Single(g => g.Id == later).Mine!);
    }

    [Fact]
    public void EveryGroupGoesByItsOwnRecord()
    {
        var hub = Hub();
        // Joe watches The Bear with Sam (Together on) and with Kid (Together off).
        hub.Request(Joe, Sam, null, Bear, "The Bear", schedule: new GroupSchedule { Together = true });
        var withSam = Pair(hub, Joe, Sam);
        hub.Request(Joe, Kid, null, Bear, "The Bear");
        var withKid = Pair(hub, Joe, Kid);
        // Episode 1 with Sam, then episodes 2 and 3 with Kid; and Joe watches all three on his own too.
        hub.Mark(Joe, new[] { withSam }, new[] { Ep1 });
        hub.Mark(Sam, new[] { withSam }, new[] { Ep1 });
        hub.Mark(Joe, new[] { withKid }, new[] { Ep1, Ep2, Ep3 });
        hub.Mark(Kid, new[] { withKid }, new[] { Ep1, Ep2, Ep3 });
        _watch.Watched(Joe, Ep1, _time.GetUtcNow());
        // Sam sees Joe where they are together: episode 1, not 3.
        var joeForSam = hub.Progress(Sam, Bear).Single(p => p.UserId == Joe);
        Assert.Equal(new[] { Ep1 }, joeForSam.Watched);
        Assert.Equal(Ep2, joeForSam.NextId);
        Assert.Equal(Ep2, hub.Groups(Sam).Single().TogetherFromId);
        // Kid sees Joe by their group's record: all three.
        Assert.Equal(new[] { Ep1, Ep2, Ep3 }, hub.Progress(Kid, Bear).Single(p => p.UserId == Joe).Watched);
        Assert.All(hub.Groups(Joe), g => Assert.True(g.Record));
        // Joe sees each partner per group.
        var mine = hub.Progress(Joe, Bear);
        Assert.Equal(new[] { Ep1 }, mine.Single(p => p.UserId == Sam).Watched);
        Assert.Equal(new[] { Ep1, Ep2, Ep3 }, mine.Single(p => p.UserId == Kid).Watched);
    }

    [Fact]
    public void MarksArePerGroupAndPerPerson()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Bear, "The Bear");
        var withSam = Pair(hub, Joe, Sam);
        hub.Request(Joe, Kid, null, Bear, "The Bear");
        var withKid = Pair(hub, Joe, Kid);
        GroupView G(Guid user, Guid id) => hub.Groups(user).Single(g => g.Id == id);
        // One group: Joe's place there only; Sam's, the other group's and Joe's own watched state stay as they were.
        Assert.Equal(new[] { withSam }, hub.Mark(Joe, new[] { withSam }, new[] { Ep1 }));
        Assert.Equal(new[] { Ep1 }, G(Joe, withSam).Mine);
        Assert.Empty(G(Joe, withKid).Mine!);
        Assert.Empty(G(Sam, withSam).Mine!);
        Assert.Empty(_watch.Played);
        // Several groups at once ("All groups where it's next").
        hub.Mark(Joe, new[] { withSam, withKid }, new[] { Ep2 });
        Assert.Equal(new[] { Ep1, Ep2 }, G(Joe, withSam).Mine);
        Assert.Equal(new[] { Ep2 }, G(Joe, withKid).Mine);
        // Unwatched in one group only.
        hub.Mark(Joe, new[] { withSam }, new[] { Ep1 }, watched: false);
        Assert.Equal(new[] { Ep2 }, G(Joe, withSam).Mine);
        Assert.Equal(new[] { Ep2 }, G(Joe, withKid).Mine);
        // Only the caller's own groups, only what's in their titles; a refused mark changes nothing anywhere.
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.Mark(Sam, new[] { withSam, withKid }, new[] { Ep3 })).Error);
        Assert.Equal(PartnerError.Invalid, Assert.Throws<PartnerException>(() => hub.Mark(Joe, new[] { withSam }, new[] { Ep3, Sv1 })).Error);
        Assert.Empty(G(Sam, withSam).Mine!);
        Assert.DoesNotContain(Ep3, G(Joe, withSam).Mine!);
        Assert.Throws<PartnerException>(() => hub.Mark(Joe, new[] { withSam }, Array.Empty<Guid>()));
    }

    [Fact]
    public void AGroupWatchesSeveralTitles()
    {
        var hub = Hub();
        // An invite for two titles carries both; the invitee sees them, and the group starts with both and an empty record.
        hub.Request(Joe, Sam, null, Guid.Empty, string.Empty, schedule: Weekly(), titles: new[] { new GroupTitle { Id = Sev, Name = "Severance" }, new GroupTitle { Id = Bear, Name = "The Bear" } });
        var invite = hub.Incoming(Sam).Single();
        Assert.Equal(new[] { Sev, Bear }, invite.Titles!.Select(t => t.Id));
        Assert.Equal(Sev, invite.SeriesId);
        Assert.Equal(new[] { Sev, Bear }, hub.Outgoing(Joe).Single().Titles!.Select(t => t.Id));
        var gid = hub.Accept(Sam, invite.Id).Id;
        var g = hub.Groups(Joe).Single();
        Assert.Equal(new[] { "Severance", "The Bear" }, g.Titles!.Select(t => t.Name));
        Assert.Empty(g.Mine!);
        // Older apps read the first title.
        Assert.Equal(Sev, g.SeriesId);
        Assert.Equal(Sev, _storage.State.Groups.Single().SeriesId);
        // Someone invited later into the group is shown both titles.
        hub.Request(Sam, Kid, null, Guid.Empty, string.Empty, gid);
        Assert.Equal(new[] { Sev, Bear }, hub.Incoming(Kid).Single().Titles!.Select(t => t.Id));
        // Notes and progress go by either title.
        Assert.Single(hub.GroupsHolding(Joe, Sv1));
        Assert.Single(hub.GroupsHolding(Joe, Ep1));
        Assert.Equal("Severance & The Bear", hub.Names(Joe, gid)!.Value.Title);
        // Each title is paced by the schedule on its own: one of each a week.
        _time.Advance(TimeSpan.FromHours(1));
        hub.Mark(Joe, new[] { gid }, new[] { Sv1, Ep1 });
        _time.Advance(TimeSpan.FromHours(1));
        g = hub.Groups(Sam).Single();
        Assert.Equal(Sv2, g.Titles!.Single(t => t.Id == Sev).LockedFromId);
        Assert.Equal(Ep2, g.Titles!.Single(t => t.Id == Bear).LockedFromId);
        Assert.Equal(DayOfWeek.Thursday, g.Titles!.Single(t => t.Id == Bear).OpensAt?.DayOfWeek);
        // Any member adds or removes a title; a group keeps at least one, and outsiders can't touch it.
        var other = Guid.NewGuid();
        hub.AddTitle(Sam, gid, other, "Slow Horses");
        hub.AddTitle(Sam, gid, other, "Slow Horses");
        Assert.Equal(3, hub.Groups(Joe).Single().Titles!.Count);
        hub.RemoveTitle(Joe, gid, Sev);
        Assert.Equal(new[] { Bear, other }, hub.Groups(Sam).Single().Titles!.Select(t => t.Id));
        Assert.Equal(Bear, _storage.State.Groups.Single().SeriesId);
        hub.RemoveTitle(Joe, gid, other);
        Assert.Equal(PartnerError.Invalid, Assert.Throws<PartnerException>(() => hub.RemoveTitle(Joe, gid, Bear)).Error);
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.AddTitle(Kid, gid, Sev, "Severance")).Error);
        // Removing a title keeps what was watched of it: adding it back carries on.
        hub.AddTitle(Joe, gid, Sev, "Severance");
        Assert.Contains(Sv1, hub.Groups(Joe).Single().Mine!);
    }

    [Fact]
    public void TogetherMarksWhatTheGroupHasNotWatchedPerTitle()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Guid.Empty, string.Empty, schedule: new GroupSchedule { Together = true }, titles: new[] { new GroupTitle { Id = Bear, Name = "The Bear" }, new GroupTitle { Id = Sev, Name = "Severance" } });
        var gid = Pair(hub, Joe, Sam);
        // Sam is ahead in The Bear with the group: Joe catching up is free, episode 3 is the first the group hasn't watched.
        hub.Mark(Sam, new[] { gid }, new[] { Ep1, Ep2 });
        var g = hub.Groups(Joe).Single();
        Assert.Equal(Ep3, g.Titles!.Single(t => t.Id == Bear).TogetherFromId);
        Assert.Equal(Sv1, g.Titles!.Single(t => t.Id == Sev).TogetherFromId);
        Assert.Null(g.Titles!.Single(t => t.Id == Bear).LockedFromId);
        // Together off: nothing is marked for watching together.
        hub.ProposeSchedule(Joe, gid, new GroupSchedule());
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        g = hub.Groups(Joe).Single();
        Assert.Null(g.Schedule);
        Assert.All(g.Titles!, t => Assert.Null(t.TogetherFromId));
        // The record carries on through the switch.
        Assert.Equal(new[] { Ep1, Ep2 }, hub.Progress(Joe, Bear).Single().Watched);
    }

    [Fact]
    public void OldGroupsMoveToTitlesAndSeedTheirRecords()
    {
        // Stored before titles and records for every group: one Separately group (Joe and Sam) and one together-only (Joe and Kid).
        var apart = Guid.NewGuid();
        var together = Guid.NewGuid();
        var when = DateTimeOffset.Parse("2026-11-01T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        _storage.State = System.Text.Json.JsonSerializer.Deserialize<PartnerState>($$"""
            {"Groups":[
              {"Id":"{{apart}}","SeriesId":"{{Bear}}","SeriesName":"The Bear","Members":["{{Joe}}","{{Sam}}"],"Schedule":{"Cadence":"weekly","Day":4,"Hour":19,"TimeZone":"UTC","SoftLock":true,"Since":"2026-10-01T00:00:00Z"},"Watched":[]},
              {"Id":"{{together}}","SeriesId":"{{Bear}}","SeriesName":"The Bear","Members":["{{Joe}}","{{Kid}}"],"Schedule":{"Cadence":"binge","Together":true},"Watched":[{"Member":"{{Joe}}","ItemId":"{{Ep1}}"},{"Member":"{{Kid}}","ItemId":"{{Ep1}}"}]}
            ],"Requests":[{"Id":"{{Guid.NewGuid()}}","From":"{{Sam}}","To":"{{Kid}}","ToLabel":"kid","SeriesId":"{{Bear}}","SeriesName":"The Bear","At":"2026-11-15T19:00:00Z"}]}
            """)!;
        _watch.Watched(Joe, Ep1, when);
        _watch.Watched(Joe, Ep2, when);
        _watch.Watched(Sam, Ep1, when);
        _watch.Watched(Kid, Ep2, when);
        var hub = Hub();
        // Titles: the one title, as a list; invites too.
        Assert.Equal(new[] { Bear }, hub.Groups(Joe).Single(g => g.Id == apart).Titles!.Select(t => t.Id));
        Assert.Equal(new[] { Bear }, hub.Incoming(Kid).Single().Titles!.Select(t => t.Id));
        // The Separately group's record: each member's own watched parts at migration.
        Assert.Equal(new[] { Ep1, Ep2 }, hub.Groups(Joe).Single(g => g.Id == apart).Mine);
        Assert.Equal(new[] { Ep1 }, hub.Groups(Sam).Single().Mine);
        // The together-only group keeps its record (Kid's own episode 2 doesn't come in).
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
        // The schedule carries on from when Joe played episode 2 (1 November): episode 3 opened that Thursday, and nothing comes after it.
        Assert.Null(hub.Groups(Joe).Single(g => g.Id == apart).LockedFromId);
        // Seeded once: what anyone watches on their own afterwards doesn't move the group.
        _watch.Watched(Sam, Ep2, _time.GetUtcNow());
        Assert.Equal(new[] { Ep1 }, hub.Groups(Sam).Single().Mine);
        var stored = _storage.State.Groups.Single(g => g.Id == apart);
        Assert.True(stored.RecordKept);
        Assert.Equal(Bear, stored.Titles.Single().Id);
        Assert.Equal(Bear, stored.SeriesId);
    }

    [Fact]
    public void AGroupWhoseTitleIsntInTheLibraryYetIsSeededLater()
    {
        var gid = Guid.NewGuid();
        var missing = Guid.NewGuid();
        _storage.State = System.Text.Json.JsonSerializer.Deserialize<PartnerState>($$"""
            {"Groups":[{"Id":"{{gid}}","SeriesId":"{{missing}}","SeriesName":"Later","Members":["{{Joe}}","{{Sam}}"],"Watched":[]}]}
            """)!;
        var hub = Hub();
        hub.Groups(Joe);
        Assert.False(_storage.State.Groups.Single().RecordKept);
        _watch.Titles[missing] = new[] { Ep1 };
        _watch.Watched(Sam, Ep1, _time.GetUtcNow());
        Assert.Empty(hub.Groups(Joe).Single().Mine!);
        Assert.True(_storage.State.Groups.Single().RecordKept);
        Assert.Equal(new[] { Ep1 }, hub.Progress(Joe, missing).Single().Watched);
    }

    [Fact]
    public void ScheduleLocksGoByTheGroupsRecord()
    {
        var weekly = Weekly();
        weekly.Together = true;
        var (hub, gid) = TogetherTrio(weekly);
        // Everyone watched episode 1 on their own: nothing counts for the group, so episode 1 is the next and out.
        _watch.Watched(Joe, Ep1, _time.GetUtcNow());
        _watch.Watched(Sam, Ep1, _time.GetUtcNow());
        var g = hub.Groups(Joe).Single();
        Assert.Equal(Ep2, g.LockedFromId);
        Assert.Null(g.OpensAt);
        Assert.Equal(Ep1, g.TogetherFromId);
        // Watched together an hour later: episode 2 waits for Thursday 7pm (by when it was watched with the group).
        _time.Advance(TimeSpan.FromHours(1));
        hub.RecordLive(Ep1, new[] { Joe, Sam }, "s");
        _time.Advance(TimeSpan.FromHours(1));
        g = hub.Groups(Kid).Single();
        Assert.Equal(Ep2, g.LockedFromId);
        Assert.Equal(DateTimeOffset.Parse("2026-11-19T19:00:00Z", System.Globalization.CultureInfo.InvariantCulture), g.OpensAt);
        Assert.Equal(Ep2, g.TogetherFromId);
    }

    [Fact]
    public void TheSameRoomCountsOnlyMembersWhoWereThere()
    {
        var (hub, gid) = TogetherTrio();
        // Someone outside the group can't be counted, and nobody outside it can count anything.
        var e = Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, new[] { Ep1 }, new[] { Sam, Hidden }));
        Assert.Equal(PartnerError.Invalid, e.Error);
        Assert.Equal(PartnerError.NotFound, Assert.Throws<PartnerException>(() => hub.WatchedWith(Hidden, gid, new[] { Ep1 }, new[] { Joe })).Error);
        // Only parts of the group's titles, and something must be chosen.
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, new[] { Guid.NewGuid() }, new[] { Sam }));
        Assert.Throws<PartnerException>(() => hub.WatchedWith(Joe, gid, Array.Empty<Guid>(), new[] { Sam }));
        // Joe and Sam in the same room: counted for both in the group's record, Kid not; nobody's own watched state changes.
        Assert.Equal(new[] { Joe, Sam }.OrderBy(x => x), hub.WatchedWith(Joe, gid, new[] { Ep1, Ep2 }, new[] { Sam }).OrderBy(x => x));
        Assert.Equal(new[] { Ep1, Ep2 }, hub.Groups(Sam).Single().Mine);
        Assert.Empty(hub.Groups(Kid).Single().Mine!);
        Assert.Empty(_watch.Played);
        Assert.Equal(Ep3, hub.Groups(Joe).Single().TogetherFromId);
        // Alone, it counts for the caller only.
        Assert.Equal(new[] { Kid }, hub.WatchedWith(Kid, gid, new[] { Ep1 }, Array.Empty<Guid>()));
        Assert.Equal(new[] { Ep1 }, hub.Groups(Kid).Single().Mine);
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
        hub.RecordLive(Ep1, new[] { Joe, Sam, Kid }, "s");
        hub.Leave(Kid, gid);
        Assert.DoesNotContain(_storage.State.Groups.Single().Watched, w => w.Member == Kid);
        Assert.Equal(2, _storage.State.Groups.Single().Watched.Count);
    }

    [Fact]
    public void TogetherIsChangedByVoteLikeTheRest()
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
        // Binge but Together on keeps the schedule.
        hub.ProposeSchedule(Sam, gid, new GroupSchedule { Together = true });
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: true);
        var s = hub.Groups(Joe).Single().Schedule;
        Assert.Equal(GroupSchedule.Binge, s?.Cadence);
        Assert.True(s?.Together);
        // Together off, binge: no schedule at all (the old "Ask first" alone keeps nothing).
        hub.ProposeSchedule(Sam, gid, new GroupSchedule { SoftLock = true });
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: true);
        Assert.Null(hub.Groups(Joe).Single().Schedule);
    }
}
