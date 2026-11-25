namespace Nitrate.Companion.Credits;

/// <summary>What to decode of a title for the credits reader.</summary>
/// <param name="FromMs">From here…</param>
/// <param name="ToMs">…to here (exclusive); null: to the end of the file.</param>
/// <param name="Fps">0: one frame per keyframe (only keyframes decoded); else this many frames a second, every frame decoded.</param>
/// <param name="MaxShift">How far (a share of the height) rolling credits may move between two frames.</param>
public sealed record CreditDecodeRequest(long FromMs, long? ToMs, int Fps, double MaxShift);

/// <summary>Frames decoded and measured in one go (one ffmpeg run), in time order.</summary>
/// <param name="Times">Each frame's time (ms).</param>
/// <param name="Gaps">For each, the time since the frame before it in this run (0 for the first).</param>
/// <param name="Features">Each frame's features, measured against the frame before it in this run.</param>
/// <param name="FirstFrame">The first frame's picture (yuv420p), kept to measure it against an earlier run's last.</param>
/// <param name="LastFrame">The last frame's picture.</param>
/// <param name="Decoded">Frames measured (those outside the range left out).</param>
public sealed record CreditRun(IReadOnlyList<long> Times, IReadOnlyList<long> Gaps, IReadOnlyList<CreditFeatures> Features, byte[]? FirstFrame, byte[]? LastFrame, int Decoded)
{
    /// <summary>Nothing decoded.</summary>
    public static readonly CreditRun Empty = new(Array.Empty<long>(), Array.Empty<long>(), Array.Empty<CreditFeatures>(), null, null, 0);

    /// <summary>Frames.</summary>
    public int Count => Times.Count;
}

/// <summary>Measures frames as they're decoded, keeping those in [fromMs, toMs) and only the first and last pictures.</summary>
public sealed class CreditRunBuilder
{
    private readonly int _width;
    private readonly int _height;
    private readonly long _fromMs;
    private readonly long? _toMs;
    private readonly CreditFeatureReader _reader;
    private readonly List<long> _times = new();
    private readonly List<long> _gaps = new();
    private readonly List<CreditFeatures> _features = new();
    private byte[]? _first;
    private byte[]? _last;

    /// <summary>Initializes a new instance of the <see cref="CreditRunBuilder"/> class.</summary>
    public CreditRunBuilder(int width, int height, long fromMs, long? toMs, double maxShift)
    {
        _width = width;
        _height = height;
        _fromMs = fromMs;
        _toMs = toMs;
        _reader = new CreditFeatureReader(width, height, maxShift);
    }

    /// <summary>The bytes of one yuv420p frame.</summary>
    public int FrameSize => (_width * _height) + (2 * (_width / 2) * (_height / 2));

    /// <summary>Measures the yuv420p frame [frame] shown at [atMs] when it's in range.</summary>
    public void Add(long atMs, ReadOnlySpan<byte> frame)
    {
        if (atMs < _fromMs || (_toMs is { } to && atMs >= to) || (_times.Count > 0 && atMs <= _times[^1]))
        {
            return;
        }

        var luma = _width * _height;
        var chroma = (_width / 2) * (_height / 2);
        _features.Add(_reader.Next(frame[..luma], frame.Slice(luma, chroma), frame.Slice(luma + chroma, chroma)));
        _gaps.Add(_times.Count == 0 ? 0 : atMs - _times[^1]);
        _times.Add(atMs);
        _first ??= frame[..FrameSize].ToArray();
        _last ??= new byte[FrameSize];
        frame[..FrameSize].CopyTo(_last);
    }

    /// <summary>What was measured.</summary>
    public CreditRun Build() => new(_times, _gaps, _features, _first, _last, _times.Count);
}

/// <summary>What the credits reader read of a title, and decided.</summary>
/// <param name="Frames">The features kept (rounded as stored), in time order.</param>
/// <param name="Timeline">When each was shown.</param>
/// <param name="ReadFromMs">How far back it read: the earliest time decoded.</param>
/// <param name="Decoded">Frames decoded and measured in all.</param>
/// <param name="Decision">What it decided (on exactly <see cref="Frames"/>).</param>
public sealed record CreditRead(IReadOnlyList<CreditFeatures> Frames, CreditTimeline Timeline, long ReadFromMs, int Decoded, CreditDecision Decision);

