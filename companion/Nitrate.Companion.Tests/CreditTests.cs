using System.Diagnostics;
using System.Text.Json;
using Nitrate.Companion.Credits;
using Xunit;
using Xunit.Abstractions;

namespace Nitrate.Companion.Tests;

/// <summary>Synthetic 320×180 yuv420p frames, as the scan reads them (core's CreditScenesTest draws the same).</summary>
internal static class Synth
{
    public const int W = 320;
    public const int H = 180;
    private const int Black = 16;
    private const int Ink = 235;

    public static byte[] Frame(Func<int, int, int> luma, Func<int, int, (int U, int V)>? chroma = null)
    {
        var bytes = new byte[(W * H) + (2 * (W / 2) * (H / 2))];
        for (var y = 0; y < H; y++)
        {
            for (var x = 0; x < W; x++)
            {
                bytes[(y * W) + x] = (byte)Math.Clamp(luma(x, y), 0, 255);
            }
        }

        var c = W * H;
        var q = (W / 2) * (H / 2);
        for (var y = 0; y < H / 2; y++)
        {
            for (var x = 0; x < W / 2; x++)
            {
                var (u, v) = chroma?.Invoke(x * 2, y * 2) ?? (128, 128);
                bytes[c + (y * (W / 2)) + x] = (byte)u;
                bytes[c + q + (y * (W / 2)) + x] = (byte)v;
            }
        }

        return bytes;
    }

    /// <summary>A line of small crisp letters (5×7, 2 apart, a space every 5), centred, its top at [top].</summary>
    public static void Text(byte[] luma, int top, int letters, int width = W, int height = H)
    {
        var left = (width - (letters * 7)) / 2;
        for (var k = 0; k < letters; k++)
        {
            if (k % 5 == 4)
            {
                continue;
            }

            var x0 = left + (k * 7);
            for (var dy = 0; dy < 7; dy++)
            {
                var y = top + dy;
                if (y < 0 || y >= height)
                {
                    continue;
                }

                for (var dx = 0; dx < 5; dx++)
                {
                    var on = (k % 3) switch
                    {
                        0 => dx == 0 || dx == 4 || dy == 3, // H
                        1 => dx == 0 || dy == 0 || dy == 3 || dy == 6, // E
                        _ => ((dx == 0 || dx == 4) && dy > 0 && dy < 6) || ((dy == 0 || dy == 6) && dx > 0 && dx < 4), // O
                    };
                    if (on)
                    {
                        luma[(y * width) + x0 + dx] = Ink;
                    }
                }
            }
        }
    }

    public static byte[] Blank() => Frame((_, _) => Black);

    /// <summary>A credits card: a role and two names, centred; [seed] varies the lengths.</summary>
    public static byte[] Card(int seed)
    {
        var f = Blank();
        Text(f, 70, 10 + (seed % 4));
        Text(f, 86, 16 + (seed % 7));
        Text(f, 102, 14 + (seed % 5));
        return f;
    }

    /// <summary>Rolling credits at [t] seconds: lines every 18 rows moving up [speed] rows a second.</summary>
    public static byte[] Rolling(int t, int speed = 12)
    {
        var f = Blank();
        var offset = t * speed;
        for (var j = offset / 18; j * 18 < offset + H + 18; j++)
        {
            Text(f, (j * 18) - offset, 12 + ((j * 7) % 13));
        }

        return f;
    }

    /// <summary>A lit picture.</summary>
    public static byte[] Picture(int t = 0) => Frame((x, y) => 30 + (((x * 7) + (y * 13) + (t * 5)) % 200), (x, y) => (110 + (x % 30), 140 - (y % 25)));

    /// <summary>A near-black smoke-filled scene with a few soft bright patches (embers, a doorway), drifting.</summary>
    public static byte[] Smoke(int t) => Frame((x, y) =>
    {
        var haze = 14 + (int)(8 * (Math.Sin((x + t) / 23.0) + Math.Sin(y / 17.0) + 2) / 2) + (((x * 31) + (y * 17) + t) % 5);
        double glow = 0;
        foreach (var (cx, cy) in new[] { (80 + t, 60), (200, 120), (260 - t, 40) })
        {
            glow += 190 * Math.Exp(-(((x - cx) * (x - cx)) + ((y - cy) * (y - cy))) / 200.0);
        }

        return haze + (int)glow;
    });

