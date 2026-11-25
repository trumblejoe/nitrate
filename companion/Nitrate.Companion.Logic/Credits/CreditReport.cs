namespace Nitrate.Companion.Credits;

/// <summary>
/// The admins' "Credits check" for one title (<c>GET /Nitrate/Credits/{itemId}/Report</c>): what
/// the credits reader decided and why, one row per frame of the scanned part, for tuning. No
/// pictures and nothing about anyone's viewing: only the frames' features and the title's
/// subtitle timings.
/// </summary>
/// <param name="ItemId">The title.</param>
/// <param name="RuntimeMs">Its length.</param>
/// <param name="StartMs">Where the scanned part starts.</param>
/// <param name="IntervalMs">Time between rows in readings from before (one a second); 0 when rows carry their own times (one per keyframe, denser at the boundaries).</param>
/// <param name="Checked">When the title was read (null: not yet).</param>
/// <param name="Format">The result's format (<see cref="CreditResult.CurrentFormat"/> when read the current way).</param>
/// <param name="Skips">The scenes in the credits found.</param>
/// <param name="OutroStartMs">Where the final credits start, if it ends on them.</param>
/// <param name="OutroRejected">People marked the final credits wrong: they're not handed out.</param>
/// <param name="Dialogue">The subtitle lines used, as [startMs, endMs, marks (1 sung, 2 italic)]; null without subtitles indexed.</param>
/// <param name="Vetoed">Stretches that looked like credits and weren't counted, and why.</param>
/// <param name="Columns">What each row's values are.</param>
/// <param name="Rows">One per frame: its time (s, to the hundredth), the rounded features, then the verdict code.</param>
/// <param name="Reading">The title is being read now: ask again in a few minutes.</param>
/// <param name="Note">Why there are no rows, when there aren't.</param>
/// <param name="ReadMs">How long reading the title took (null: read before this was kept).</param>
/// <param name="Frames">Frames decoded and measured.</param>
/// <param name="ReadFromMs">How far back it read (where decoding started).</param>
/// <param name="Hardware">The hardware decoding used (ffmpeg's <c>-hwaccel</c>: vaapi, cuda, d3d11va, videotoolbox); null for software.</param>
public record CreditReport(
    Guid ItemId,
    long RuntimeMs,
    long StartMs,
    long IntervalMs,
    DateTimeOffset? Checked,
    int Format,
    IReadOnlyList<CreditSkip> Skips,
    long? OutroStartMs,
    bool OutroRejected,
    IReadOnlyList<long[]>? Dialogue,
    IReadOnlyList<VetoedStretch> Vetoed,
    IReadOnlyList<string> Columns,
    IReadOnlyList<IReadOnlyList<object>> Rows,
    bool Reading = false,
    string? Note = null,
    long? ReadMs = null,
    int? Frames = null,
    long? ReadFromMs = null,
    string? Hardware = null)
{
    /// <summary>The rows' columns.</summary>
    public static readonly IReadOnlyList<string> ColumnNames = new[]
    {
        "t", "dark", "bright", "mid", "marks", "entropy", "sharp", "lines", "lineInk", "regular", "symmetry",
        "strokes", "strokeWidth", "saturation", "scrollDy", "scrollMad", "stillMad", "verdict",
    };

    /// <summary>
    /// The report for [itemId] from its kept [frames] (null: not read the current way yet, so
    /// [reading] says whether a read was started), its [result] and [dialogue].
    /// </summary>
    public static CreditReport Build(Guid itemId, long runtimeMs, CreditResult? result, CreditFrameSet? frames, IReadOnlyList<DialogueLine>? dialogue, bool reading = false, string? note = null)
    {
        if (frames == null)
        {
            return new CreditReport(
                itemId, runtimeMs, (long)(runtimeMs * CreditFinder.CreditsFrom), CreditScanJob.IntervalMs, result?.Checked, result?.Format ?? 0,
                result?.Skips ?? Array.Empty<CreditSkip>(), result?.OutroStartMs, result?.OutroRejected ?? false, null,
                Array.Empty<VetoedStretch>(), ColumnNames, Array.Empty<IReadOnlyList<object>>(), reading, note);
        }

        var features = frames.Frames();
        var timeline = frames.Timeline();
        var decision = CreditFinder.Decide(runtimeMs, features, timeline, dialogue);
        var rows = new List<IReadOnlyList<object>>(features.Count);
        for (var i = 0; i < features.Count; i++)
        {
            var f = features[i];
            rows.Add(new object[]
            {
                Math.Round(timeline.At(i) / 1000.0, 2),
                R(f.Dark), R(f.Bright), R(f.Mid), R(f.Marks), R(f.Entropy, 2), R(f.Sharp), f.Lines, R(f.LineInk), R(f.Regular), R(f.Symmetry),
                R(f.Strokes), R(f.StrokeWidth, 1), f.Saturation is { } s ? R(s, 0) : -1, f.ScrollDy, R(f.ScrollMad, 1), R(f.StillMad, 1),
                decision.Verdicts[i].ToString(),
            });
        }

        return new CreditReport(
            itemId, runtimeMs, frames.StartMs, frames.IntervalMs, result?.Checked, result?.Format ?? 0,
            decision.Skips, decision.OutroStartMs, result?.OutroRejected ?? false,
            dialogue?.Select(l => new[] { l.StartMs, l.EndMs, (l.Sung ? 1L : 0) | (l.Italic ? 2L : 0) }).ToList(),
            decision.Vetoed, ColumnNames, rows, reading, note,
            frames.ReadMs, frames.Decoded, frames.Times == null ? null : frames.StartMs, frames.Hardware);
    }

    private static double R(double value, int digits = 3) => Math.Round(value, digits);
}
