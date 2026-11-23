using System.Globalization;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Xunit;

namespace Nitrate.Companion.Tests;

public class PartyTests
{
    private static readonly Guid Joe = Guid.NewGuid();
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Out = Guid.NewGuid();
    private static readonly Guid Show = Guid.NewGuid();
    private static readonly Guid Ep1 = Guid.NewGuid();
    private static readonly Guid Ep2 = Guid.NewGuid();
    private static readonly Guid Ep3 = Guid.NewGuid();

    // Thursday 19 November 2026, 18:00 UTC; the party is Thursdays 20:00 UTC.
    private readonly FakeTimeProvider _time = new(At("2026-11-19T18:00:00Z"));
    private readonly Storage _storage = new();
    private readonly Watch _watch = new();

    private static DateTimeOffset At(string iso) => DateTimeOffset.Parse(iso, CultureInfo.InvariantCulture);

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
        public HashSet<(Guid User, Guid Item)> Played { get; } = new();

        public ShowProgress Progress(Guid userId, Guid titleId)
        {
            var order = new[] { Ep1, Ep2, Ep3 };
            var watched = order.Where(e => Played.Contains((userId, e))).ToList();
            return new ShowProgress(watched, null, null, order, null);
        }

        public bool Belongs(Guid titleId, Guid itemId, Guid userId) => titleId == Show;

