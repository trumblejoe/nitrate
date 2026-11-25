using System.Text.Json;
using MediaBrowser.Model.Configuration;
using MediaBrowser.Model.Entities;
using Nitrate.Companion.Credits;
using Nitrate.Companion.Scanning;
using Xunit;
using Xunit.Abstractions;

namespace Nitrate.Companion.Tests;

/// <summary>A synthetic title: what's on screen when, decoded one frame per keyframe (irregular) or at a frame rate.</summary>
internal sealed class SynthTitle
{
    // Keyframes 2–5 s apart, irregular (an encoder's GOPs).
    private static readonly long[] Pattern = { 2_000, 3_000, 5_000, 4_000, 2_500, 3_500, 4_500, 2_000, 3_000 };
    private readonly List<(long From, Func<double, byte[]> Frame)> _parts = new();

    public SynthTitle(long runtimeMs, long[]? gaps = null)
    {
        RuntimeMs = runtimeMs;
        var g = gaps ?? Pattern;
        var keyframes = new List<long>();
        for (long t = 0, k = 0; t < runtimeMs; t += g[k++ % g.Length])
        {
            keyframes.Add(t);
        }

        Keyframes = keyframes;
    }

    public long RuntimeMs { get; }

    public IReadOnlyList<long> Keyframes { get; }

    public List<CreditDecodeRequest> Asked { get; } = new();

    /// <summary>From [fromMs], [frame] (seconds since this part started).</summary>
    public SynthTitle Then(long fromMs, Func<double, byte[]> frame)
    {
        _parts.Add((fromMs, frame));
        return this;
    }

    public static byte[] Rolling(double s) => Synth.Rolling(1, (int)Math.Round(s * 12));

    public static byte[] Cards(double s) => s % 5 >= 4 ? Synth.Blank() : Synth.Card((int)(s / 5));

    public byte[] At(long ms)
    {
        var part = _parts.Last(p => p.From <= ms);
        return part.Frame((ms - part.From) / 1000.0);
    }

    public Task<CreditRun> Decode(CreditDecodeRequest r, CancellationToken cancellationToken)
    {
        Asked.Add(r);
        var builder = new CreditRunBuilder(Synth.W, Synth.H, r.FromMs, r.ToMs, r.MaxShift);
        var end = Math.Min(r.ToMs ?? RuntimeMs, RuntimeMs);
        var times = r.Fps <= 0
            ? Keyframes.Where(t => t >= r.FromMs && t < end)
            : Enumerable.Range(0, int.MaxValue).Select(k => r.FromMs + (k * 1000L / r.Fps)).TakeWhile(t => t < end);
        foreach (var t in times)
        {
            builder.Add(t, At(t));
        }

        return Task.FromResult(builder.Build());
    }

    public Task<CreditRead> ReadAsync(IReadOnlyList<DialogueLine>? dialogue = null) =>
        CreditReader.ReadAsync(RuntimeMs, Decode, _ => dialogue, CancellationToken.None);
}

public class CreditReadingTests
{
    private const long Min = 60_000;
    private readonly ITestOutputHelper _out;

    public CreditReadingTests(ITestOutputHelper output) => _out = output;

    private static void Near(long expected, long? actual, long within = 1_000) =>
        Assert.True(actual != null && Math.Abs(actual.Value - expected) <= within, $"expected {expected} ± {within}, got {actual}");

