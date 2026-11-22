using Nitrate.Companion.Insights;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

public class InsightsTests
{
    private static readonly DateTimeOffset Day = DateTimeOffset.Parse("2026-11-20T10:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
    private static readonly long Now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    [Fact]
    public void TrendsAddUpByDayAndShape()
    {
        using var db = new CompanionDb(":memory:");
        var store = new QueryTrendStore(db);
        store.Add(new[] { ("/Shows/NextUp", 100L, Day), ("/Shows/NextUp", 300L, Day.AddMinutes(5)), ("/Items/{id}/Similar", 2_000L, Day) });
        store.Add(new[] { ("/Shows/NextUp", 120L, Day.AddHours(1)), ("/Shows/NextUp", 90L, Day.AddDays(1)) });
        var trends = store.Trends(7, Day.AddDays(1));
        Assert.Equal("/Items/{id}/Similar", trends[0].Shape);
        var nextUp = trends.Single(t => t.Shape == "/Shows/NextUp");
        Assert.Equal(new[] { 3L, 1L }, nextUp.Days.Select(d => d.Count));
        Assert.Equal(128, nextUp.Days[0].MedianMs);
        Assert.Equal(512, nextUp.Days[0].SlowMs);
    }

    [Fact]
    public void TwoPeopleOrAnAdminRejectAPart()
    {
        using var db = new CompanionDb(":memory:");
        var votes = new SegmentVotes(db);
        var item = Guid.NewGuid();
        var (sam, kim) = (Guid.NewGuid(), Guid.NewGuid());
        Assert.False(votes.Add(item, new SegmentVote(sam, SegmentPart.Intro, false, 1)));
        // The same person again doesn't count twice.
        Assert.False(votes.Add(item, new SegmentVote(sam, SegmentPart.Intro, false, 2)));
        // Another part is its own vote.
        Assert.False(votes.Add(item, new SegmentVote(kim, SegmentPart.Credits, false, 3)));
        Assert.True(votes.Add(item, new SegmentVote(kim, SegmentPart.Intro, false, 4)));
        Assert.True(votes.Add(Guid.NewGuid(), new SegmentVote(sam, SegmentPart.Credits, true, 5)));
    }

    [Fact]
    public void ASittingCountsPlayedTimeOnly()
    {
        var clock = new SittingClock(Day);
        clock.Tick(Day.AddSeconds(10), false);
        clock.Tick(Day.AddSeconds(20), true);
        // Paused for ten minutes: not counted.
        clock.Tick(Day.AddMinutes(10), false);
        clock.Tick(Day.AddMinutes(10).AddSeconds(10), false);
        // The player went quiet for an hour: only the cap counts.
        clock.Tick(Day.AddMinutes(70), false);
        Assert.Equal(TimeSpan.FromSeconds(20 + 10 + 90), clock.Watched);
    }

    [Fact]
    public void ChatIsReadOnlyByThoseWhoWereThere()
    {
        using var db = new CompanionDb(":memory:");
        var records = new PeopleRecords(db);
        var item = Guid.NewGuid();
        var (sam, kim, lee) = (Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid());
        records.AddChat(item, "1", new ChatRecord(sam, "sam", "Text", "what a twist", 60_000, Now, new[] { sam, kim }));
        Assert.Single(records.Chat(item, kim));
        Assert.Empty(records.Chat(item, lee));
    }

    [Fact]
    public void ProblemsGroupByTitleAcrossDevices()
    {
        using var db = new CompanionDb(":memory:");
        var records = new PeopleRecords(db);
        var (film, other) = (Guid.NewGuid(), Guid.NewGuid());
        records.AddProblem(new PlaybackProblem(film, "Dune", "tv1", "Living room", "Failed", "Decoder", 1, Now + 1, Now + 1));
        records.AddProblem(new PlaybackProblem(film, "Dune", "tv1", "Living room", "Failed", "Decoder", 1, Now + 2, Now + 2));
        records.AddProblem(new PlaybackProblem(film, "Dune", "phone", "Phone", "Converted", "Audio codec", 1, Now + 3, Now + 3));
        records.AddProblem(new PlaybackProblem(other, "Heat", "phone", "Phone", "Converted", null, 1, Now + 4, Now + 4));
        var titles = records.Problems();
        Assert.Equal("Dune", titles[0].Name);
        Assert.Equal((2, 2, 1), (titles[0].Devices, titles[0].Failed, titles[0].Converted));
    }

    [Fact]
    public void MomentsBelongToTheirOwner()
    {
        using var db = new CompanionDb(":memory:");
        var records = new PeopleRecords(db);
        var sam = Guid.NewGuid();
        var moment = new Moment(Guid.NewGuid(), sam, "sam", Guid.NewGuid(), 75_000, "the shot", null, 1);
        records.PutMoment(moment);
        Assert.Equal("the shot", Assert.Single(records.Moments(sam)).Note);
        records.DeleteMoment(sam, moment.Id);
        Assert.Empty(records.Moments(sam));
    }
}