    /// <summary>A dark fire scene: a soft orange glow and crisp orange sparks and flame tongues.</summary>
    public static byte[] Fire(int t) => Frame(
        (x, y) =>
        {
            var glow = 170 * Math.Exp(-(((x - 160) * (x - 160)) + ((y - 150) * (y - 150))) / 600.0);
            var spark = (x + (t * 3)) % 23 < 2 && (y + (t * 7)) % 29 < 7 && y < 140;
            return 14 + (int)glow + (spark ? 200 : 0);
        },
        (x, y) => (68, 183));

    public static IReadOnlyList<CreditFeatures> Read(IEnumerable<byte[]> frames, double maxShift = 0.25)
    {
        var reader = new CreditFeatureReader(W, H, maxShift);
        return frames.Select(f => reader.Next(f.AsSpan(0, W * H), f.AsSpan(W * H, (W / 2) * (H / 2)), f.AsSpan((W * H) + ((W / 2) * (H / 2))))).ToList();
    }

    public static IEnumerable<byte[]> Seq(int count, Func<int, byte[]> frame) => Enumerable.Range(0, count).Select(frame);

    /// <summary>Card credits: each card held 4 s, a second of black between.</summary>
    public static IEnumerable<byte[]> Cards(int count) => Seq(count, i => i % 5 == 4 ? Blank() : Card(i / 5));
}

public class CreditTests
{
    private const long Interval = CreditScanJob.IntervalMs;
    private const long Runtime = 40 * 60_000L;
    private static readonly long Start = (long)(Runtime * CreditFinder.CreditsFrom);
    private readonly ITestOutputHelper _out;

    public CreditTests(ITestOutputHelper output) => _out = output;

    private static string Verdicts(IEnumerable<byte[]> frames) => new(CreditFinder.Classify(Synth.Read(frames), Interval));

    [Fact]
    public void FramesAreMeasured()
    {
        var card = Synth.Read(new[] { Synth.Card(1), Synth.Card(1) })[1];
        Assert.Equal(3, card.Lines);
        Assert.True(card.Sharp > 0.9 && card.Strokes > 0.9 && card.Symmetry > 0.95, card.ToString());
        Assert.True(card.Still);
        Assert.Equal(0, card.Saturation);

        var rolling = Synth.Read(new[] { Synth.Rolling(0), Synth.Rolling(1) })[1];
        Assert.Equal(12, rolling.ScrollDy);

        var fire = Synth.Read(new[] { Synth.Fire(0) })[0];
        Assert.True(fire.Saturation > CreditFinder.SaturationMax, fire.ToString());

        var smoke = Synth.Read(new[] { Synth.Smoke(0) })[0];
        Assert.True(smoke.Sharp < CreditFinder.SharpMin, smoke.ToString());
    }

    [Fact]
    public void OnlyTextOnBlackLooksLikeCredits()
    {
        Assert.Equal('C', CreditFinder.Verdict(Synth.Read(new[] { Synth.Card(2) })[0]));
        Assert.Equal('b', CreditFinder.Verdict(Synth.Read(new[] { Synth.Blank() })[0]));
        Assert.Equal('.', CreditFinder.Verdict(Synth.Read(new[] { Synth.Picture() })[0]));
        Assert.DoesNotContain('C', Verdicts(Synth.Seq(10, Synth.Smoke)));
        Assert.All(Verdicts(Synth.Seq(10, Synth.Fire)), v => Assert.False(CreditFinder.Counts(v)));
        Assert.All(Verdicts(Synth.Seq(10, i => Synth.Rolling(i))), v => Assert.True(CreditFinder.Counts(v)));
        // Black between cards counts with them.
        Assert.All(Verdicts(Synth.Cards(20)), v => Assert.True(CreditFinder.Counts(v)));
    }