    [Fact]
    public async Task RollingCreditsThenASceneAtKeyframes()
    {
        // 40 min: film to 33:00, rolling credits to 37:00, a 1-min scene, cards to the end.
        var movie = new SynthTitle(40 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(33 * Min, SynthTitle.Rolling)
            .Then(37 * Min, s => Synth.Picture((int)s))
            .Then(38 * Min, SynthTitle.Cards);
        var read = await movie.ReadAsync();
        var skip = Assert.Single(read.Decision.Skips);
        Near(33 * Min, skip.CreditsStartMs);
        Near(37 * Min, skip.SceneStartMs);
        Assert.False(skip.AfterCredits);
        Near(38 * Min, read.Decision.OutroStartMs);
        // Boundaries were looked at closer, at 4 frames a second.
        Assert.Contains(movie.Asked, r => r.Fps == CreditReader.RefineFps);
        _out.WriteLine($"{read.Decoded} frames decoded, read from {read.ReadFromMs / 1000} s; verdicts {read.Decision.Verdicts}");
    }

    [Fact]
    public async Task CardCreditsThenAPostCreditsSceneAtKeyframes()
    {
        var movie = new SynthTitle(40 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(34 * Min, SynthTitle.Cards)
            .Then(38 * Min, s => Synth.Picture((int)s));
        var read = await movie.ReadAsync();
        var skip = Assert.Single(read.Decision.Skips);
        Near(34 * Min, skip.CreditsStartMs);
        Near(38 * Min, skip.SceneStartMs);
        Assert.True(skip.AfterCredits);
        Assert.Null(read.Decision.OutroStartMs);
    }

    [Fact]
    public async Task ASmokeFilledSceneIsNotCreditsAtKeyframes()
    {
        var movie = new SynthTitle(40 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(30 * Min, s => Synth.Smoke((int)s))
            .Then(32 * Min, s => Synth.Picture((int)s))
            .Then(35 * Min, SynthTitle.Cards);
        var read = await movie.ReadAsync();
        Assert.Empty(read.Decision.Skips);
        Near(35 * Min, read.Decision.OutroStartMs);
    }

    [Fact]
    public async Task AFireSceneIsNotCreditsAtKeyframes()
    {
        var movie = new SynthTitle(40 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(30 * Min, s => Synth.Fire((int)s))
            .Then(32 * Min, s => Synth.Picture((int)s))
            .Then(35 * Min, SynthTitle.Cards);
        var read = await movie.ReadAsync();
        Assert.Empty(read.Decision.Skips);
        Near(35 * Min, read.Decision.OutroStartMs);
    }

    [Fact]
    public async Task ReadingBackStopsOnceItsClearlyInTheFilm()
    {
        // Two hours, 8 minutes of credits: read back 3 minutes at a time until 6 minutes of film lie before them.
        var movie = new SynthTitle(120 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(112 * Min, SynthTitle.Cards);
        var read = await movie.ReadAsync();
        Assert.Empty(read.Decision.Skips);
        Near(112 * Min, read.Decision.OutroStartMs);
        // 120 → 117 → 114 → 111 → 108 → 105: 7 minutes of film before the credits.
        Assert.Equal(105 * Min, read.ReadFromMs);
        Assert.True(read.ReadFromMs > (long)(120 * Min * CreditFinder.CreditsFrom));
        var chunks = movie.Asked.Where(r => r.Fps == 0).ToList();
        Assert.Equal(5, chunks.Count);
        Assert.Null(chunks[0].ToMs);
        Assert.Equal(new long?[] { 117 * Min, 114 * Min, 111 * Min, 108 * Min }, chunks.Skip(1).Select(c => c.ToMs));
        _out.WriteLine($"Read {(120 * Min - read.ReadFromMs) / Min} min of 36; {read.Decoded} frames decoded");

        // No credits at all: 6 minutes of film from the end, and done.
        var none = new SynthTitle(120 * Min).Then(0, s => Synth.Picture((int)s));
        var nothing = await none.ReadAsync();
        Assert.Equal(111 * Min, nothing.ReadFromMs);
        Assert.Empty(nothing.Decision.Skips);
        Assert.Null(nothing.Decision.OutroStartMs);
    }

    [Fact]
    public async Task LongCreditsWithASceneInTheMiddleAreReadWhole()
    {
        // Credits 100:00–106:00, a 2-min scene, credits 108:00 to the end (12 min).
        var movie = new SynthTitle(120 * Min)
            .Then(0, s => Synth.Picture((int)s))
            .Then(100 * Min, SynthTitle.Rolling)
            .Then(106 * Min, s => Synth.Picture((int)s))
            .Then(108 * Min, SynthTitle.Cards);
        var read = await movie.ReadAsync();
        var skip = Assert.Single(read.Decision.Skips);
        Near(100 * Min, skip.CreditsStartMs);
        Near(106 * Min, skip.SceneStartMs);
        Assert.False(skip.AfterCredits);
        Near(108 * Min, read.Decision.OutroStartMs);
        Assert.True(read.ReadFromMs <= 94 * Min, $"read from {read.ReadFromMs}");
    }

    [Fact]
    public async Task BoundariesArePlacedWithinASecondDespiteLongGops()
    {
        // Keyframes 10 s apart: a keyframe alone could be 10 s off.
        var movie = new SynthTitle(40 * Min, new long[] { 10_000 })
            .Then(0, s => Synth.Picture((int)s))
            .Then((34 * Min) + 4_300, SynthTitle.Cards)
            .Then((38 * Min) + 7_700, s => Synth.Picture((int)s));
        var read = await movie.ReadAsync();
        var skip = Assert.Single(read.Decision.Skips);
        Near((34 * Min) + 4_300, skip.CreditsStartMs);
        Near((38 * Min) + 7_700, skip.SceneStartMs);

        // Without the closer look, only to the keyframe.
        var keyframesOnly = CreditFinder.Decide(movie.RuntimeMs, read.Frames.Where((_, i) => read.Timeline.Times[i] % 10_000 == 0).ToList(), Keyed(read.Timeline), null);
        Assert.Equal((34 * Min) + 10_000, Assert.Single(keyframesOnly.Skips).CreditsStartMs);
    }

    private static CreditTimeline Keyed(CreditTimeline t)
    {
        var keep = Enumerable.Range(0, t.Count).Where(i => t.Times[i] % 10_000 == 0).ToList();
        return new CreditTimeline(keep.Select(i => t.Times[i]).ToList(), t.EndMs, keep.Select(_ => 10_000L).ToList());
    }

    [Fact]
    public void RollingCreditsScrollAcrossIrregularGaps()
    {
        var times = new long[] { 0, 2_000, 5_000, 10_000, 14_000, 16_500 };
        var b = new CreditRunBuilder(Synth.W, Synth.H, 0, null, CreditReader.KeyframeMaxShift);
        foreach (var t in times)
        {
            b.Add(t, SynthTitle.Rolling(t / 1000.0));
        }

        var run = b.Build();
        // Rows moved: 12 a second times the gap.
        Assert.Equal(new[] { 0, 24, 36, 60, 48, 30 }, run.Features.Select(f => f.ScrollDy));
        var timeline = new CreditTimeline(run.Times, 18_000, run.Gaps);
        Assert.All(CreditFinder.Classify(run.Features, timeline).Skip(1), v => Assert.True(CreditFinder.Counts(v)));
    }

    [Fact]
    public void KeyframeReadingsAreKeptWithTheirTimes()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-credits-" + Guid.NewGuid().ToString("N") + ".db");
        using var db = new Nitrate.Companion.Storage.CompanionDb(path);
        var item = Guid.NewGuid();
        var store = new CreditStore(db);
        var frames = CreditFrameCodec.Round(Synth.Read(Synth.Cards(5)));
        var timeline = new CreditTimeline(new long[] { 100_000, 102_000, 105_250, 109_000, 109_250 }, 112_000, new long[] { 0, 2_000, 3_250, 3_750, 250 });
        store.PutFrames(item, CreditFrameSet.Of("v", 99_000, frames, timeline, readMs: 9_100, decoded: 61, hardware: "vaapi"));
        var kept = new CreditStore(db).Frames(item)!;
        Assert.Equal(timeline.Times, kept.Timeline().Times);
        Assert.Equal(timeline.ScrollGaps, kept.Timeline().ScrollGaps);
        Assert.Equal(112_000, kept.Timeline().EndMs);
        Assert.Equal(frames, kept.Frames());

        var report = CreditReport.Build(item, 120_000, new CreditResult("v", Array.Empty<CreditSkip>(), DateTimeOffset.UnixEpoch, Format: CreditResult.CurrentFormat), kept, null);
        Assert.Equal(105.25, report.Rows[2][0]);
        Assert.Equal(0, report.IntervalMs);
        Assert.Equal((9_100L, 61, 99_000L, "vaapi"), (report.ReadMs!.Value, report.Frames!.Value, report.ReadFromMs!.Value, report.Hardware!));
        var json = JsonSerializer.Serialize(report);
        Assert.Contains("\"ReadMs\":9100,\"Frames\":61,\"ReadFromMs\":99000,\"Hardware\":\"vaapi\"", json, StringComparison.Ordinal);

        // A set kept from before (one a second, no times) still reads.
        var old = new CreditFrameSet("v", 99_000, 1_000, Convert.ToBase64String(CreditFrameCodec.Pack(frames)));
        var oldJson = JsonSerializer.Deserialize<CreditFrameSet>(JsonSerializer.Serialize(new { Version = "v", StartMs = 99_000, IntervalMs = 1_000, Data = old.Data }))!;
        Assert.Equal(new long[] { 99_000, 100_000, 101_000, 102_000, 103_000 }, oldJson.Timeline().Times);
        Assert.Equal(104_000, oldJson.Timeline().EndMs);
        Assert.Null(CreditReport.Build(item, 120_000, null, oldJson, null).ReadFromMs);
    }

    [Fact]
    public void KeyframeAndCloserLookArguments()
    {
        var keyframes = CreditScanJob.Arguments("/f.mkv", new CreditDecodeRequest(5_400_000, 5_580_000, 0, 0.75)).ToList();
        Assert.Equal(
            "-hide_banner -nostdin -nostats -loglevel info -skip_frame nokey -skip_loop_filter all -ss 5400 -t 181 -i /f.mkv -an -sn -dn -fps_mode passthrough -vf showinfo,scale=320:180:flags=area,format=yuv420p -f rawvideo -pix_fmt yuv420p -",
            string.Join(' ', keyframes));
        var look = CreditScanJob.Arguments("/f.mkv", new CreditDecodeRequest(5_412_750, 5_420_000, 4, 0.25), new HardwareChoice("vaapi", new[] { "-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128" })).ToList();
        Assert.Equal(
            "-hide_banner -nostdin -nostats -loglevel info -hwaccel vaapi -hwaccel_device /dev/dri/renderD128 -skip_frame noref -skip_loop_filter all -ss 5412.75 -t 8.25 -i /f.mkv -an -sn -dn -fps_mode passthrough -vf fps=4,showinfo,scale=320:180:flags=area,format=yuv420p -f rawvideo -pix_fmt yuv420p -",
            string.Join(' ', look));
        Assert.Equal(12.5, CreditScanJob.ShowInfoTime("[Parsed_showinfo_0 @ 0x55d5c] n:   3 pts:  12500 pts_time:12.5    duration:   1000 duration_time:1"));
        Assert.Null(CreditScanJob.ShowInfoTime("[Parsed_showinfo_0 @ 0x55d5c] color_range:tv color_space:bt709"));
        Assert.Null(CreditScanJob.ShowInfoTime("Stream mapping:"));
    }

    [Fact]
    public async Task FramesAreTimedAsTheyStream()
    {
        using var stream = new MemoryStream(SynthTitle.Rolling(0).Concat(SynthTitle.Rolling(3)).Concat(SynthTitle.Rolling(5)).ToArray());
        var times = new Queue<long?>(new long?[] { 1_000, 4_000, null });
        var builder = new CreditRunBuilder(Synth.W, Synth.H, 0, null, 0.75);
        await CreditScanJob.ReadFramesAsync(stream, Synth.W, Synth.H, _ => Task.FromResult(times.Dequeue()), builder.Add, CancellationToken.None);
        var run = builder.Build();
        Assert.Equal(new long[] { 1_000, 4_000 }, run.Times);
        Assert.Equal(36, run.Features[1].ScrollDy);
    }
}

public class HardwareDecodingTests
{
    private static readonly string[] All = { "h264", "hevc", "mpeg2video", "vc1", "vp9", "av1" };

    private static HardwareDecoding Set(HardwareAccelerationType type, string[]? codecs = null, bool hevc10 = true) =>
        new(type, "/dev/dri/renderD129", string.Empty, codecs ?? All, Hevc10: hevc10);

    private static string? Args(HardwareDecoding hw, string codec = "h264", int depth = 8, string? pix = "yuv420p", bool windows = false, Func<string, bool>? supported = null) =>
        hw.For(codec, depth, pix, windows, supported) is { } c ? c.Name + ": " + string.Join(' ', c.Arguments) : null;

    [Fact]
    public void ArgumentsFollowJellyfinsSettings()
    {
        HardwareDecoding.ForgetFailures();
        Assert.Equal("vaapi: -hwaccel vaapi -hwaccel_device /dev/dri/renderD129", Args(Set(HardwareAccelerationType.vaapi)));
        Assert.Equal("vaapi: -hwaccel vaapi -hwaccel_device /dev/dri/renderD128", Args(Set(HardwareAccelerationType.qsv)));
        Assert.Equal("vaapi: -hwaccel vaapi -hwaccel_device /dev/dri/renderD130", Args(Set(HardwareAccelerationType.qsv) with { QsvDevice = "/dev/dri/renderD130" }));
        Assert.Equal("d3d11va: -hwaccel d3d11va", Args(Set(HardwareAccelerationType.qsv), windows: true));
        Assert.Equal("d3d11va: -hwaccel d3d11va -hwaccel_device 1", Args(Set(HardwareAccelerationType.qsv) with { QsvDevice = "1" }, windows: true));
        Assert.Equal("cuda: -hwaccel cuda", Args(Set(HardwareAccelerationType.nvenc)));
        Assert.Equal("d3d11va: -hwaccel d3d11va", Args(Set(HardwareAccelerationType.amf), windows: true));
        Assert.Equal("vaapi: -hwaccel vaapi -hwaccel_device /dev/dri/renderD129", Args(Set(HardwareAccelerationType.amf)));
        Assert.Equal("videotoolbox: -hwaccel videotoolbox", Args(Set(HardwareAccelerationType.videotoolbox)));

        // Software: none set, ones not used here, VA-API on Windows, the server's ffmpeg without it.
        Assert.Null(Args(Set(HardwareAccelerationType.none)));
        Assert.Null(Args(Set(HardwareAccelerationType.v4l2m2m)));
        Assert.Null(Args(Set(HardwareAccelerationType.rkmpp)));
        Assert.Null(Args(Set(HardwareAccelerationType.vaapi), windows: true));
        Assert.Null(Args(Set(HardwareAccelerationType.nvenc), supported: name => name != "cuda"));
        Assert.NotNull(Args(Set(HardwareAccelerationType.nvenc), supported: name => name == "cuda"));
    }

    [Fact]
    public void OnlyCodecsAndBitDepthsJellyfinDecodesInHardware()
    {
        HardwareDecoding.ForgetFailures();
        var hw = Set(HardwareAccelerationType.vaapi, new[] { "h264", "hevc", "vp9" }, hevc10: false);
        Assert.NotNull(Args(hw, "h264"));
        Assert.NotNull(Args(hw, "h265"));
        Assert.Null(Args(hw, "vc1"));
        Assert.Null(Args(hw, "mpeg2video"));
        // 10-bit HEVC only with its tick; H.264 High 10 never; HEVC 4:2:2 only with the RExt tick.
        Assert.Null(Args(hw, "hevc", 10, "yuv420p10le"));
        Assert.NotNull(Args(hw with { Hevc10 = true }, "hevc", 10, "yuv420p10le"));
        Assert.Null(Args(hw, "h264", 10, "yuv420p10le"));
        Assert.Null(Args(hw with { Hevc10 = true }, "hevc", 10, "yuv422p10le"));
        Assert.NotNull(Args(hw with { HevcRext10 = true }, "hevc", 10, "yuv422p10le"));
        Assert.Null(Args(hw, "vp9", 10, "yuv420p10le"));
        Assert.NotNull(Args(hw with { Vp910 = true }, "vp9", 10, "yuv420p10le"));

        var options = new EncodingOptions { HardwareAccelerationType = HardwareAccelerationType.nvenc, HardwareDecodingCodecs = new[] { "hevc" }, EnableDecodingColorDepth10Hevc = true };
        Assert.Equal("cuda: -hwaccel cuda", Args(HardwareDecoding.From(options), "hevc", 10, "yuv420p10le"));
        Assert.Null(Args(HardwareDecoding.From(options), "h264"));
    }

    [Fact]
    public async Task AFailureFallsBackToSoftwareAndIsRemembered()
    {
        HardwareDecoding.ForgetFailures();
        var hw = Set(HardwareAccelerationType.vaapi);
        var choice = hw.For("hevc", 10, "yuv420p10le", false)!;
        var tries = new List<string>();
        Task<int> Read(HardwareChoice? c, int hardwareFrames)
        {
            tries.Add(c?.Name ?? "software");
            return c == null ? Task.FromResult(40) : hardwareFrames < 0 ? throw new InvalidOperationException("Failed to initialise VAAPI") : Task.FromResult(hardwareFrames);
        }

        // Works in hardware.
        Assert.Equal((40, (HardwareChoice?)choice), await HardwareDecoding.RunAsync(choice, c => Read(c, 40), n => n));
        // Hardware throws: read again in software, and that codec isn't tried in hardware again.
        var (frames, used) = await HardwareDecoding.RunAsync(choice, c => Read(c, -1), n => n);
        Assert.Equal(40, frames);
        Assert.Null(used);
        Assert.True(HardwareDecoding.Failed("vaapi", "hevc", 10));
        Assert.Null(hw.For("hevc", 10, "yuv420p10le", false));
        Assert.NotNull(hw.For("h264", 8, "yuv420p", false));
        tries.Clear();
        Assert.Null((await HardwareDecoding.RunAsync(choice, c => Read(c, 40), n => n)).Used);
        Assert.Equal(new[] { "software" }, tries);

        // Hardware reads nothing where software reads frames: remembered too.
        var h264 = hw.For("h264", 8, "yuv420p", false)!;
        Assert.Null((await HardwareDecoding.RunAsync(h264, c => Read(c, 0), n => n)).Used);
        Assert.True(HardwareDecoding.Failed("vaapi", "h264", 8));
        HardwareDecoding.ForgetFailures();
    }
}

public class ReadGateTests
{
    [Fact]
    public async Task OneReadAtATimeAndARequestedReadGoesNext()
    {
        var gate = new ReadGate();
        var order = new List<string>();
        var first = await gate.EnterAsync();
        Assert.True(gate.Busy);
        // Two nightly tasks' next files wait, then a requested check arrives.
        var intros = gate.EnterAsync();
        var timing = gate.EnterAsync();
        var check = gate.EnterAsync(priority: true);
        Assert.Equal((2, 1), (gate.Waiting, gate.PriorityWaiting));
        Assert.False(intros.IsCompleted || timing.IsCompleted || check.IsCompleted);

        first.Dispose();
        var next = await check;
        Assert.False(intros.IsCompleted || timing.IsCompleted);
        next.Dispose();
        next.Dispose(); // Twice is once.
        (await intros).Dispose();
        (await timing).Dispose();
        Assert.False(gate.Busy);
        Assert.Equal((0, 0), (gate.Waiting, gate.PriorityWaiting));
    }

    [Fact]
    public async Task NeverTwoAtOnce()
    {
        var gate = new ReadGate();
        var inside = 0;
        var most = 0;
        var tasks = Enumerable.Range(0, 20).Select(i => Task.Run(() => gate.RunAsync(
            async () =>
            {
                var now = Interlocked.Increment(ref inside);
                most = Math.Max(most, now);
                await Task.Delay(2);
                Interlocked.Decrement(ref inside);
            },
            priority: i % 5 == 0))).ToList();
        await Task.WhenAll(tasks);
        Assert.Equal(1, most);
        Assert.False(gate.Busy);
    }

    [Fact]
    public async Task ACancelledWaiterLeavesTheQueue()
    {
        var gate = new ReadGate();
        var held = await gate.EnterAsync();
        using var cancel = new CancellationTokenSource();
        var waiting = gate.EnterAsync(cancel.Token);
        var after = gate.EnterAsync();
        cancel.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiting);
        Assert.Equal(1, gate.Waiting);
        held.Dispose();
        (await after).Dispose();
        Assert.False(gate.Busy);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => gate.EnterAsync(cancel.Token));
    }
}
