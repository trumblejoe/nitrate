using Nitrate.Companion.Intros;
using Xunit;

namespace Nitrate.Companion.Tests;

public class IntroTests
{
    private const int Rate = AudioPrint.SampleRate;

    /// <summary>Sound that changes every 0.2 s: a few tones chosen by [seed], like music or speech.</summary>
    private static short[] Sound(int seed, double seconds)
    {
        var random = new Random(seed);
        var samples = new short[(int)(seconds * Rate)];
        var block = Rate / 5;
        var tones = new double[3];
        for (var i = 0; i < samples.Length; i++)
        {
            if (i % block == 0)
            {
                for (var t = 0; t < tones.Length; t++)
                {
                    tones[t] = 300 + (random.NextDouble() * 1700);
                }
            }

            double v = 0;
            foreach (var hz in tones)
            {
                v += Math.Sin(2 * Math.PI * hz * i / Rate);
            }

            samples[i] = (short)(v * 3000);
        }

        return samples;
    }

    /// <summary>[parts] one after another, with a little noise and a gain of its own (another encode).</summary>
    private static short[] Episode(int noiseSeed, double gain, params short[][] parts)
    {
        var random = new Random(noiseSeed);
        return parts.SelectMany(p => p).Select(s => (short)Math.Clamp((s * gain) + (random.NextDouble() * 600) - 300, short.MinValue, short.MaxValue)).ToArray();
    }

    private static readonly short[] Theme = Sound(1000, 30);

    [Fact]
    public void FindsTheSharedIntroAfterColdOpensOfAnyLength()
    {
        var opens = new[] { 0.0, 42.0, 17.5, 63.0 };
        var episodes = opens.Select((open, i) => Episode(i, 0.8 + (i * 0.1), Sound(i + 1, open), Theme, Sound(i + 50, 120))).ToList();
        var found = IntroFinder.Find(episodes.Select(e => AudioPrint.Of(e)).ToList());
        for (var i = 0; i < opens.Length; i++)
        {
            var intro = found[i];
            Assert.NotNull(intro);
            Assert.InRange(intro!.StartMs, (long)(opens[i] * 1000) - 600, (long)(opens[i] * 1000) + 600);
            Assert.InRange(intro.EndMs - intro.StartMs, 29_000, 31_500);
        }
    }

    [Fact]
    public void AnEpisodeWithoutTheIntroGetsNone()
    {
        var episodes = new[]
        {
            Episode(1, 1, Sound(1, 20), Theme, Sound(2, 90)),
            Episode(2, 1, Sound(3, 90), Sound(4, 60)),
            Episode(3, 1, Sound(5, 35), Theme, Sound(6, 90)),
        };
        var found = IntroFinder.Find(episodes.Select(e => AudioPrint.Of(e)).ToList());
        Assert.NotNull(found[0]);
        Assert.Null(found[1]);
        // Its neighbour has nothing, so it looks one further along.
        Assert.NotNull(found[2]);
    }

    [Fact]
    public void ShortStingsAreNotIntros()
    {
        var sting = Sound(77, 6);
        var episodes = new[] { Episode(1, 1, Sound(1, 10), sting, Sound(2, 90)), Episode(2, 1, Sound(3, 25), sting, Sound(4, 90)) };
        var found = IntroFinder.Find(episodes.Select(e => AudioPrint.Of(e)).ToList());
        Assert.All(found, Assert.Null);
    }

    [Fact]
    public void HowMuchOfEachEpisodeIsRead()
    {
        Assert.Equal(3 * 60_000, IntroFinder.SearchMs(8 * 60_000));
        Assert.Equal(396_000, IntroFinder.SearchMs(22 * 60_000));
        Assert.Equal(10 * 60_000, IntroFinder.SearchMs(60 * 60_000));
        Assert.Equal(60_000, IntroFinder.SearchMs(60_000));
    }

    [Fact]
    public void StoreKeepsResultsAndPrintsByVersion()
    {
        var folder = Path.Combine(Path.GetTempPath(), "nitrate-intros-" + Guid.NewGuid().ToString("N"));
        try
        {
            var id = Guid.NewGuid();
            var store = new IntroStore(folder);
            Assert.True(store.Needs(id, "v1"));
            store.Put(id, new IntroResult("v1", new Intro(1_000, 31_000), DateTimeOffset.UtcNow));
            store.SavePrint(id, "v1", new uint[] { 1, 2, 3 });
            var again = new IntroStore(folder);
            Assert.False(again.Needs(id, "v1"));
            Assert.Equal(new Intro(1_000, 31_000), again.Get(id)!.Intro);
            Assert.Equal(new uint[] { 1, 2, 3 }, again.Print(id, "v1"));
            Assert.Null(again.Print(id, "v2"));
            again.KeepOnly(new HashSet<Guid>());
            Assert.Null(new IntroStore(folder).Get(id));
            Assert.Null(again.Print(id, "v1"));
        }
        finally
        {
            if (Directory.Exists(folder))
            {
                Directory.Delete(folder, true);
            }
        }
    }
}
