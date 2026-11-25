namespace Nitrate.Companion.Credits;

/// <summary>A scene in the credits: the credits start, and where the scene after them starts.</summary>
/// <param name="CreditsStartMs">Where this stretch of credits starts.</param>
/// <param name="SceneStartMs">Where the scene after it starts.</param>
/// <param name="AfterCredits">The last one (after the credits), rather than during them.</param>
/// <param name="Spoken">The title's subtitles have someone speaking in the scene: it's a scene, not more credits.</param>
public record CreditSkip(long CreditsStartMs, long SceneStartMs, bool AfterCredits, bool Spoken = false);

/// <summary>One subtitle line, as the dialogue vote sees it.</summary>
/// <param name="StartMs">When it starts.</param>
/// <param name="EndMs">When it ends.</param>
/// <param name="Sung">Marked as music (♪): lyrics, never dialogue.</param>
/// <param name="Italic">All in italics (lyrics, or a voice off screen).</param>
public record DialogueLine(long StartMs, long EndMs, bool Sung = false, bool Italic = false);

/// <summary>A stretch that looked like credits but was not counted, and why.</summary>
/// <param name="StartMs">Where it starts.</param>
/// <param name="EndMs">Where it ends.</param>
/// <param name="Why">In a few words.</param>
public record VetoedStretch(long StartMs, long EndMs, string Why);

/// <summary>What the reader decided about a title's last part.</summary>
/// <param name="Verdicts">One letter per frame (<see cref="CreditFinder"/>'s verdict codes).</param>
/// <param name="Skips">The scenes in the credits.</param>
/// <param name="OutroStartMs">Where the final credits start, if it ends on credits.</param>
/// <param name="Vetoed">Stretches not counted as credits.</param>
public record CreditDecision(string Verdicts, IReadOnlyList<CreditSkip> Skips, long? OutroStartMs, IReadOnlyList<VetoedStretch> Vetoed);

/// <summary>
/// Finds scenes in the credits from the features of a title's last part (<see cref="CreditFeatures"/>,
/// one frame a second on the server; the apps read their scrub thumbnails the same way, core
/// CreditScenes, keep the two in step): long runs of credits frames with pictures after them.
/// A frame is credits when it holds text (crisp, small strokes in lines on black) or is part of a
/// steady scroll, unless its marks are coloured (fire, lamps) or its brightness too spread to be
/// text on black; black frames right after credits count with them, and so do short gaps (a fade
/// between cards). With the title's subtitles indexed, a stretch with dialogue spread over it is
/// not credits, and a scene after the credits with dialogue is a scene for sure.
/// </summary>
/// <remarks>
/// Verdict codes: C text, S steady scroll, B black after credits, g short gap between credits
/// (all four count); b black elsewhere, o coloured marks, e brightness too spread, w text that
/// moves without scrolling, f a stretch with too little text in it, d dialogue over it, . picture.
/// </remarks>
public static class CreditFinder
{
    /// <summary>A scene this long at least, after its credits.</summary>
    public const long MinSceneMs = 15_000;

    /// <summary>Credits at least this long.</summary>
    public const long MinCreditsMs = 60_000;

    /// <summary>Credits start in the title's last part, from this share of its length.</summary>
    public const double CreditsFrom = 0.7;

    /// <summary>
    /// A scene in or after the credits is short: more pictures than this after a dark stretch means
    /// the film went on (a night or smoke-filled scene read as credits). Core's MAX_SCENE_MS.
    /// </summary>
    public const long MaxSceneMs = 6 * 60_000;

    /// <summary>The final stretch of credits (to the end, after any scene) is at least this long.</summary>
    public const long MinFinalCreditsMs = 15_000;

    /// <summary>A frame is mostly black: more than this share darker than <see cref="CreditFeatureReader.DarkBelow"/>…</summary>
    public const double DarkMin = 0.72;

    /// <summary>…less than this bright…</summary>
    public const double BrightMax = 0.22;

    /// <summary>…and less than this in between.</summary>
    public const double MidMax = 0.18;

    /// <summary>A black frame (card gap): this dark, and next to no marks.</summary>
    public const double BlankDark = 0.97;