        public string? Label(Guid itemId) => itemId == Ep1 ? "S1 E1" : itemId == Ep2 ? "S1 E2" : itemId == Ep3 ? "S1 E3" : null;
    }

    private static GroupSchedule Thursdays(int hour = 20, string cadence = GroupSchedule.Binge) =>
        new() { Cadence = cadence, TimeZone = "UTC", Party = new PartyTime { Days = new() { 4 }, Hour = hour } };

    private PartnerHub Hub() => new(_storage, new People(), _watch, _time);

    /// <summary>A group of Joe and Sam (and Kim with [three]) with a Thursday 8pm party.</summary>
    private Guid Group(PartnerHub hub, bool three = false, GroupSchedule? schedule = null)
    {
        hub.Request(Joe, Sam, null, Show, "Bear", schedule: schedule ?? Thursdays());
        var g = hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        if (three)
        {
            hub.Request(Joe, Kim, null, Show, "Bear", g.Id);
            hub.Accept(Kim, hub.Incoming(Kim).Single().Id);
        }

        return g.Id;
    }

    [Fact]
    public void PartyTimeIsKeptWithTheGroupAndChangesByMajority()
    {
        var hub = Hub();
        var gid = Group(hub, three: true);
        var view = hub.Groups(Sam).Single();
        Assert.Equal(new List<int> { 4 }, view.Schedule!.Party!.Days);
        Assert.Equal(At("2026-11-19T20:00:00Z"), view.Party!.At);

        // Kim asks for Mondays and Thursdays at 9pm: with Kim alone, 1 of 3, it waits; Joe's yes carries it.
        var change = Thursdays(21);
        change.Party!.Days = new() { 1, 4, 4, 9 };
        hub.ProposeSchedule(Kim, gid, change);
        Assert.Equal(20, hub.Groups(Joe).Single().Schedule!.Party!.Hour);
        hub.Vote(Joe, gid, hub.Groups(Joe).Single().Pending!.Id, yes: true);
        var party = hub.Groups(Joe).Single().Schedule!.Party!;
        Assert.Equal(new List<int> { 1, 4 }, party.Days);
        Assert.Equal(21, party.Hour);

        // A binge schedule with only a party time is still kept; taking the party away (binge, no party) drops it.
        hub.ProposeSchedule(Joe, gid, new GroupSchedule());
        hub.Vote(Sam, gid, hub.Groups(Sam).Single().Pending!.Id, yes: true);
        Assert.Null(hub.Groups(Joe).Single().Schedule);
        Assert.Null(hub.Groups(Joe).Single().Party);
    }

    [Fact]
    public void OldGroupsHaveNoParty()
    {
        var hub = Hub();
        hub.Request(Joe, Sam, null, Show, "Bear", schedule: new GroupSchedule { Cadence = GroupSchedule.Weekly });
        var g = hub.Accept(Sam, hub.Incoming(Sam).Single().Id);
        Assert.Null(hub.Party(Joe, g.Id));
        Assert.Null(hub.Groups(Joe).Single().Party);
        Assert.Throws<PartnerException>(() => hub.PushBack(Joe, g.Id, 15));
    }

    [Fact]
    public void OccurrencesFollowTheWeekdaysAndTheTimeZone()
    {
        var s = new GroupSchedule { TimeZone = "America/New_York", Party = new PartyTime { Days = new() { 1, 4 }, Hour = 20 } };

        // Thursday 29 October 2026, 8pm summer time (UTC−4), then Monday 2 November after the clocks went back (UTC−5).
        Assert.Equal(At("2026-10-30T00:00:00Z"), PartyRules.Next(s, At("2026-10-29T12:00:00Z")));
        Assert.Equal(At("2026-11-03T01:00:00Z"), PartyRules.Next(s, At("2026-10-30T00:00:00Z")));
        Assert.Equal(At("2026-11-06T01:00:00Z"), PartyRules.Next(s, At("2026-11-03T01:00:00Z")));

        // Tonight's party stays tonight's until 3 hours after it starts, then the next is.
        var utc = Thursdays();
        Assert.Equal(At("2026-11-19T20:00:00Z"), PartyRules.Tonight(utc, Guid.Empty, Array.Empty<PartyNight>(), At("2026-11-19T22:59:00Z"))!.Value.At);
        Assert.Equal(At("2026-11-26T20:00:00Z"), PartyRules.Tonight(utc, Guid.Empty, Array.Empty<PartyNight>(), At("2026-11-19T23:01:00Z"))!.Value.At);
        Assert.Null(PartyRules.Next(new GroupSchedule(), At("2026-11-19T12:00:00Z")));
    }

    [Fact]
    public void PushingBack15AppliesAtOnceOncePerPerson()
    {
        var hub = Hub();
        var gid = Group(hub);
        var said = new List<PartyAnnouncement>();
        hub.PartyAnnounced += said.Add;

        var p = hub.PushBack(Sam, gid, 15)!;
        Assert.Equal(At("2026-11-19T20:15:00Z"), p.StartsAt);
        Assert.Equal(At("2026-11-19T20:00:00Z"), p.At);
        Assert.False(p.CanPushBack);
        Assert.Equal(new PartyAnnouncement(gid, PartyRules.Pushed, Sam, 15), said.Single());
        Assert.Throws<PartnerException>(() => hub.PushBack(Sam, gid, 15));

        // Joe has his own; everyone sees who pushed it.
        var joe = hub.PushBack(Joe, gid, 15)!;
        Assert.Equal(At("2026-11-19T20:30:00Z"), joe.StartsAt);
        Assert.Equal(new[] { "sam", "joe" }, joe.Pushes.Select(x => x.Name));

        // Next week starts afresh.
        _time.SetUtcNow(At("2026-11-20T12:00:00Z"));
        var next = hub.Party(Sam, gid)!;
        Assert.Equal(At("2026-11-26T20:00:00Z"), next.StartsAt);
        Assert.True(next.CanPushBack);
    }

    [Fact]
    public void LongerPostponementsNeedAMajorityAndLapseAtStartTime()
    {
        var hub = Hub();
        var gid = Group(hub, three: true);

        // Kim asks for 30: Kim alone is 1 of 3; Sam's yes carries it.
        var asked = hub.PushBack(Kim, gid, 30)!;
        Assert.Equal(At("2026-11-19T20:00:00Z"), asked.StartsAt);
        Assert.True(asked.Request!.Mine);
        var r = hub.Party(Sam, gid)!.Request!;
        Assert.Equal("later", r.Kind);
        Assert.Equal(30, r.Minutes);
        Assert.False(r.Voted);
        Assert.Throws<PartnerException>(() => hub.PushBack(Sam, gid, 60));
        var passed = hub.PartyVote(Sam, gid, r.Id, yes: true)!;
        Assert.Equal(At("2026-11-19T20:30:00Z"), passed.StartsAt);
        Assert.Null(passed.Request);

        // Joe asks for 60 more; nobody answers before it starts: the current time stands.
        hub.PushBack(Joe, gid, 60);
        _time.SetUtcNow(At("2026-11-19T20:30:00Z"));
        var lapsed = hub.Party(Kim, gid)!;
        Assert.Null(lapsed.Request);
        Assert.Equal(At("2026-11-19T20:30:00Z"), lapsed.StartsAt);
        Assert.Throws<PartnerException>(() => hub.PushBack(Kim, gid, 15));
    }

    [Fact]
    public void NotTonightSkipsOnlyThePartyAndAFailedOneMeansCantMakeIt()
    {
        var hub = Hub();
        var gid = Group(hub, schedule: Thursdays(cadence: GroupSchedule.Weekly));
        var before = hub.Groups(Joe).Single();

        hub.SkipTonight(Joe, gid);
        var r = hub.Party(Sam, gid)!.Request!;
        Assert.Equal("skip", r.Kind);
        var skipped = hub.PartyVote(Sam, gid, r.Id, yes: true)!;
        Assert.True(skipped.Skipped);
        Assert.Throws<PartnerException>(() => hub.JoinParty(Joe, gid));

        // The title's own schedule carries on as it was.
        var after = hub.Groups(Joe).Single();
        Assert.Equal(before.Schedule!.Cadence, after.Schedule!.Cadence);
        Assert.Equal(before.OpensAt, after.OpensAt);
        Assert.Equal(before.LockedFromId, after.LockedFromId);

        // Next week: Sam asks, Joe says no; Sam simply can't make it.
        _time.SetUtcNow(At("2026-11-26T12:00:00Z"));
        hub.SkipTonight(Sam, gid);
        var no = hub.PartyVote(Joe, gid, hub.Party(Joe, gid)!.Request!.Id, yes: false)!;
        Assert.False(no.Skipped);
        Assert.Equal(new[] { "sam" }, no.CantMake);
        Assert.True(hub.Party(Sam, gid)!.ICantMake);
    }

    [Fact]
    public void RemindersAndTheStartWithoutQuestionGoOutOnceByTheClock()
    {
        var hub = Hub();
        var gid = Group(hub, three: true);
        Assert.Empty(hub.DueParties());

        _time.SetUtcNow(At("2026-11-19T19:45:00Z"));
        Assert.Equal(new[] { new PartyAnnouncement(gid, PartyRules.Reminder) }, hub.DueParties());
        Assert.Empty(hub.DueParties());

        // Kim can't make it: at start time the others are asked, once.
        hub.CantMake(Kim, gid, true);
        Assert.Equal(new[] { "kim" }, hub.Party(Joe, gid)!.CantMake);
        _time.SetUtcNow(At("2026-11-19T20:00:00Z"));
        Assert.Equal(new[] { new PartyAnnouncement(gid, PartyRules.Without) }, hub.DueParties());
        Assert.Empty(hub.DueParties());
    }

    [Fact]
    public void TheFirstToJoinStartsItAndLaterJoinersGetTheSession()
    {
        var hub = Hub();
        var gid = Group(hub);
        Assert.Throws<PartnerException>(() => hub.JoinParty(Joe, gid));
        _time.SetUtcNow(At("2026-11-19T19:50:00Z"));
        var first = hub.JoinParty(Joe, gid);
        Assert.True(first.Start);
        Assert.Equal(Ep1, first.PartId);
        Assert.False(hub.JoinParty(Sam, gid).Start);
        Assert.Throws<PartnerException>(() => hub.PartySession(Sam, gid, "x"));
        hub.PartySession(Joe, gid, "live-1");
        var later = hub.JoinParty(Sam, gid);
        Assert.False(later.Start);
        Assert.Equal("live-1", later.SessionId);
        Assert.Equal("joe", hub.Party(Sam, gid)!.StartedBy);
    }

    [Fact]
    public void WhatPlaysIsTheNextPartEveryoneCanWatch()
    {
        var order = new List<Guid> { Ep1, Ep2, Ep3 };
        var ahead = new ShowProgress(new[] { Ep1, Ep2 }, null, null, order);
        var behind = new ShowProgress(new[] { Ep1 }, null, null, order);

        // Together off: the part after the furthest everyone has watched with the group; Together on: after the group's furthest.
        Assert.Equal(Ep2, PartnerHub.PartOf(false, new[] { ahead, behind }));
        Assert.Equal(Ep3, PartnerHub.PartOf(true, new[] { ahead, behind }));
        Assert.Null(PartnerHub.PartOf(false, new[] { new ShowProgress(order, null, null, order) }));

        var hub = Hub();
        var gid = Group(hub);
        // Watched on their own, it doesn't count for the group: the group's record does.
        _watch.Played.Add((Joe, Ep1));
        _watch.Played.Add((Sam, Ep1));
        Assert.Equal(Ep1, hub.Party(Joe, gid)!.PartId);
        hub.Mark(Joe, new[] { gid }, new[] { Ep1 });
        hub.Mark(Sam, new[] { gid }, new[] { Ep1 });
        var p = hub.Party(Joe, gid)!;
        Assert.Equal(Ep2, p.PartId);
        Assert.Equal("S1:E2", p.PartLabel);
    }

    [Fact]
    public void OnlyMembersSeeAGroupsParty()
    {
        var hub = Hub();
        var gid = Group(hub);
        Assert.Throws<PartnerException>(() => hub.Party(Out, gid));
        Assert.Throws<PartnerException>(() => hub.PushBack(Out, gid, 15));
        Assert.Null(hub.Names(Out, gid));
        Assert.Equal(new[] { "sam" }, hub.Names(Joe, gid)!.Value.Others);

        // Announcements go only to the members' Nitrate apps.
        var sessions = new[]
        {
            new LiveApp("a", Joe, "Nitrate"), new LiveApp("b", Sam, "Nitrate Mobile"), new LiveApp("c", Sam, "Jellyfin Web"),
            new LiveApp("d", Out, "Nitrate"), new LiveApp("e", Joe, "NitrateX"),
        };
        Assert.Equal(new[] { "a", "b" }, PartyPush.Targets(hub.MembersOf(gid), sessions).Select(s => s.SessionId));

        // Each member's message is their own view of it.
        var m = PartyPush.MessageFor(new PartyAnnouncement(gid, PartyRules.Pushed, Sam, 15), Joe, hub, id => id == Sam ? "sam" : "joe")!;
        Assert.Equal("Bear", m.SeriesName);
        Assert.Equal(new[] { "sam" }, m.Others);
        Assert.Equal("sam", m.ByName);
        Assert.False(m.ByMe);
        Assert.Null(PartyPush.MessageFor(new PartyAnnouncement(gid, PartyRules.Pushed), Out, hub, _ => null));
    }

    [Fact]
    public void LeavingTakesWhatTheyAskedAlong()
    {
        var hub = Hub();
        var gid = Group(hub, three: true);
        hub.PushBack(Kim, gid, 60);
        hub.CantMake(Kim, gid, true);
        hub.Leave(Kim, gid);
        var p = hub.Party(Joe, gid)!;
        Assert.Null(p.Request);
        Assert.Empty(p.CantMake);
    }

    [Fact]
    public void ScheduleChangesAreToldToTheRestOfTheGroup()
    {
        var hub = Hub();
        var gid = Group(hub, three: true);
        var said = new List<ScheduleAnnouncement>();
        hub.ScheduleAnnounced += said.Add;

        var weekly = Thursdays(cadence: GroupSchedule.Weekly);
        hub.ProposeSchedule(Joe, gid, weekly);
        var ask = said.Single();
        Assert.Equal(ScheduleNews.Ask, ask.Kind);
        Assert.Equal(Joe, ask.From);
        Assert.Equal(hub.Groups(Sam).Single().Pending!.Id, ask.ChangeId);

        // The ask goes to the others' Nitrate apps, not the asker's, and never outside the group.
        var sessions = new[]
        {
            new LiveApp("joe", Joe, "Nitrate"), new LiveApp("sam", Sam, "Nitrate Mobile"), new LiveApp("kim", Kim, "Nitrate"),
            new LiveApp("web", Sam, "Jellyfin Web"), new LiveApp("out", Out, "Nitrate"),
        };
        Assert.Equal(new[] { "sam", "kim" }, PartyPush.Targets(ask, hub.MembersOf(gid), sessions).Select(s => s.SessionId));

        // Each member's message: who asked, the titles, the schedule asked for; old apps read it as party news with the party as it is.
        var m = PartyPush.MessageFor(ask, Sam, hub, id => id == Joe ? "joe" : "x")!;
        Assert.Equal(ScheduleNews.Ask, m.Kind);
        Assert.Equal("joe", m.FromName);
        Assert.False(m.FromMe);
        Assert.Equal(new[] { "Bear" }, m.Titles);
        Assert.Equal(new[] { "joe", "kim" }, m.Others);
        Assert.Equal(GroupSchedule.Weekly, m.Proposed.Cadence);
        Assert.NotNull(m.Party);
        Assert.Null(m.Outcome);
        var json = System.Text.Json.JsonSerializer.Serialize(m);
        Assert.Contains("\"Kind\":\"schedule-ask\"", json, StringComparison.Ordinal);
        Assert.Contains("\"ChangeId\"", json, StringComparison.Ordinal);
        Assert.Null(PartyPush.MessageFor(ask, Out, hub, _ => "out"));

        // A no doesn't settle it in a group of three; a yes does, and everyone hears.
        hub.Vote(Kim, gid, ask.ChangeId, yes: false);
        Assert.Single(said);
        hub.Vote(Sam, gid, ask.ChangeId, yes: true);
        var done = said[1];
        Assert.Equal(ScheduleNews.Done, done.Kind);
        Assert.Equal(ScheduleNews.Passed, done.Outcome);
        Assert.Equal(Sam, done.By);
        Assert.Equal(new[] { "joe", "sam", "kim" }, PartyPush.Targets(done, hub.MembersOf(gid), sessions).Select(s => s.SessionId));
        var d = PartyPush.MessageFor(done, Joe, hub, id => id == Sam ? "sam" : "joe")!;
        Assert.True(d.FromMe);
        Assert.Equal("sam", d.ByName);
        Assert.Equal(GroupSchedule.Weekly, d.Current?.Cadence);
    }

    [Fact]
    public void FailedAndLapsedScheduleChangesAreTold()
    {
        var hub = Hub();
        var gid = Group(hub);
        var said = new List<ScheduleAnnouncement>();
        hub.ScheduleAnnounced += said.Add;

        hub.ProposeSchedule(Joe, gid, Thursdays(cadence: GroupSchedule.Weekly));
        hub.Vote(Sam, gid, said.Single().ChangeId, yes: false);
        Assert.Equal(ScheduleNews.Failed, said[1].Outcome);
        Assert.Equal(GroupSchedule.Binge, PartyPush.MessageFor(said[1], Sam, hub, _ => "joe")!.Current?.Cadence);

        hub.ProposeSchedule(Sam, gid, Thursdays(cadence: GroupSchedule.Days2));
        Assert.Equal(ScheduleNews.Ask, said[2].Kind);
        _time.Advance(TimeSpan.FromDays(7) + TimeSpan.FromMinutes(1));
        var lapsed = hub.DueChanges().Single();
        Assert.Equal(ScheduleNews.Done, lapsed.Kind);
        Assert.Equal(ScheduleNews.Lapsed, lapsed.Outcome);
        Assert.Equal(said[2].ChangeId, lapsed.ChangeId);
        Assert.Null(lapsed.By);
        Assert.Empty(hub.DueChanges());
        Assert.Equal(4, said.Count);
    }
}
