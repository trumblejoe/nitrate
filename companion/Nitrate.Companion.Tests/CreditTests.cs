using Nitrate.Companion.Credits;
using Xunit;

namespace Nitrate.Companion.Tests;

public class CreditTests
{
    private static byte[] Credits() => Enumerable.Range(0, 48 * 27).Select(i => (byte)(i % 20 == 0 ? 240 : 5)).ToArray();

    private static byte[] Picture() => Enumerable.Range(0, 48 * 27).Select(i => (byte)(60 + (i % 120))).ToArray();

    [Fact]
    public void CreditsLookLikeTextOnBlackAndPicturesDoNot()
    {
        Assert.True(CreditFinder.LooksLikeCredits(Credits()));
        Assert.False(CreditFinder.LooksLikeCredits(Picture()));
        Assert.False(CreditFinder.LooksLikeCredits(Array.Empty<byte>()));
    }

    [Fact]
    public void AScenePastLongCreditsIsFound()
    {
        // A 100-minute film read from 70 min, one frame every 5 s: 10 min of film, 4 min of credits, a 1-min scene.
        const long interval = 5_000;
        var start = 70 * 60_000L;
        var frames = Enumerable.Repeat(Picture(), 120).Concat(Enumerable.Repeat(Credits(), 48)).Concat(Enumerable.Repeat(Picture(), 12)).ToList();
        var skip = Assert.Single(CreditFinder.Find(100 * 60_000L, start, frames, interval));
        Assert.Equal(start + (120 * interval), skip.CreditsStartMs);
        Assert.Equal(start + (168 * interval), skip.SceneStartMs);
        Assert.True(skip.AfterCredits);
    }

    [Fact]
    public void ShortCreditsOrNoSceneAfterAreNotSkips()
    {
        const long interval = 5_000;
        var shortCredits = Enumerable.Repeat(Picture(), 20).Concat(Enumerable.Repeat(Credits(), 6)).Concat(Enumerable.Repeat(Picture(), 20)).ToList();
        Assert.Empty(CreditFinder.Find(100 * 60_000L, 70 * 60_000L, shortCredits, interval));
        var creditsToTheEnd = Enumerable.Repeat(Picture(), 20).Concat(Enumerable.Repeat(Credits(), 40)).Concat(Enumerable.Repeat(Picture(), 2)).ToList();
        Assert.Empty(CreditFinder.Find(100 * 60_000L, 70 * 60_000L, creditsToTheEnd, interval));
    }

    [Fact]
    public void FinalCreditsNeverHoldAScene()
    {
        const long interval = 5_000;
        const long runtime = 100 * 60_000L;
        var start = 70 * 60_000L;
        IEnumerable<byte[]> Of(Func<byte[]> f, int n) => Enumerable.Repeat(f(), n);

        // No scene: all the credits, a short logo at the very end included.
        var plain = Of(Picture, 120).Concat(Of(Credits, 48)).Concat(Of(Picture, 2)).ToList();
        Assert.Equal(start + (120 * interval), CreditFinder.FinalCredits(runtime, start, plain, interval));

        // Post-credits scene then a few final cards: only the cards after the scene.
        var post = Of(Picture, 120).Concat(Of(Credits, 40)).Concat(Of(Picture, 12)).Concat(Of(Credits, 6)).ToList();
        Assert.Equal(start + (172 * interval), CreditFinder.FinalCredits(runtime, start, post, interval));

        // Mid-credits scene: the credits after it, past a picture card shorter than a scene.
        var mid = Of(Picture, 120).Concat(Of(Credits, 20)).Concat(Of(Picture, 12)).Concat(Of(Credits, 10)).Concat(Of(Picture, 1)).Concat(Of(Credits, 10)).ToList();
        Assert.Equal(start + (152 * interval), CreditFinder.FinalCredits(runtime, start, mid, interval));

        // Ending on a scene: no final credits; nor too short a stretch.
        Assert.Null(CreditFinder.FinalCredits(runtime, start, Of(Picture, 120).Concat(Of(Credits, 40)).Concat(Of(Picture, 12)).ToList(), interval));
        Assert.Null(CreditFinder.FinalCredits(runtime, start, Of(Picture, 120).Concat(Of(Credits, 2)).ToList(), interval));
    }

    [Fact]
    public void OldResultsAreReadAgainForTheFinalCredits()
    {
        var store = new CreditStore(null);
        var id = Guid.NewGuid();
        store.Put(id, new CreditResult("v1", Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow));
        Assert.True(store.Needs(id, "v1"));
        store.Put(id, new CreditResult("v1", Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow, Format: CreditResult.CurrentFormat));
        Assert.False(store.Needs(id, "v1"));
    }

    [Fact]
    public void SegmentsForJellyfin()
    {
        var id = Guid.NewGuid();
        var segments = Nitrate.Companion.Segments.CompanionSegmentProvider.For(
            id,
            new Nitrate.Companion.Intros.IntroResult("v", new Nitrate.Companion.Intros.Intro(40_000, 70_000), DateTimeOffset.UtcNow, new Nitrate.Companion.Intros.Intro(0, 35_000)),
            new CreditResult("v", Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow, 2_500_000, 2_700_000, CreditResult.CurrentFormat));
        Assert.Equal(
            new[] { "Recap 0-35000", "Intro 40000-70000", "Outro 2500000-2700000" },
            segments.Select(s => $"{s.Type} {s.StartTicks / TimeSpan.TicksPerMillisecond}-{s.EndTicks / TimeSpan.TicksPerMillisecond}"));
        Assert.All(segments, s => Assert.Equal(id, s.ItemId));
        Assert.Empty(Nitrate.Companion.Segments.CompanionSegmentProvider.For(id, null, null));
    }

    [Fact]
    public void ResultsAreKeptPerVersionAndForgottenWhenGone()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-credits-" + Guid.NewGuid().ToString("N") + ".db");
        using var db = new Nitrate.Companion.Storage.CompanionDb(path);
        var item = Guid.NewGuid();
        var store = new CreditStore(db);
        Assert.True(store.Needs(item, "a"));
        store.Put(item, new CreditResult("a", new[] { new CreditSkip(1, 2, true) }, DateTimeOffset.UnixEpoch, Format: CreditResult.CurrentFormat));
        var again = new CreditStore(db);
        Assert.False(again.Needs(item, "a"));
        Assert.True(again.Needs(item, "b"));
        Assert.Single(again.Get(item)!.Skips);
        again.KeepOnly(new HashSet<Guid>());
        Assert.Null(new CreditStore(db).Get(item));
    }

    [Fact]
    public void KeyframeTimesAreReadAndHeldOntoAGrid()
    {
        var log = "[Parsed_showinfo_2 @ 0x1] n:   0 pts:  101 pts_time:8.41667 duration: 1\n[Parsed_showinfo_2 @ 0x1] n:   1 pts: 200 pts_time:20 duration: 1\n";
        Assert.Equal(new long[] { 8416, 20000 }, CreditScanJob.Times(log));
        byte[] a = { 1 }, b = { 2 };
        // From 0 to 30 s every 5 s: before the first keyframe its picture stands in; then each is held.
        var grid = CreditScanJob.Resample(new[] { (8_416L, a), (20_000L, b) }, 0, 30_000, 5_000);
        Assert.Equal(new[] { a, a, a, a, b, b }, grid);
    }
}