    /// <summary>Text: at least this share of crisp edges…</summary>
    public const double SharpMin = 0.5;

    /// <summary>…this share of narrow runs (strokes)…</summary>
    public const double StrokesMin = 0.75;

    /// <summary>…and this share of its marks in text lines.</summary>
    public const double LineInkMin = 0.6;

    /// <summary>Sure text: two lines or more this alike in height, or…</summary>
    public const double RegularMin = 0.5;

    /// <summary>…one line or more this well centred (a card).</summary>
    public const double SymmetryMin = 0.85;

    /// <summary>Coloured marks (fire, lamps, a lit window): mean saturation above this is no credits.</summary>
    public const double SaturationMax = 40;

    /// <summary>Brightness spread wider than this (bits) is a scene, not text on black.</summary>
    public const double EntropyMax = 3.0;

    /// <summary>Gaps up to this long between credits frames (a fade between cards) count as credits.</summary>
    public const long GapMs = 3_000;

    /// <summary>A stretch of credits is at least this share text or scroll (not just black and gaps).</summary>
    public const double MinTextShare = 0.5;

    /// <summary>Dialogue vetoes a stretch from this many spoken lines in it…</summary>
    public const int DialogueVetoLines = 4;

    /// <summary>…spread over at least this long (lyrics sung over the credits come in a cluster shorter than this).</summary>
    public const long LyricClusterMs = 30_000;

    /// <summary>A stretch this much scroll is rolling credits, whatever is said over it.</summary>
    public const double ScrollSure = 0.5;

    /// <summary>Whether a verdict code counts as credits.</summary>
    public static bool Counts(char verdict) => verdict is 'C' or 'S' or 'B' or 'g';

    /// <summary>One frame's verdict on its own (the sequence rules come after, in <see cref="Classify"/>); 's' = scrolled, steadiness not known yet.</summary>
    public static char Verdict(CreditFeatures f)
    {
        if (!(f.Dark > DarkMin && f.Bright < BrightMax && f.Mid < MidMax))
        {
            return '.';
        }

        if (f.Dark >= BlankDark && f.Marks < CreditFeatureReader.MinMarks)
        {
            return 'b';
        }

        if (f.Marks < CreditFeatureReader.MinMarks)
        {
            return '.';
        }

        if (f.Saturation is { } sat && sat > SaturationMax)
        {
            return 'o';
        }

        if (f.Entropy > EntropyMax)
        {
            return 'e';
        }

        var text = f.Sharp >= SharpMin && f.Strokes >= StrokesMin && f.Lines >= 1 && f.LineInk >= LineInkMin;
        if (text && ((f.Lines >= 2 && f.Regular >= RegularMin) || f.Symmetry >= SymmetryMin))
        {
            return 'C';
        }

        if (text && (f.Still || f.Scrolled))
        {
            return 'C';
        }

        if (f.Scrolled)
        {
            return 's';
        }

        return text ? 'w' : '.';
    }

    /// <summary>Whether one frame, alone, looks like credits (or a black card between them).</summary>
    public static bool LooksLikeCredits(CreditFeatures f) => Verdict(f) is 'C' or 'b';

    /// <summary>Every frame's verdict ([frames] one every [intervalMs]), with the sequence rules: steady scroll, black after credits, short gaps.</summary>
    public static char[] Classify(IReadOnlyList<CreditFeatures> frames, long intervalMs)
    {
        var v = frames.Select(Verdict).ToArray();
        for (var i = 0; i < v.Length; i++)
        {
            if (v[i] == 's' || (v[i] == 'w' && frames[i].Scrolled))
            {
                v[i] = Steady(frames, i) ? 'S' : '.';
            }
        }

        BlackAfterCredits(v);
        var gap = (int)(GapMs / Math.Max(1, intervalMs));
        for (var i = 0; i < v.Length;)
        {
            if (Counts(v[i]))
            {
                i++;
                continue;
            }

            var start = i;
            while (i < v.Length && !Counts(v[i]))
            {
                i++;
            }

            if (start > 0 && i < v.Length && i - start <= gap)
            {
                for (var k = start; k < i; k++)
                {
                    v[k] = 'g';
                }
            }
        }

        BlackAfterCredits(v);
        return v;
    }

