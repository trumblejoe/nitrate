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
    public void ResultsAreKeptPerVersionAndForgottenWhenGone()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-credits-" + Guid.NewGuid().ToString("N") + ".json");
        var item = Guid.NewGuid();
        var store = new CreditStore(path);
        Assert.True(store.Needs(item, "a"));
        store.Put(item, new CreditResult("a", new[] { new CreditSkip(1, 2, true) }, DateTimeOffset.UnixEpoch));
        var again = new CreditStore(path);
        Assert.False(again.Needs(item, "a"));
        Assert.True(again.Needs(item, "b"));
        Assert.Single(again.Get(item)!.Skips);
        again.KeepOnly(new HashSet<Guid>());
        Assert.Null(new CreditStore(path).Get(item));
        File.Delete(path);
    }

    [Fact]
    public void KeyframeTimesAreReadAndHeldOntoAGrid()
    {
        var log = "[Parsed_showinfo_2 @ 0x1] n:   0 pts:  101 pts_time:8.41667 duration: 1\n[Parsed_showinfo_2 @ 0x1] n:   1 pts: 200 pts_time:20 duration: 1\n";
        Assert.Equal(new long[] { 8416, 20000 }, CreditScanner.Times(log));
        byte[] a = { 1 }, b = { 2 };
        // From 0 to 30 s every 5 s: before the first keyframe its picture stands in; then each is held.
        var grid = CreditScanner.Resample(new[] { (8_416L, a), (20_000L, b) }, 0, 30_000, 5_000);
        Assert.Equal(new[] { a, a, a, a, b, b }, grid);
    }
}