/// <summary>
/// Reads a title's end for the credits, cheaply: one frame per keyframe (only keyframes decoded),
/// backwards from the end in <see cref="ChunkMs"/> chunks, stopping once the reading is clearly back
/// in the film (<see cref="ShouldStop"/>) or at <see cref="CreditFinder.CreditsFrom"/>; then each
/// boundary found (credits start, scene start, final credits) is looked at closely, a short stretch
/// decoded at <see cref="RefineFps"/> frames a second, so it's placed to within about a second.
/// </summary>
public static class CreditReader
{
    /// <summary>Read back this much at a time.</summary>
    public const long ChunkMs = 3 * 60_000;

    /// <summary>Boundaries are looked at with this many frames a second.</summary>
    public const int RefineFps = 4;

    /// <summary>A boundary between two keyframes further apart than this is looked at over this much before it only.</summary>
    public const long RefineBeforeMaxMs = 16_000;

    /// <summary>A closer look starts this much before the keyframe before a boundary and ends this much after it.</summary>
    public const long RefineMarginMs = 1_000;

    /// <summary>Rolling credits may move up to this share of the height between two keyframes (overlap ≥ a quarter).</summary>
    public const double KeyframeMaxShift = 0.75;

    /// <summary>…and this share between two frames of a closer look.</summary>
    public const double RefineMaxShift = 0.25;

    /// <summary>
    /// Reads the end of a title [runtimeMs] long through [decode] (ffmpeg on the server, synthetic
    /// frames in tests), with [dialogueFrom] giving its subtitle lines from a time (null: none indexed).
    /// </summary>
    public static async Task<CreditRead> ReadAsync(
        long runtimeMs,
        Func<CreditDecodeRequest, CancellationToken, Task<CreditRun>> decode,
        Func<long, IReadOnlyList<DialogueLine>?> dialogueFrom,
        CancellationToken cancellationToken)
    {
        var floor = (long)(runtimeMs * CreditFinder.CreditsFrom);
        var times = new List<long>();
        var gaps = new List<long>();
        var features = new List<CreditFeatures>();
        byte[]? earliestPicture = null;
        var decoded = 0;
        var end = runtimeMs;
        var readFrom = runtimeMs;
        var first = true;
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var from = Math.Max(floor, end - ChunkMs);
            // The newest chunk reads to the file's end, wherever that is.
            var run = await decode(new CreditDecodeRequest(from, first ? null : end, 0, KeyframeMaxShift), cancellationToken).ConfigureAwait(false);
            decoded += run.Decoded;
            if (run.Count > 0)
            {
                if (features.Count > 0 && earliestPicture != null && run.LastFrame != null)
                {
                    // The newer chunk's first frame, measured against this chunk's last (they were read apart).
                    var reader = new CreditFeatureReader(CreditScanJob.Width, CreditScanJob.Height, KeyframeMaxShift);
                    Measure(reader, run.LastFrame);
                    features[0] = CreditFrameCodec.Round(new[] { Measure(reader, earliestPicture) })[0];
                    gaps[0] = times[0] - run.Times[^1];
                }

                times.InsertRange(0, run.Times);
                gaps.InsertRange(0, run.Gaps);
                features.InsertRange(0, CreditFrameCodec.Round(run.Features));
                earliestPicture = run.FirstFrame;
            }

            readFrom = from;
            first = false;
            if (from <= floor || ShouldStop(features, new CreditTimeline(times, CreditTimeline.EndOf(times, runtimeMs), gaps), readFrom))
            {
                break;
            }

            end = from;
        }

        var dialogue = dialogueFrom(readFrom);
        var keyTimeline = new CreditTimeline(times, CreditTimeline.EndOf(times, runtimeMs), gaps);
        var keyDecision = CreditFinder.Decide(runtimeMs, features, keyTimeline, dialogue);

        // A closer look at each boundary.
        var windows = Windows(keyDecision, keyTimeline);
        var looks = new List<(long From, long To, CreditRun Run)>();
        foreach (var (w0, w1) in windows)
        {
            var step = 1000 / RefineFps;
            // One frame early, only to measure the first one kept against.
            var run = await decode(new CreditDecodeRequest(Math.Max(0, w0 - step), w1, RefineFps, RefineMaxShift), cancellationToken).ConfigureAwait(false);
            decoded += run.Decoded;
            // A look that read nothing leaves the keyframes there as they were.
            if (run.Times.Any(t => t >= w0 && t < w1))
            {
                looks.Add((w0, w1, run));
            }
        }

        if (looks.Count == 0)
        {
            return new CreditRead(features, keyTimeline, readFrom, decoded, keyDecision);
        }