    /// <summary>
    /// What the reader makes of a title's last part: [frames] one every [intervalMs] from
    /// [startMs] of a title [runtimeMs] long, and [dialogue] (its subtitle lines, one language; null
    /// when it has none indexed: the pictures decide alone).
    /// </summary>
    public static CreditDecision Decide(long runtimeMs, long startMs, IReadOnlyList<CreditFeatures> frames, long intervalMs, IReadOnlyList<DialogueLine>? dialogue)
    {
        if (runtimeMs <= 0 || intervalMs <= 0 || frames.Count == 0)
        {
            return new CreditDecision(string.Empty, Array.Empty<CreditSkip>(), null, Array.Empty<VetoedStretch>());
        }

        var v = Classify(frames, intervalMs);
        long At(int i) => startMs + (i * intervalMs);
        var vetoed = new List<VetoedStretch>();
        foreach (var (start, end) in Runs(v))
        {
            var text = Enumerable.Range(start, end - start).Count(i => v[i] is 'C' or 'S');
            string? why = null;
            char code = 'f';
            if (text < (end - start) * MinTextShare)
            {
                why = "too little text in it";
            }
            else if (dialogue != null && Enumerable.Range(start, end - start).Count(i => frames[i].Scrolled) < (end - start) * ScrollSure)
            {
                var vote = DialogueVote(dialogue, At(start), At(end));
                if (vote.Veto)
                {
                    why = vote.Why;
                    code = 'd';
                }
            }

            if (why != null)
            {
                for (var i = start; i < end; i++)
                {
                    v[i] = code;
                }

                if ((end - start) * intervalMs >= MinCreditsMs / 2)
                {
                    vetoed.Add(new VetoedStretch(At(start), At(end), why));
                }
            }
        }

        var credits = v.Select(Counts).ToArray();
        var skips = Find(runtimeMs, startMs, credits, intervalMs);
        if (dialogue != null)
        {
            var runStarts = Runs(v).Select(r => At(r.Start)).ToList();
            skips = skips.Select(s =>
            {
                var sceneEnd = runStarts.Where(e => e > s.SceneStartMs).DefaultIfEmpty(runtimeMs).Min();
                return s with { Spoken = dialogue.Any(l => !l.Sung && l.StartMs >= s.SceneStartMs && l.StartMs < sceneEnd) };
            }).ToList();
        }

        return new CreditDecision(new string(v), skips, FinalCredits(runtimeMs, startMs, credits, intervalMs), vetoed);
    }

    /// <summary>
    /// Whether the [lines] said in [fromMs, toMs) make it a scene rather than credits: at least
    /// <see cref="DialogueVetoLines"/> spoken lines spread over <see cref="LyricClusterMs"/> or
    /// more. Lines marked as music (♪) never count; lines all in italics are lyrics (or a voice
    /// off screen) and don't veto; a few lines, or a cluster shorter than that, are a song over the
    /// credits.
    /// </summary>
    public static (bool Veto, string Why) DialogueVote(IReadOnlyList<DialogueLine> lines, long fromMs, long toMs)
    {
        var spoken = lines.Where(l => !l.Sung && l.StartMs >= fromMs && l.StartMs < toMs).OrderBy(l => l.StartMs).ToList();
        if (spoken.Count < DialogueVetoLines)
        {
            return (false, spoken.Count == 0 ? "no dialogue" : $"{spoken.Count} lines: a song at most");
        }

        if (spoken.All(l => l.Italic))
        {
            return (false, "lines all in italics: lyrics");
        }

        var spread = spoken[^1].StartMs - spoken[0].StartMs;
        if (spread < LyricClusterMs)
        {
            return (false, $"{spoken.Count} lines within {spread / 1000} s: a song");
        }

        return (true, $"{spoken.Count} lines of dialogue over {spread / 60_000}:{spread / 1000 % 60:00}");
    }