    [Fact]
    public void RollingCreditsThenAScene()
    {
        // 12 minutes read: 5 min of film, 4 min of rolling credits, a 1-min scene, then 2 min of cards to the end.
        var frames = Synth.Seq(300, Synth.Picture).Concat(Synth.Seq(240, i => Synth.Rolling(i))).Concat(Synth.Seq(60, Synth.Picture)).Concat(Synth.Cards(120));
        var d = CreditFinder.Decide(Runtime, Start, Synth.Read(frames), Interval, null);
        var skip = Assert.Single(d.Skips);
        Assert.Equal(Start + 300_000, skip.CreditsStartMs);
        Assert.Equal(Start + 540_000, skip.SceneStartMs);
        Assert.False(skip.AfterCredits);
        Assert.Equal(Start + 600_000, d.OutroStartMs);
    }

    [Fact]
    public void CardCreditsThenAPostCreditsScene()
    {
        var frames = Synth.Seq(400, Synth.Picture).Concat(Synth.Cards(240)).Concat(Synth.Seq(80, Synth.Picture));
        var d = CreditFinder.Decide(Runtime, Start, Synth.Read(frames), Interval, null);
        var skip = Assert.Single(d.Skips);
        Assert.Equal(Start + 400_000, skip.CreditsStartMs);
        Assert.Equal(Start + 640_000, skip.SceneStartMs);
        Assert.True(skip.AfterCredits);
        Assert.False(skip.Spoken);
        Assert.Null(d.OutroStartMs);
    }

    [Fact]
    public void ASmokeFilledSceneIsNotCredits()
    {
        // Batman Begins' escape from the burning house: 2 min near-black with soft glows, 3 min more film, then credits to the end.
        var frames = Synth.Seq(120, Synth.Picture).Concat(Synth.Seq(120, Synth.Smoke)).Concat(Synth.Seq(180, Synth.Picture)).Concat(Synth.Cards(300));
        var d = CreditFinder.Decide(Runtime, Start, Synth.Read(frames), Interval, null);
        Assert.Empty(d.Skips);
        Assert.Equal(Start + 420_000, d.OutroStartMs);
    }

    [Fact]
    public void AFireSceneIsNotCredits()
    {
        var frames = Synth.Seq(120, Synth.Picture).Concat(Synth.Seq(120, Synth.Fire)).Concat(Synth.Seq(180, Synth.Picture)).Concat(Synth.Cards(300));
        var d = CreditFinder.Decide(Runtime, Start, Synth.Read(frames), Interval, null);
        Assert.Empty(d.Skips);
        Assert.Equal(Start + 420_000, d.OutroStartMs);
    }

    // 3 min of film, 90 s of something read as credits (a dark scene with crisp lights), 2 min of film, credits to the end.
    private static IReadOnlyList<CreditFeatures> DarkThenFilm() =>
        Synth.Read(Synth.Seq(180, Synth.Picture).Concat(Synth.Cards(90)).Concat(Synth.Seq(120, Synth.Picture)).Concat(Synth.Cards(330)));

