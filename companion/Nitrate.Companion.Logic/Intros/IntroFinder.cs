namespace Nitrate.Companion.Intros;

/// <summary>Where an episode's intro is, in milliseconds from its start.</summary>
public record Intro(long StartMs, long EndMs);

/// <summary>
/// Finds each episode's intro from the opening minutes of a season's episodes: the longest
/// stretch of sound it shares with a neighbouring episode, between <see cref="MinMs"/> and
/// <see cref="MaxMs"/> long. Episodes are compared with the next and previous ones (and the one
/// after next when those find nothing), so one odd episode doesn't spoil the rest.
/// </summary>
public static class IntroFinder
{
    /// <summary>Shorter shared stretches are logos or stings, not intros.</summary>
    public const long MinMs = 15_000;

    /// <summary>Longer ones are shared footage (a two-parter, a recap), not intros.</summary>
    public const long MaxMs = 150_000;

    /// <summary>
    /// How much of each episode to read: 30% of it, at least 3 minutes and at most 10 (and never
    /// past its end).
    /// </summary>
    public static long SearchMs(long runtimeMs) => Math.Min(runtimeMs, Math.Clamp((long)(runtimeMs * 0.3), 3 * 60_000, 10 * 60_000));

    /// <summary>Each episode's intro (null: none found), by its place in [prints], in episode order.</summary>
    public static IReadOnlyList<Intro?> Find(IReadOnlyList<uint[]> prints)
    {
        var minFrames = (int)(MinMs / AudioPrint.FrameMs);
        var found = new Intro?[prints.Count];
        var pairs = new Dictionary<(int, int), (int AStart, int AEnd, int BStart, int BEnd)?>();
        (int, int, int, int)? Shared(int i, int j)
        {
            var key = (Math.Min(i, j), Math.Max(i, j));
            if (!pairs.TryGetValue(key, out var m))
            {
                m = AudioPrint.LongestShared(prints[key.Item1], prints[key.Item2], minFrames);
                pairs[key] = m;
            }

            if (m is not { } r)
            {
                return null;
            }

            // In i's own frames.
            return i == key.Item1 ? (r.AStart, r.AEnd, r.BStart, r.BEnd) : (r.BStart, r.BEnd, r.AStart, r.AEnd);
        }

        for (var i = 0; i < prints.Count; i++)
        {
            Intro? best = null;
            foreach (var j in new[] { i + 1, i - 1, i + 2, i - 2 })
            {
                if (j < 0 || j >= prints.Count || (best != null && Math.Abs(j - i) > 1))
                {
                    continue;
                }

                if (Shared(i, j) is not { } m)
                {
                    continue;
                }

                var intro = new Intro((long)(m.Item1 * AudioPrint.FrameMs), (long)(m.Item2 * AudioPrint.FrameMs));
                var length = intro.EndMs - intro.StartMs;
                if (length < MinMs || length > MaxMs)
                {
                    continue;
                }

                if (best == null || length > best.EndMs - best.StartMs)
                {
                    best = intro;
                }
            }

            found[i] = best;
        }

        return found;
    }
}