        var merged = new List<(long At, long Gap, CreditFeatures F)>();
        for (var i = 0; i < times.Count; i++)
        {
            if (!looks.Any(l => times[i] >= l.From && times[i] < l.To))
            {
                merged.Add((times[i], gaps[i], features[i]));
            }
        }

        foreach (var (w0, w1, run) in looks)
        {
            var rounded = CreditFrameCodec.Round(run.Features);
            for (var i = 0; i < run.Count; i++)
            {
                if (run.Times[i] >= w0 && run.Times[i] < w1 && run.Times[i] < keyTimeline.EndMs)
                {
                    merged.Add((run.Times[i], run.Gaps[i], rounded[i]));
                }
            }
        }

        merged.Sort((a, b) => a.At.CompareTo(b.At));
        var timeline = new CreditTimeline(merged.Select(m => m.At).ToList(), keyTimeline.EndMs, merged.Select(m => m.Gap).ToList());
        var frames = merged.Select(m => m.F).ToList();
        return new CreditRead(frames, timeline, readFrom, decoded, CreditFinder.Decide(runtimeMs, frames, timeline, dialogue));
    }

    /// <summary>
    /// Whether reading further back can't change what's decided: more than
    /// <see cref="CreditFinder.MaxSceneMs"/> of pictures lie between [readFromMs] and the earliest
    /// credits frame read (or the end, with none). Anything earlier would be followed by a longer
    /// "scene" than a scene in the credits can be, and is too far from the final credits to join
    /// them, so it's the film. While credits keep appearing (a long roll, a scene in the middle of
    /// the credits), the earliest credits frame moves back with them and reading goes on.
    /// </summary>
    public static bool ShouldStop(IReadOnlyList<CreditFeatures> frames, CreditTimeline timeline, long readFromMs)
    {
        var v = CreditFinder.Classify(frames, timeline);
        var anchor = timeline.EndMs;
        for (var i = 0; i < v.Length; i++)
        {
            if (CreditFinder.Counts(v[i]))
            {
                anchor = timeline.At(i);
                break;
            }
        }

        return anchor - readFromMs > CreditFinder.MaxSceneMs;
    }

    /// <summary>
    /// The stretches to look at closely: around each boundary the keyframes found (a skip's credits
    /// and scene starts, the final credits' start), from just before the keyframe before it (before
    /// any black keyframes just before it) to just after it (at most <see cref="RefineBeforeMaxMs"/>
    /// before it), overlapping ones joined.
    /// </summary>
    public static IReadOnlyList<(long From, long To)> Windows(CreditDecision decision, CreditTimeline timeline)
    {
        var bounds = decision.Skips.SelectMany(s => new[] { s.CreditsStartMs, s.SceneStartMs })
            .Concat(decision.OutroStartMs is { } o ? new[] { o } : Array.Empty<long>())
            .Distinct()
            .OrderBy(b => b);
        var windows = new List<(long From, long To)>();
        foreach (var b in bounds)
        {
            var i = IndexOf(timeline, b);
            if (i <= 0)
            {
                continue;
            }

            // Back over black before it too: read one per keyframe, credits that start right after
            // a fade can show black at the keyframe before their first.
            var j = i - 1;
            while (j > 0 && j < decision.Verdicts.Length && decision.Verdicts[j] == 'b')
            {
                j--;
            }

            var before = Math.Max(timeline.At(j), b - RefineBeforeMaxMs);
            var w = (From: before - RefineMarginMs, To: b + RefineMarginMs);
            if (windows.Count > 0 && w.From <= windows[^1].To)
            {
                windows[^1] = (windows[^1].From, Math.Max(windows[^1].To, w.To));
            }
            else
            {
                windows.Add(w);
            }
        }

        return windows;
    }

    private static int IndexOf(CreditTimeline timeline, long at)
    {
        var times = timeline.Times;
        int lo = 0, hi = times.Count - 1;
        while (lo <= hi)
        {
            var mid = (lo + hi) / 2;
            if (times[mid] == at)
            {
                return mid;
            }

            if (times[mid] < at)
            {
                lo = mid + 1;
            }
            else
            {
                hi = mid - 1;
            }
        }

        return -1;
    }

    private static CreditFeatures Measure(CreditFeatureReader reader, byte[] frame)
    {
        var luma = CreditScanJob.Width * CreditScanJob.Height;
        var chroma = (CreditScanJob.Width / 2) * (CreditScanJob.Height / 2);
        return reader.Next(frame.AsSpan(0, luma), frame.AsSpan(luma, chroma), frame.AsSpan(luma + chroma, chroma));
    }
}
