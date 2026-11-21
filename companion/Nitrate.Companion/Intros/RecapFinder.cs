namespace Nitrate.Companion.Intros;

/// <summary>
/// Finds a "Previously on…" recap: the opening of an episode, before its intro, made mostly of
/// clips heard in the episodes before it. Recaps are many short clips stitched together with a
/// voice over, so every shared stretch of a couple of seconds counts, and gaps between them up to
/// <see cref="MaxGapMs"/> are allowed. Matches inside an earlier episode's own intro don't count
/// (the theme isn't a recap).
/// </summary>
public static class RecapFinder
{
    /// <summary>One clip of a recap is at least this long.</summary>
    public const long MinClipMs = 2_000;

    /// <summary>Gaps between clips (the voice over, a cut to black) up to this long.</summary>
    public const long MaxGapMs = 8_000;

    /// <summary>A recap is at least this long...</summary>
    public const long MinMs = 15_000;

    /// <summary>...and starts this soon after the episode does.</summary>
    public const long StartsWithinMs = 30_000;

    /// <summary>At least this share of a recap is clips from earlier episodes.</summary>
    public const double MinCoverage = 0.5;

    /// <summary>Without a known intro, a recap is looked for in this much of the opening.</summary>
    public const long SearchMs = 4 * 60_000;

    /// <summary>An earlier episode: its whole sound's fingerprint, and its intro if known (not counted).</summary>
    public record Earlier(uint[] Print, Intro? Intro);

    /// <summary>
    /// The recap at the start of an episode whose opening is [opening] (its fingerprint), before
    /// [intro] when that's known, from clips of [earlier] episodes; null when there's none.
    /// </summary>
    public static Intro? Find(uint[] opening, Intro? intro, IReadOnlyList<Earlier> earlier)
    {
        var frameMs = AudioPrint.FrameMs;
        var endMs = intro is { StartMs: > 10_000 } ? intro.StartMs : SearchMs;
        var regionFrames = Math.Min(opening.Length, (int)(endMs / frameMs));
        if (regionFrames * frameMs < MinMs || earlier.Count == 0)
        {
            return null;
        }

        var region = opening[..regionFrames];
        var covered = new bool[regionFrames];
        var minClip = (int)(MinClipMs / frameMs);
        foreach (var e in earlier)
        {
            int? introFrom = e.Intro is { } i ? (int)(i.StartMs / frameMs) : null;
            int? introTo = e.Intro is { } j ? (int)(j.EndMs / frameMs) : null;
            foreach (var run in AudioPrint.SharedRuns(region, e.Print, minClip))
            {
                // The earlier episode's own intro: the theme, not a recap.
                if (introFrom is { } f && introTo is { } t && run.BStart < t && run.BEnd > f)
                {
                    continue;
                }

                for (var k = run.AStart; k < run.AEnd; k++)
                {
                    covered[k] = true;
                }
            }
        }

        var first = Array.IndexOf(covered, true);
        if (first < 0 || first * frameMs > StartsWithinMs)
        {
            return null;
        }

        // Clips joined over short gaps, from the first one on.
        var maxGap = (int)(MaxGapMs / frameMs);
        var last = first;
        var hits = 0;
        for (var k = first; k < regionFrames; k++)
        {
            if (!covered[k])
            {
                continue;
            }

            if (k - last > maxGap)
            {
                break;
            }

            last = k;
            hits++;
        }

        var span = last - first + 1;
        if (span * frameMs < MinMs || hits < span * MinCoverage)
        {
            return null;
        }

        // "Previously on…" is said before the first clip: a start this close counts from the top.
        var startMs = first * frameMs < 5_000 ? 0 : (long)(first * frameMs);
        var stopMs = (long)((last + 1) * frameMs);
        return new Intro(startMs, intro is { } known ? Math.Min(stopMs, known.StartMs) : stopMs);
    }
}
