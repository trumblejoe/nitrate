namespace Nitrate.Companion.Credits;

/// <summary>A scene in the credits: the credits start, and where the scene after them starts.</summary>
/// <param name="CreditsStartMs">Where this stretch of credits starts.</param>
/// <param name="SceneStartMs">Where the scene after it starts.</param>
/// <param name="AfterCredits">The last one (after the credits), rather than during them.</param>
public record CreditSkip(long CreditsStartMs, long SceneStartMs, bool AfterCredits);

/// <summary>
/// Finds scenes in the credits from small greyscale frames of a title's last part, the way the
/// apps read scrub thumbnails (core CreditScenes.fromFrames / looksLikeCredits): long runs of
/// credits frames (mostly black, crisp white text) with pictures after them. Keep the two in step.
/// </summary>
public static class CreditFinder
{
    /// <summary>A scene this long at least, after its credits.</summary>
    public const long MinSceneMs = 15_000;

    /// <summary>Credits at least this long.</summary>
    public const long MinCreditsMs = 60_000;

    /// <summary>Credits start in the title's last part, from this share of its length.</summary>
    public const double CreditsFrom = 0.7;

    /// <summary>
    /// The skips in [frames] (greyscale bytes, one every [intervalMs] from [startMs]) of a title
    /// [runtimeMs] long.
    /// </summary>
    public static IReadOnlyList<CreditSkip> Find(long runtimeMs, long startMs, IReadOnlyList<byte[]> frames, long intervalMs)
    {
        if (runtimeMs <= 0 || intervalMs <= 0 || frames.Count == 0)
        {
            return Array.Empty<CreditSkip>();
        }

        var credits = frames.Select(LooksLikeCredits).ToArray();
        var minCredits = Math.Max(3, (int)(MinCreditsMs / intervalMs));
        var minScene = Math.Max(1, (int)(MinSceneMs / intervalMs)) + 1;
        var from = (long)(runtimeMs * CreditsFrom);
        long At(int i) => startMs + (i * intervalMs);

        var runs = new List<(int Start, int End, bool Credits)>();
        var start = 0;
        for (var i = 1; i <= credits.Length; i++)
        {
            if (i == credits.Length || credits[i] != credits[start])
            {
                runs.Add((start, i, credits[start]));
                start = i;
            }
        }

        var creditRuns = runs.Where(r => r.Credits && r.End - r.Start >= minCredits && At(r.Start) >= from).ToList();
        var skips = new List<CreditSkip>();
        for (var k = 0; k < creditRuns.Count; k++)
        {
            var run = creditRuns[k];
            var next = k + 1 < creditRuns.Count ? creditRuns[k + 1].Start : credits.Length;
            var scene = Enumerable.Range(run.End, Math.Max(0, next - run.End)).Count(i => !credits[i]);
            if (scene >= minScene)
            {
                skips.Add(new CreditSkip(At(run.Start), At(run.End), k == creditRuns.Count - 1));
            }
        }

        return skips;
    }

    /// <summary>Credits: almost all black with crisp near-white marks (a dark scene has soft mid-tones).</summary>
    public static bool LooksLikeCredits(byte[] frame)
    {
        if (frame.Length == 0)
        {
            return false;
        }

        int dark = 0, bright = 0, mid = 0;
        foreach (var v in frame)
        {
            if (v < 40)
            {
                dark++;
            }
            else if (v > 170)
            {
                bright++;
            }
            else
            {
                mid++;
            }
        }

        double n = frame.Length;
        return dark / n > 0.72 && bright / n < 0.22 && mid / n < 0.18;
    }
}