    /// <summary>
    /// The skips in [credits] (one verdict every [intervalMs] from [startMs]) of a title
    /// [runtimeMs] long: a run of credits at least <see cref="MinCreditsMs"/> long in the last part,
    /// with a scene of <see cref="MinSceneMs"/> to <see cref="MaxSceneMs"/> after it.
    /// </summary>
    public static IReadOnlyList<CreditSkip> Find(long runtimeMs, long startMs, IReadOnlyList<bool> credits, long intervalMs)
    {
        if (runtimeMs <= 0 || intervalMs <= 0 || credits.Count == 0)
        {
            return Array.Empty<CreditSkip>();
        }

        var minCredits = Math.Max(3, (int)(MinCreditsMs / intervalMs));
        var minScene = Math.Max(1, (int)(MinSceneMs / intervalMs)) + 1;
        var maxScene = (int)(MaxSceneMs / intervalMs);
        var from = (long)(runtimeMs * CreditsFrom);
        long At(int i) => startMs + (i * intervalMs);

        var runs = new List<(int Start, int End, bool Credits)>();
        var start = 0;
        for (var i = 1; i <= credits.Count; i++)
        {
            if (i == credits.Count || credits[i] != credits[start])
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
            var next = k + 1 < creditRuns.Count ? creditRuns[k + 1].Start : credits.Count;
            var scene = Enumerable.Range(run.End, Math.Max(0, next - run.End)).Count(i => !credits[i]);
            if (scene >= minScene && scene <= maxScene)
            {
                skips.Add(new CreditSkip(At(run.Start), At(run.End), k == creditRuns.Count - 1));
            }
        }

        return skips;
    }

    /// <summary>
    /// Where the final stretch of credits starts: credits that run to the title's end with no scene
    /// after them (a short logo or black at the very end is fine), from [credits] as for
    /// <see cref="Find"/>. Breaks shorter than a scene (a picture card) don't end it; a scene does,
    /// so a mid- or post-credits scene is never inside it. Null when the title doesn't end on credits.
    /// </summary>
    public static long? FinalCredits(long runtimeMs, long startMs, IReadOnlyList<bool> credits, long intervalMs)
    {
        if (runtimeMs <= 0 || intervalMs <= 0 || credits.Count == 0)
        {
            return null;
        }

        var minScene = Math.Max(1, (int)(MinSceneMs / intervalMs)) + 1;
        var from = (long)(runtimeMs * CreditsFrom);
        long At(int i) => startMs + (i * intervalMs);

        // Back from the end: past a short tail that isn't credits, then over credits and short breaks.
        var i = credits.Count - 1;
        var tail = 0;
        while (i >= 0 && !credits[i])
        {
            i--;
            tail++;
        }

        if (i < 0 || tail >= minScene)
        {
            return null;
        }

        var first = i;
        var gap = 0;
        for (; i >= 0; i--)
        {
            if (credits[i])
            {
                first = i;
                gap = 0;
            }
            else if (++gap >= minScene)
            {
                break;
            }
        }

        var startAt = At(first);
        var length = At(credits.Count - tail) - startAt;
        return startAt >= from && length >= MinFinalCreditsMs ? startAt : null;
    }

    // Rolling credits move the same way frame after frame: a neighbour scrolled by about as much.
    private static bool Steady(IReadOnlyList<CreditFeatures> frames, int i)
    {
        var dy = frames[i].ScrollDy;
        bool Near(int k) => k >= 0 && k < frames.Count && frames[k].ScrollDy > 0 && Math.Abs(frames[k].ScrollDy - dy) <= Math.Max(2, dy / 4);
        return dy > 0 && (Near(i - 1) || Near(i + 1));
    }

    // Black right after credits frames (between cards, or to the end) counts with them.
    private static void BlackAfterCredits(char[] v)
    {
        for (var i = 1; i < v.Length; i++)
        {
            if (v[i] == 'b' && Counts(v[i - 1]))
            {
                v[i] = 'B';
            }
        }
    }

    // Runs of counting verdicts, as (start, end exclusive).
    private static IEnumerable<(int Start, int End)> Runs(char[] v)
    {
        for (var i = 0; i < v.Length;)
        {
            if (!Counts(v[i]))
            {
                i++;
                continue;
            }

            var start = i;
            while (i < v.Length && Counts(v[i]))
            {
                i++;
            }

            yield return (start, i);
        }
    }
}
