namespace Nitrate.Companion.Credits;

/// <summary>
/// When each frame read of a title's last part was shown: frames come one per keyframe now (every
/// 2–5 s, irregular), denser around the boundaries (<see cref="CreditReader"/>), or one a second in
/// readings kept from before (<see cref="Regular"/>). Every length the finder works with
/// (credits, scenes, gaps) is in milliseconds from these times, never a count of frames.
/// </summary>
/// <param name="Times">Each frame's time (ms), ascending.</param>
/// <param name="EndMs">Where the last frame stops counting (the end of what was read).</param>
/// <param name="ScrollGaps">For each frame, how long before it the frame its scroll and stillness were measured against was (ms; 0 for none).</param>
public sealed record CreditTimeline(IReadOnlyList<long> Times, long EndMs, IReadOnlyList<long> ScrollGaps)
{
    /// <summary>[count] frames one every [intervalMs] from [startMs] (readings from before keyframes).</summary>
    public static CreditTimeline Regular(long startMs, int count, long intervalMs) => new(
        Enumerable.Range(0, count).Select(i => startMs + (i * intervalMs)).ToList(),
        startMs + (count * intervalMs),
        Enumerable.Range(0, count).Select(i => i == 0 ? 0 : intervalMs).ToList());

    /// <summary>Frames.</summary>
    public int Count => Times.Count;

    /// <summary>Frame [i]'s time; <see cref="EndMs"/> for [i] = <see cref="Count"/> (a run's end).</summary>
    public long At(int i) => i < Times.Count ? Times[i] : EndMs;

    /// <summary>How long frame [i] stands for: until the next frame (or the end).</summary>
    public long Span(int i) => At(i + 1) - At(i);

    /// <summary>The gap frame [i]'s scroll was measured over, in seconds (0 when none).</summary>
    public double ScrollGapSeconds(int i) => i < ScrollGaps.Count ? ScrollGaps[i] / 1000.0 : 0;

    /// <summary>
    /// Where a reading's last frame stops counting: the title's end when it's near (within
    /// <see cref="LastSpanMaxMs"/>), else the last frame plus the usual gap between frames.
    /// </summary>
    public static long EndOf(IReadOnlyList<long> times, long runtimeMs)
    {
        if (times.Count == 0)
        {
            return runtimeMs;
        }

        var last = times[^1];
        if (runtimeMs > last && runtimeMs - last <= LastSpanMaxMs)
        {
            return runtimeMs;
        }

        var gaps = times.Zip(times.Skip(1), (a, b) => b - a).Where(g => g > 0).OrderBy(g => g).ToList();
        return last + (gaps.Count == 0 ? 1_000 : gaps[gaps.Count / 2]);
    }

    /// <summary>The last frame counts up to this long, to the title's end.</summary>
    public const long LastSpanMaxMs = 10_000;
}
