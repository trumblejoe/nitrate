using System.Globalization;
using System.Text.Json;

namespace Nitrate.Companion.Timing;

/// <summary>One thing wrong with a file's timing, for Library health.</summary>
/// <param name="Kind">"start-offset", "audio-gap", "video-gap", "audio-backwards", "video-backwards" or "variable-frame-rate".</param>
/// <param name="AtMs">Where in the file (null: the whole file).</param>
/// <param name="AmountMs">How big (the offset, the gap; null when it doesn't apply).</param>
/// <param name="Text">In plain words.</param>
public record TimingIssue(string Kind, long? AtMs, long? AmountMs, string Text);

/// <summary>A stream's start as ffprobe reports it.</summary>
/// <param name="Index">The stream's index in the file.</param>
/// <param name="Type">"video" or "audio" (others are ignored).</param>
/// <param name="StartMs">When its first packet plays.</param>
/// <param name="AverageFps">Its average frame rate (video).</param>
/// <param name="BaseFps">Its base frame rate (video): far from the average means a variable frame rate.</param>
public record ProbedStream(int Index, string Type, long? StartMs, double? AverageFps = null, double? BaseFps = null);

/// <summary>One packet: which stream, when it plays (and is decoded), and how long it lasts.</summary>
public record ProbedPacket(int Stream, long? PtsMs, long? DtsMs, long? DurationMs);

/// <summary>
/// Reads ffprobe's answer about the start of a file (its streams' start times and the packets of
/// the first few minutes) for what puts sound and picture out of step in players: the sound
/// starting well before or after the picture (players and the server's converting don't all
/// handle it), gaps in the sound or picture, timestamps going backwards, and a variable frame rate.
/// </summary>
public static class TimingFinder
{
    /// <summary>A start offset smaller than this (100 ms) isn't worth naming.</summary>
    public const long OffsetMs = 100;

    /// <summary>A hole in the sound longer than this (300 ms) is a gap.</summary>
    public const long AudioGapMs = 300;

    /// <summary>A hole in the picture longer than this (half a second) is a gap.</summary>
    public const long VideoGapMs = 500;

    /// <summary>At most this many gaps of each kind are named.</summary>
    public const int MaxGaps = 3;

    /// <summary>How much of the start of a file is read (five minutes).</summary>
    public const long ReadMs = 5 * 60_000;