    [Fact]
    public void DialogueOverADarkStretchMakesItAScene()
    {
        var frames = DarkThenFilm();
        // Pictures alone: a "scene in the credits".
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, null).Skips);
        // People talking all through it: not credits.
        var talk = Enumerable.Range(0, 15).Select(i => new DialogueLine(Start + 182_000 + (i * 6_000), Start + 185_000 + (i * 6_000))).ToList();
        var d = CreditFinder.Decide(Runtime, Start, frames, Interval, talk);
        Assert.Empty(d.Skips);
        var vetoed = Assert.Single(d.Vetoed);
        Assert.Equal(Start + 180_000, vetoed.StartMs);
        Assert.Contains("15 lines of dialogue", vetoed.Why, StringComparison.Ordinal);
        Assert.Contains('d', d.Verdicts);
        Assert.Equal(Start + 390_000, d.OutroStartMs);
    }

    [Fact]
    public void ASongOverTheCreditsIsStillCredits()
    {
        var frames = Synth.Read(Synth.Seq(400, Synth.Picture).Concat(Synth.Cards(240)).Concat(Synth.Seq(80, Synth.Picture)));
        // Two captioned song lines.
        var two = new[] { new DialogueLine(Start + 420_000, Start + 424_000), new DialogueLine(Start + 500_000, Start + 504_000) };
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, two).Skips);
        // A chorus: six lines within 20 s.
        var chorus = Enumerable.Range(0, 6).Select(i => new DialogueLine(Start + 450_000 + (i * 3_500), Start + 453_000 + (i * 3_500))).ToList();
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, chorus).Skips);
        // A whole song marked ♪, or all in italics.
        var song = Enumerable.Range(0, 30).Select(i => new DialogueLine(Start + 410_000 + (i * 7_000), Start + 414_000 + (i * 7_000), Sung: true)).ToList();
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, song).Skips);
        var italic = song.Select(l => l with { Sung = false, Italic = true }).ToList();
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, italic).Skips);
    }

    [Fact]
    public void RollingCreditsAreNeverVetoedByWhatsSaidOverThem()
    {
        var frames = Synth.Read(Synth.Seq(300, Synth.Picture).Concat(Synth.Seq(240, i => Synth.Rolling(i))).Concat(Synth.Seq(60, Synth.Picture)));
        var talk = Enumerable.Range(0, 30).Select(i => new DialogueLine(Start + 305_000 + (i * 7_000), Start + 308_000 + (i * 7_000))).ToList();
        Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, talk).Skips);
    }

    [Fact]
    public void DialogueInTheSceneConfirmsIt()
    {
        var frames = Synth.Read(Synth.Seq(400, Synth.Picture).Concat(Synth.Cards(240)).Concat(Synth.Seq(80, Synth.Picture)));
        var lines = new[] { new DialogueLine(Start + 650_000, Start + 653_000), new DialogueLine(Start + 660_000, Start + 662_000) };
        Assert.True(Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, lines).Skips).Spoken);
        // No subtitles: the pictures decide alone, and nothing is confirmed.
        Assert.False(Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, null).Skips).Spoken);
        Assert.False(Assert.Single(CreditFinder.Decide(Runtime, Start, frames, Interval, Array.Empty<DialogueLine>()).Skips).Spoken);
    }

    [Fact]
    public void DialogueVoteRules()
    {
        DialogueLine L(long s, bool sung = false, bool italic = false) => new(s, s + 2_000, sung, italic);
        Assert.False(CreditFinder.DialogueVote(new[] { L(0), L(40_000), L(80_000) }, 0, 100_000).Veto);
        Assert.True(CreditFinder.DialogueVote(new[] { L(0), L(20_000), L(40_000), L(60_000) }, 0, 100_000).Veto);
        Assert.False(CreditFinder.DialogueVote(new[] { L(0), L(5_000), L(10_000), L(15_000), L(20_000) }, 0, 100_000).Veto);
        Assert.False(CreditFinder.DialogueVote(new[] { L(0, sung: true), L(20_000, sung: true), L(40_000, sung: true), L(60_000, sung: true) }, 0, 100_000).Veto);
        Assert.False(CreditFinder.DialogueVote(new[] { L(0, italic: true), L(20_000, italic: true), L(40_000, italic: true), L(60_000, italic: true) }, 0, 100_000).Veto);
        // Only lines inside the stretch count.
        Assert.False(CreditFinder.DialogueVote(new[] { L(0), L(20_000), L(40_000), L(160_000) }, 0, 100_000).Veto);
    }

    [Fact]
    public void AScenePastLongCreditsIsFound()
    {
        // A 100-minute film read from 70 min, one verdict every 5 s: 10 min of film, 4 min of credits, a 1-min scene.
        const long interval = 5_000;
        var start = 70 * 60_000L;
        var frames = Bools(120, false).Concat(Bools(48, true)).Concat(Bools(12, false)).ToList();
        var skip = Assert.Single(CreditFinder.Find(100 * 60_000L, start, frames, interval));
        Assert.Equal(start + (120 * interval), skip.CreditsStartMs);
        Assert.Equal(start + (168 * interval), skip.SceneStartMs);
        Assert.True(skip.AfterCredits);
    }

    [Fact]
    public void ADarkSceneWithTheFilmGoingOnIsNotCredits()
    {
        // 2 min read as credits, 25 min more of the film, then 10 min of credits to the end: no scene in the credits.
        const long interval = 5_000;
        var frames = Bools(12, false).Concat(Bools(24, true)).Concat(Bools(300, false)).Concat(Bools(120, true)).ToList();
        Assert.Empty(CreditFinder.Find(140 * 60_000L, 98 * 60_000L, frames, interval));
    }

    [Fact]
    public void ShortCreditsOrNoSceneAfterAreNotSkips()
    {
        const long interval = 5_000;
        Assert.Empty(CreditFinder.Find(100 * 60_000L, 70 * 60_000L, Bools(20, false).Concat(Bools(6, true)).Concat(Bools(20, false)).ToList(), interval));
        Assert.Empty(CreditFinder.Find(100 * 60_000L, 70 * 60_000L, Bools(20, false).Concat(Bools(40, true)).Concat(Bools(2, false)).ToList(), interval));
    }

    [Fact]
    public void FinalCreditsNeverHoldAScene()
    {
        const long interval = 5_000;
        const long runtime = 100 * 60_000L;
        var start = 70 * 60_000L;
        List<bool> F(params (int N, bool C)[] parts) => parts.SelectMany(p => Bools(p.N, p.C)).ToList();

        Assert.Equal(start + (120 * interval), CreditFinder.FinalCredits(runtime, start, F((120, false), (48, true), (2, false)), interval));
        Assert.Equal(start + (172 * interval), CreditFinder.FinalCredits(runtime, start, F((120, false), (40, true), (12, false), (6, true)), interval));
        Assert.Equal(start + (152 * interval), CreditFinder.FinalCredits(runtime, start, F((120, false), (20, true), (12, false), (10, true), (1, false), (10, true)), interval));
        Assert.Null(CreditFinder.FinalCredits(runtime, start, F((120, false), (40, true), (12, false)), interval));
        Assert.Null(CreditFinder.FinalCredits(runtime, start, F((120, false), (2, true)), interval));
    }

    [Fact]
    public void BlackAtTheVeryEndBelongsToTheCredits()
    {
        var frames = Synth.Read(Synth.Seq(400, Synth.Picture).Concat(Synth.Cards(200)).Concat(Synth.Seq(60, _ => Synth.Blank())));
        var d = CreditFinder.Decide(Runtime, Start, frames, Interval, null);
        Assert.Empty(d.Skips);
        Assert.Equal(Start + 400_000, d.OutroStartMs);
    }

    [Fact]
    public void FeaturesArePackedAsTheReportShowsThem()
    {
        var frames = Synth.Read(Synth.Seq(30, i => Synth.Rolling(i)).Concat(Synth.Seq(3, Synth.Fire)));
        var round = CreditFrameCodec.Round(frames);
        Assert.Equal(frames.Count, round.Count);
        Assert.Equal(round, CreditFrameCodec.Round(round));
        for (var i = 0; i < frames.Count; i++)
        {
            Assert.Equal(frames[i].Lines, round[i].Lines);
            Assert.Equal(frames[i].ScrollDy, round[i].ScrollDy);
            Assert.InRange(Math.Abs(frames[i].Dark - round[i].Dark), 0, 0.003);
            Assert.Equal(CreditFinder.Verdict(frames[i]), CreditFinder.Verdict(round[i]));
        }
    }

    [Fact]
    public void TheReportIsCompactJson()
    {
        var item = Guid.NewGuid();
        var frames = CreditFrameCodec.Round(Synth.Read(Synth.Seq(400, Synth.Picture).Concat(Synth.Cards(240)).Concat(Synth.Seq(80, Synth.Picture))));
        var set = new CreditFrameSet("v", Start, Interval, Convert.ToBase64String(CreditFrameCodec.Pack(frames)));
        var lines = new[] { new DialogueLine(Start + 650_000, Start + 653_000, Italic: true) };
        var report = CreditReport.Build(item, Runtime, new CreditResult("v", Array.Empty<CreditSkip>(), DateTimeOffset.UnixEpoch, Format: CreditResult.CurrentFormat), set, lines);
        Assert.Equal(720, report.Rows.Count);
        Assert.Equal(report.Columns.Count, report.Rows[0].Count);
        Assert.Equal("C", report.Rows[450][^1]);
        Assert.Equal(Start / 1000.0, report.Rows[0][0]);
        Assert.Null(report.ReadMs);
        var skip = Assert.Single(report.Skips);
        Assert.True(skip.Spoken);
        Assert.Equal(new long[] { Start + 650_000, Start + 653_000, 2 }, Assert.Single(report.Dialogue!));
        var json = JsonSerializer.Serialize(report);
        Assert.Contains("\"Rows\":[[1680,", json, StringComparison.Ordinal);
        _out.WriteLine($"Report for 12 min at 1 frame/s: {json.Length / 1024} KB; a row: {JsonSerializer.Serialize(report.Rows[450])}");
        Assert.True(json.Length < 150_000);

        // Not read the current way yet.
        var waiting = CreditReport.Build(item, Runtime, null, null, null, reading: true, note: "Reading");
        Assert.True(waiting.Reading);
        Assert.Empty(waiting.Rows);
    }

    [Fact]
    public async Task FramesAreReadAsTheyStream()
    {
        // Two rolling-credit frames, as ffmpeg hands them over, then a half frame (cut off): two measured.
        using var stream = new MemoryStream(Synth.Rolling(0).Concat(Synth.Rolling(1)).Concat(new byte[1000]).ToArray());
        var frames = await CreditScanJob.ReadFramesAsync(stream, Synth.W, Synth.H, 0.25, CancellationToken.None);
        Assert.Equal(2, frames.Count);
        Assert.Equal(12, frames[1].ScrollDy);
        var args = CreditScanJob.Arguments("/films/a.mkv", new CreditDecodeRequest(4_200_500, null, 0, 0.75)).ToList();
        Assert.Contains("showinfo,scale=320:180:flags=area,format=yuv420p", args);
        Assert.Equal("nokey", args[args.IndexOf("-skip_frame") + 1]);
        Assert.Equal("4200.5", args[args.IndexOf("-ss") + 1]);
        Assert.DoesNotContain("-t", args);
    }

    [Fact]
    public void MeasuringIsQuickEnough()
    {
        var frames = Synth.Seq(60, i => Synth.Rolling(i)).Concat(Synth.Seq(60, Synth.Smoke)).Concat(Synth.Seq(60, Synth.Picture)).ToList();
        var reader = new CreditFeatureReader(Synth.W, Synth.H);
        var clock = Stopwatch.StartNew();
        for (var round = 0; round < 5; round++)
        {
            foreach (var f in frames)
            {
                reader.Next(f.AsSpan(0, Synth.W * Synth.H), f.AsSpan(Synth.W * Synth.H, 14_400), f.AsSpan((Synth.W * Synth.H) + 14_400));
            }
        }

        var perSecond = frames.Count * 5 / clock.Elapsed.TotalSeconds;
        _out.WriteLine($"Features: {perSecond:0} frames/s at 320×180");
        Assert.True(perSecond > 50, $"{perSecond} frames/s");
    }

    [Fact]
    public void OldResultsAreReadAgain()
    {
        var store = new CreditStore(null);
        var id = Guid.NewGuid();
        store.Put(id, new CreditResult("v1", Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow));
        Assert.True(store.Needs(id, "v1"));
        store.Put(id, new CreditResult("v1", Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow, Format: 2));
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
    public void ResultsAndFramesAreKeptPerVersionAndForgottenWhenGone()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-credits-" + Guid.NewGuid().ToString("N") + ".db");
        using var db = new Nitrate.Companion.Storage.CompanionDb(path);
        var item = Guid.NewGuid();
        var store = new CreditStore(db);
        Assert.True(store.Needs(item, "a"));
        store.Put(item, new CreditResult("a", new[] { new CreditSkip(1, 2, true) }, DateTimeOffset.UnixEpoch, Format: CreditResult.CurrentFormat));
        var frames = CreditFrameCodec.Round(Synth.Read(Synth.Cards(10)));
        store.PutFrames(item, new CreditFrameSet("a", 0, 1_000, Convert.ToBase64String(CreditFrameCodec.Pack(frames))));
        var again = new CreditStore(db);
        Assert.False(again.Needs(item, "a"));
        Assert.True(again.Needs(item, "b"));
        Assert.Single(again.Get(item)!.Skips);
        Assert.Equal(frames, again.Frames(item)!.Frames());
        again.KeepOnly(new HashSet<Guid>());
        Assert.Null(new CreditStore(db).Get(item));
        Assert.Null(new CreditStore(db).Frames(item));
    }

    private static IEnumerable<bool> Bools(int n, bool value) => Enumerable.Repeat(value, n);
}
