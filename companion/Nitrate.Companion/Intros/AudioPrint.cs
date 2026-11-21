using System.Numerics;

namespace Nitrate.Companion.Intros;

/// <summary>
/// A compact fingerprint of a stretch of sound, and the longest stretch two fingerprints share.
/// Each frame (2048 samples at 8 kHz, every 512) becomes 32 bits: whether the energy difference
/// between neighbouring frequency bands (300–2000 Hz, log-spaced) rose or fell since the previous
/// frame. The same music encoded twice gives nearly the same bits; unrelated sound differs in half.
/// </summary>
public static class AudioPrint
{
    /// <summary>The sample rate the audio is read at.</summary>
    public const int SampleRate = 8000;

    /// <summary>Samples per frame.</summary>
    public const int FrameSize = 2048;

    /// <summary>Samples between frames.</summary>
    public const int Hop = 512;

    /// <summary>Milliseconds between frames.</summary>
    public const double FrameMs = 1000.0 * Hop / SampleRate;

    /// <summary>Two frames match when at most this many of their 32 bits differ (unrelated: about 16).</summary>
    public const int MaxBitErrors = 10;

    /// <summary>A shared stretch carries on over gaps of up to this many unmatched frames (about a second).</summary>
    public const int MaxGap = 16;

    /// <summary>At least this share of a shared stretch's frames must match.</summary>
    public const double MinDensity = 0.4;

    private const int Bands = 33;
    private const double LowHz = 300;
    private const double HighHz = 2000;

    /// <summary>The fingerprint of mono 16-bit samples at <see cref="SampleRate"/>.</summary>
    public static uint[] Of(ReadOnlySpan<short> samples)
    {
        var frames = samples.Length < FrameSize ? 0 : 1 + ((samples.Length - FrameSize) / Hop);
        if (frames < 2)
        {
            return Array.Empty<uint>();
        }

        var edges = BandEdges();
        var window = new double[FrameSize];
        for (var i = 0; i < FrameSize; i++)
        {
            window[i] = 0.5 - (0.5 * Math.Cos(2 * Math.PI * i / (FrameSize - 1)));
        }

        var re = new double[FrameSize];
        var im = new double[FrameSize];
        var previous = new double[Bands];
        var current = new double[Bands];
        var print = new uint[frames - 1];
        for (var f = 0; f < frames; f++)
        {
            var start = f * Hop;
            for (var i = 0; i < FrameSize; i++)
            {
                re[i] = samples[start + i] * window[i];
                im[i] = 0;
            }

            Fft(re, im);
            for (var b = 0; b < Bands; b++)
            {
                double e = 0;
                for (var k = edges[b]; k < edges[b + 1]; k++)
                {
                    e += (re[k] * re[k]) + (im[k] * im[k]);
                }

                current[b] = Math.Log(1e-9 + e);
            }

            if (f > 0)
            {
                uint bits = 0;
                for (var b = 0; b < Bands - 1; b++)
                {
                    var d = (current[b] - current[b + 1]) - (previous[b] - previous[b + 1]);
                    if (d > 0)
                    {
                        bits |= 1u << b;
                    }
                }

                print[f - 1] = bits;
            }

            (previous, current) = (current, previous);
        }

        return print;
    }

    /// <summary>
    /// The longest stretch [a] and [b] share, as frame ranges in each (end exclusive), or null when
    /// they share nothing at least [minFrames] long.
    /// </summary>
    public static (int AStart, int AEnd, int BStart, int BEnd)? LongestShared(uint[] a, uint[] b, int minFrames)
    {
        (int, int, int, int)? best = null;
        var bestLength = minFrames - 1;
        for (var shift = -(b.Length - 1); shift < a.Length; shift++)
        {
            var i0 = Math.Max(0, shift);
            var j0 = i0 - shift;
            var length = Math.Min(a.Length - i0, b.Length - j0);
            if (length <= bestLength)
            {
                continue;
            }

            var hits = new List<int>();
            void Close()
            {
                if (hits.Count == 0 || hits[^1] - hits[0] + 1 <= bestLength || hits.Count < (hits[^1] - hits[0] + 1) * MinDensity)
                {
                    return;
                }

                // Chance matches just outside the shared part can stretch it: it starts and ends where
                // matches are dense.
                var first = 0;
                while (first < hits.Count && Dense(hits, first, 1) < EdgeHits)
                {
                    first++;
                }

                var end = hits.Count - 1;
                while (end > first && Dense(hits, end, -1) < EdgeHits)
                {
                    end--;
                }

                if (first >= end)
                {
                    return;
                }

                var run = hits[end] - hits[first] + 1;
                if (run > bestLength)
                {
                    bestLength = run;
                    best = (i0 + hits[first], i0 + hits[end] + 1, j0 + hits[first], j0 + hits[end] + 1);
                }
            }

            for (var k = 0; k < length; k++)
            {
                if (BitOperations.PopCount(a[i0 + k] ^ b[j0 + k]) > MaxBitErrors)
                {
                    continue;
                }

                if (hits.Count > 0 && k - hits[^1] > MaxGap)
                {
                    Close();
                    hits.Clear();
                }

                hits.Add(k);
            }

            Close();
        }

        return best;
    }

    /// <summary>A shared stretch's ends need this many matches among the <see cref="EdgeWindow"/> frames beside them.</summary>
    private const int EdgeHits = 6;

    private const int EdgeWindow = 8;

    /// <summary>How many of [hits] fall within <see cref="EdgeWindow"/> frames of hits[index], going [direction].</summary>
    private static int Dense(List<int> hits, int index, int direction)
    {
        var count = 0;
        for (var h = index; h >= 0 && h < hits.Count && Math.Abs(hits[h] - hits[index]) < EdgeWindow; h += direction)
        {
            count++;
        }

        return count;
    }

    private static int[] BandEdges()
    {
        var edges = new int[Bands + 1];
        for (var b = 0; b <= Bands; b++)
        {
            var hz = LowHz * Math.Pow(HighHz / LowHz, (double)b / Bands);
            edges[b] = (int)Math.Round(hz * FrameSize / SampleRate);
        }

        return edges;
    }

    /// <summary>In-place radix-2 FFT (length a power of two).</summary>
    private static void Fft(double[] re, double[] im)
    {
        var n = re.Length;
        for (int i = 1, j = 0; i < n; i++)
        {
            var bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1)
            {
                j ^= bit;
            }

            j ^= bit;
            if (i < j)
            {
                (re[i], re[j]) = (re[j], re[i]);
                (im[i], im[j]) = (im[j], im[i]);
            }
        }

        for (var len = 2; len <= n; len <<= 1)
        {
            var angle = -2 * Math.PI / len;
            double wr = Math.Cos(angle), wi = Math.Sin(angle);
            for (var i = 0; i < n; i += len)
            {
                double cr = 1, ci = 0;
                for (var k = 0; k < len / 2; k++)
                {
                    var a = i + k;
                    var b = a + (len / 2);
                    var tr = (re[b] * cr) - (im[b] * ci);
                    var ti = (re[b] * ci) + (im[b] * cr);
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    var nr = (cr * wr) - (ci * wi);
                    ci = (cr * wi) + (ci * wr);
                    cr = nr;
                }
            }
        }
    }
}