    /// <summary>The issues in what ffprobe reported (the first video and the first audio stream).</summary>
    public static IReadOnlyList<TimingIssue> Find(IReadOnlyList<ProbedStream> streams, IReadOnlyList<ProbedPacket> packets)
    {
        var issues = new List<TimingIssue>();
        var video = streams.FirstOrDefault(s => s.Type == "video");
        var audio = streams.FirstOrDefault(s => s.Type == "audio");
        if (video?.StartMs is { } vs && audio?.StartMs is { } aStart)
        {
            var offset = aStart - vs;
            if (Math.Abs(offset) >= OffsetMs)
            {
                issues.Add(new TimingIssue(
                    "start-offset", null, offset,
                    $"The sound starts {Seconds(Math.Abs(offset))} s {(offset > 0 ? "after" : "before")} the picture. Most players allow for it, but some (and the server's converting, in places) don't, which leaves them out of step by that much."));
            }
        }

        if (audio != null)
        {
            Gaps(packets.Where(p => p.Stream == audio.Index).ToList(), useDts: false, AudioGapMs, "audio-gap", "the sound", issues);
            Backwards(packets.Where(p => p.Stream == audio.Index).Select(p => p.PtsMs).ToList(), "audio-backwards", "the sound", issues);
        }

        if (video != null)
        {
            Gaps(packets.Where(p => p.Stream == video.Index).ToList(), useDts: true, VideoGapMs, "video-gap", "the picture", issues);
            // Video packets come in decoding order: their decode times must only go forward.
            Backwards(packets.Where(p => p.Stream == video.Index).Select(p => p.DtsMs).ToList(), "video-backwards", "the picture", issues);
            if (video.AverageFps is > 0 and var avg && video.BaseFps is > 0 and var b && Math.Abs(avg - b) / b > 0.05)
            {
                issues.Add(new TimingIssue(
                    "variable-frame-rate", null, null,
                    $"The picture's frame rate varies (on average {avg.ToString("0.###", CultureInfo.InvariantCulture)} fps against {b.ToString("0.###", CultureInfo.InvariantCulture)}), as phone recordings and some web videos do. Players can drift on it; converting it to a steady rate fixes that."));
            }
        }

        return issues;
    }

    private static void Gaps(IReadOnlyList<ProbedPacket> list, bool useDts, long minimum, string kind, string what, List<TimingIssue> issues)
    {
        var found = 0;
        for (var i = 1; i < list.Count && found < MaxGaps; i++)
        {
            var prev = list[i - 1];
            var at = useDts ? list[i].DtsMs : list[i].PtsMs;
            var before = useDts ? prev.DtsMs : prev.PtsMs;
            if (at is not { } now || before is not { } then)
            {
                continue;
            }

            var gap = now - (then + (prev.DurationMs ?? 0));
            if (gap >= minimum)
            {
                found++;
                issues.Add(new TimingIssue(kind, then, gap, $"A {Seconds(gap)} s gap in {what} at {Clock(then)}: sound and picture can come apart there."));
            }
        }
    }

    private static void Backwards(IReadOnlyList<long?> times, string kind, string what, List<TimingIssue> issues)
    {
        long? last = null;
        foreach (var t in times)
        {
            if (t is not { } now)
            {
                continue;
            }

            if (last is { } then && now < then - 1)
            {
                issues.Add(new TimingIssue(kind, now, then - now, $"The timing of {what} goes backwards at {Clock(now)}, a fault in how the file was made: players can lose step there."));
                return;
            }

            last = now;
        }
    }

    /// <summary>Reads ffprobe's JSON (-show_entries stream=…:packet=…).</summary>
    public static (IReadOnlyList<ProbedStream> Streams, IReadOnlyList<ProbedPacket> Packets) Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var streams = new List<ProbedStream>();
        if (doc.RootElement.TryGetProperty("streams", out var s))
        {
            foreach (var e in s.EnumerateArray())
            {
                streams.Add(new ProbedStream(
                    e.TryGetProperty("index", out var i) ? i.GetInt32() : -1,
                    e.TryGetProperty("codec_type", out var ty) ? ty.GetString() ?? string.Empty : string.Empty,
                    Ms(e, "start_time"),
                    Rate(e, "avg_frame_rate"),
                    Rate(e, "r_frame_rate")));
            }
        }

        var packets = new List<ProbedPacket>();
        if (doc.RootElement.TryGetProperty("packets", out var p))
        {
            foreach (var e in p.EnumerateArray())
            {
                packets.Add(new ProbedPacket(e.TryGetProperty("stream_index", out var si) ? si.GetInt32() : -1, Ms(e, "pts_time"), Ms(e, "dts_time"), Ms(e, "duration_time")));
            }
        }

        return (streams, packets);
    }

    private static long? Ms(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String && double.TryParse(v.GetString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var d)
            ? (long)Math.Round(d * 1000)
            : null;

    private static double? Rate(JsonElement e, string name)
    {
        if (!e.TryGetProperty(name, out var v) || v.GetString() is not { } text)
        {
            return null;
        }

        var parts = text.Split('/');
        return parts.Length == 2 && double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var n) && double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var d) && d > 0 && n > 0
            ? n / d
            : null;
    }

    private static string Seconds(long ms) => (ms / 1000.0).ToString("0.0#", CultureInfo.InvariantCulture);

    private static string Clock(long ms)
    {
        var t = TimeSpan.FromMilliseconds(ms);
        return t.Hours > 0 ? t.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture) : t.ToString(@"m\:ss", CultureInfo.InvariantCulture);
    }
}
