namespace Nitrate.Companion.Credits;

/// <summary>
/// What the credits reader measures in one frame (and between it and the frame before it). The
/// same definitions as core's <c>CreditFeatures</c> (CreditScenes.kt): keep the two in step, and
/// docs/features/playback.md ("Scenes in the credits") with them.
/// </summary>
/// <param name="Dark">Share of pixels darker than <see cref="CreditFeatureReader.DarkBelow"/>.</param>
/// <param name="Bright">Share brighter than <see cref="CreditFeatureReader.BrightAbove"/>.</param>
/// <param name="Mid">Share in between.</param>
/// <param name="Marks">Share of "marks": pixels lighter than <see cref="CreditFeatureReader.MarkAbove"/> (text, even thin strokes softened by scaling).</param>
/// <param name="Entropy">Shannon entropy (bits) of the 32-bin brightness histogram: text on black is low, a lit scene high.</param>
/// <param name="Sharp">Of the neighbouring pixel pairs where a mark meets a non-mark, the share that jump by at least <see cref="CreditFeatureReader.EdgeJump"/>: text is crisp, glow and fire soft.</param>
/// <param name="Lines">Text lines: bands of rows holding marks, separated by empty rows, at most <see cref="CreditFeatureReader.LineMaxShare"/> of the height tall.</param>
/// <param name="LineInk">Share of the marks inside those bands (the rest are in taller blobs).</param>
/// <param name="Regular">How alike the lines' heights are (1 − their coefficient of variation, 0–1; one line counts as 1).</param>
/// <param name="Symmetry">How centred the lines are left to right (1 = every line's ends equally far from the middle).</param>
/// <param name="Strokes">Share of the rows' runs of marks that are at most <see cref="CreditFeatureReader.StrokeMax"/> wide (letters' strokes).</param>
/// <param name="StrokeWidth">The runs' mean width, in pixels.</param>
/// <param name="Saturation">Mean colourfulness of the marks (max of |U−128|, |V−128|; white ≈ 0, orange flame ≈ 60+); null without colour or marks.</param>
/// <param name="ScrollDy">Rows the picture moved up since the frame before (best match), 0 when it didn't scroll.</param>
/// <param name="ScrollMad">Mean brightness difference to the frame before at that shift (0 = identical).</param>
/// <param name="StillMad">Mean brightness difference to the frame before, unshifted.</param>
public record CreditFeatures(
    double Dark,
    double Bright,
    double Mid,
    double Marks,
    double Entropy,
    double Sharp,
    int Lines,
    double LineInk,
    double Regular,
    double Symmetry,
    double Strokes,
    double StrokeWidth,
    double? Saturation,
    int ScrollDy,
    double ScrollMad,
    double StillMad)
{
    /// <summary>Unchanged since the frame before (a card held on screen).</summary>
    public bool Still => StillMad <= CreditFeatureReader.StillMadMax;

    /// <summary>The frame before it moved up into this one (rolling credits).</summary>
    public bool Scrolled => ScrollDy > 0;
}

/// <summary>
/// Reads <see cref="CreditFeatures"/> from frames as they arrive (brightness, plus colour when it
/// has it), keeping only the frame before for the scroll test: no pictures are kept.
/// </summary>
public sealed class CreditFeatureReader
{
    /// <summary>Darker than this is black.</summary>
    public const int DarkBelow = 40;

    /// <summary>Brighter than this is bright.</summary>
    public const int BrightAbove = 170;

    /// <summary>Lighter than this is a mark (text, thin strokes softened by scaling included).</summary>
    public const int MarkAbove = 110;

    /// <summary>A crisp edge: a mark and its neighbour differ by at least this.</summary>
    public const int EdgeJump = 80;

    /// <summary>A text line is at most this share of the frame's height.</summary>
    public const double LineMaxShare = 0.12;

    /// <summary>A stroke is at most this many pixels wide at 320 across (scaled with the width, never under 3).</summary>
    public const int StrokeMax = 8;

    /// <summary>Unchanged: mean difference at most this.</summary>
    public const double StillMadMax = 1.5;

    /// <summary>Scrolled: the shifted match is at most this far off…</summary>
    public const double ScrollMadMax = 4.0;

    /// <summary>…and the unshifted one at least this many times worse (plus one).</summary>
    public const double ScrollGain = 2.0;

    /// <summary>Marks needed for any text test (a share of the frame).</summary>
    public const double MinMarks = 0.0005;

    private readonly int _width;
    private readonly int _height;
    private readonly double _maxShift;
    private byte[]? _previous;
    private double[]? _previousRows;

    /// <summary>Initializes a new instance of the <see cref="CreditFeatureReader"/> class for frames [width]×[height], frames [maxShift] of the height apart at most when scrolling (0.25 for one a second).</summary>
    public CreditFeatureReader(int width, int height, double maxShift = 0.25)
    {
        _width = width;
        _height = height;
        _maxShift = maxShift;
    }

    /// <summary>
    /// The features of the next frame: [luma] is width×height brightness; [u]/[v] (optional) its
    /// colour at half the width and height (yuv420p), each covering a 2×2 block.
    /// </summary>
    public CreditFeatures Next(ReadOnlySpan<byte> luma, ReadOnlySpan<byte> u = default, ReadOnlySpan<byte> v = default)
    {
        var w = _width;
        var h = _height;
        var n = w * h;
        int dark = 0, bright = 0, mid = 0, marks = 0;
        Span<int> histogram = stackalloc int[32];
        var rowMarks = new int[h];
        var rows = new double[h];
        long satSum = 0;
        var hasColour = u.Length >= (w / 2) * (h / 2) && v.Length >= (w / 2) * (h / 2);
        int sharp = 0, edges = 0;
        int runs = 0, shortRuns = 0;
        long runWidth = 0;
        var strokeMax = Math.Max(3, StrokeMax * w / 320);
        for (var y = 0; y < h; y++)
        {
            var row = y * w;
            long rowSum = 0;
            var run = 0;
            for (var x = 0; x < w; x++)
            {
                int p = luma[row + x];
                rowSum += p;
                histogram[p >> 3]++;
                if (p < DarkBelow)
                {
                    dark++;
                }
                else if (p > BrightAbove)
                {
                    bright++;
                }
                else
                {
                    mid++;
                }

                var mark = p > MarkAbove;
                if (mark)
                {
                    marks++;
                    rowMarks[y]++;
                    run++;
                    if (hasColour)
                    {
                        var c = ((y / 2) * (w / 2)) + Math.Min(x / 2, (w / 2) - 1);
                        satSum += Math.Max(Math.Abs(u[c] - 128), Math.Abs(v[c] - 128));
                    }
                }
                else if (run > 0)
                {
                    EndRun(run);
                    run = 0;
                }

                if (x + 1 < w)
                {
                    Edge(p, luma[row + x + 1]);
                }

                if (y + 1 < h)
                {
                    Edge(p, luma[row + w + x]);
                }
            }

            if (run > 0)
            {
                EndRun(run);
            }

            rows[y] = (double)rowSum / w;
        }

        void EndRun(int length)
        {
            runs++;
            runWidth += length;
            if (length <= strokeMax)
            {
                shortRuns++;
            }
        }

        void Edge(int a, int b)
        {
            if ((a > MarkAbove) != (b > MarkAbove))
            {
                edges++;
                if (Math.Abs(a - b) >= EdgeJump)
                {
                    sharp++;
                }
            }
        }

        double entropy = 0;
        foreach (var count in histogram)
        {
            if (count > 0)
            {
                var p = (double)count / n;
                entropy -= p * Math.Log2(p);
            }
        }

        // Text lines: bands of rows with marks in them.
        var inkMin = Math.Max(2, w / 100);
        var lineMax = Math.Max(1, (int)Math.Round(h * LineMaxShare));
        var heights = new List<int>();
        double offsets = 0;
        var lineMarks = 0;
        for (var y = 0; y < h;)
        {
            if (rowMarks[y] < inkMin)
            {
                y++;
                continue;
            }

            var top = y;
            var bandMarks = 0;
            while (y < h && rowMarks[y] >= inkMin)
            {
                bandMarks += rowMarks[y];
                y++;
            }

            var height = y - top;
            if (height <= lineMax)
            {
                heights.Add(height);
                lineMarks += bandMarks;
                int left = w, right = -1;
                for (var yy = top; yy < y; yy++)
                {
                    var row = yy * w;
                    for (var x = 0; x < w; x++)
                    {
                        if (luma[row + x] > MarkAbove)
                        {
                            left = Math.Min(left, x);
                            right = Math.Max(right, x);
                        }
                    }
                }

                offsets += Math.Abs(((left + right) / 2.0) - ((w - 1) / 2.0)) / (w / 2.0);
            }
        }

        var regular = 1.0;
        if (heights.Count >= 2)
        {
            var mean = heights.Average();
            var sd = Math.Sqrt(heights.Sum(x => (x - mean) * (x - mean)) / heights.Count);
            regular = Math.Clamp(1 - (sd / mean), 0, 1);
        }

        // Scroll: the best upward shift of the row means, checked over the whole frame.
        int scrollDy = 0;
        double scrollMad = 0, stillMad = 0;
        if (_previous is { } prev && _previousRows is { } prevRows)
        {
            stillMad = Mad(luma, prev, 0);
            var maxDy = (int)(h * _maxShift);
            var best = -1;
            var bestProfile = double.MaxValue;
            for (var dy = 1; dy <= maxDy && h - dy >= h / 4; dy++)
            {
                double d = 0;
                for (var y = 0; y < h - dy; y++)
                {
                    d += Math.Abs(rows[y] - prevRows[y + dy]);
                }

                d /= h - dy;
                if (d < bestProfile)
                {
                    bestProfile = d;
                    best = dy;
                }
            }

            if (best > 0 && marks >= MinMarks * n)
            {
                scrollMad = Mad(luma, prev, best);
                if (scrollMad <= ScrollMadMax && stillMad >= (ScrollGain * scrollMad) + 1)
                {
                    scrollDy = best;
                }
            }
        }

        _previous = luma.ToArray();
        _previousRows = rows;
        return new CreditFeatures(
            (double)dark / n,
            (double)bright / n,
            (double)mid / n,
            (double)marks / n,
            entropy,
            edges == 0 ? 0 : (double)sharp / edges,
            heights.Count,
            marks == 0 ? 0 : (double)lineMarks / marks,
            regular,
            heights.Count == 0 ? 0 : 1 - (offsets / heights.Count),
            runs == 0 ? 0 : (double)shortRuns / runs,
            runs == 0 ? 0 : (double)runWidth / runs,
            hasColour && marks > 0 ? (double)satSum / marks : null,
            scrollDy,
            scrollMad,
            stillMad);
    }

    // Mean |now(x, y) − before(x, y + dy)| over the rows both have.
    private double Mad(ReadOnlySpan<byte> now, byte[] before, int dy)
    {
        var w = _width;
        long sum = 0;
        var count = (_height - dy) * w;
        for (var i = 0; i < count; i++)
        {
            sum += Math.Abs(now[i] - before[i + (dy * w)]);
        }

        return count == 0 ? 0 : (double)sum / count;
    }
}
