using System.Globalization;
using System.Text.RegularExpressions;

namespace Nitrate.Companion.Lines;

/// <summary>Reads an SRT file (what the server hands any text subtitle over as) into spoken lines.</summary>
public static partial class SubtitleText
{
    /// <summary>
    /// The file's lines in time order: styling tags, sound descriptions ("[door slams]",
    /// "(music)") and music notes dropped, a speaker's name kept off ("JOHN: hello" → "hello").
    /// </summary>
    public static IReadOnlyList<LineCue> Parse(string srt)
    {
        var cues = new List<LineCue>();
        var lines = srt.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n').Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            if (!lines[i].Contains("-->", StringComparison.Ordinal))
            {
                continue;
            }

            var parts = lines[i].Split("-->", 2);
            var start = Ms(parts[0]);
            var end = Ms(parts[1]);
            var body = new List<string>();
            while (i + 1 < lines.Length && !string.IsNullOrWhiteSpace(lines[i + 1]))
            {
                body.Add(lines[++i]);
            }

            var raw = string.Join(' ', body);
            var text = Clean(raw);
            if (start is not null && end is not null && text.Length > 0)
            {
                cues.Add(new LineCue(start.Value, end.Value, text, MarksOf(raw)));
            }
        }

        return cues.OrderBy(c => c.StartMs).ToList();
    }

    /// <summary>What's said in one cue, without tags, sound descriptions or a speaker label.</summary>
    public static string Clean(string text)
    {
        var t = Tags().Replace(text, string.Empty);
        t = Sounds().Replace(t, " ");
        t = t.Replace("♪", " ", StringComparison.Ordinal).Replace("♫", " ", StringComparison.Ordinal);
        t = Speaker().Replace(t, string.Empty);
        t = t.Replace(" - ", " ", StringComparison.Ordinal).TrimStart('-', ' ');
        return Spaces().Replace(t, " ").Trim();
    }

    /// <summary>How a cue is marked: ♪/♫ anywhere = sung; every word inside &lt;i&gt; = italic.</summary>
    public static CueMarks MarksOf(string raw)
    {
        var marks = CueMarks.None;
        if (raw.Contains('♪', StringComparison.Ordinal) || raw.Contains('♫', StringComparison.Ordinal))
        {
            marks |= CueMarks.Sung;
        }

        // Italic: nothing said outside <i>…</i> (or {\i1}…).
        var outside = Italics().Replace(raw, " ");
        if (Italics().IsMatch(raw) && Clean(outside).Length == 0)
        {
            marks |= CueMarks.Italic;
        }

        return marks;
    }

    private static long? Ms(string s)
    {
        var m = Time().Match(s);
        if (!m.Success)
        {
            return null;
        }

        long Part(int g) => m.Groups[g].Success && m.Groups[g].Value.Length > 0 ? long.Parse(m.Groups[g].Value, CultureInfo.InvariantCulture) : 0;
        return (Part(1) * 3_600_000) + (Part(2) * 60_000) + (Part(3) * 1000) + Part(4);
    }

    [GeneratedRegex(@"(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{3})")]
    private static partial Regex Time();

    [GeneratedRegex(@"<[^>]+>|\{\\[^}]*\}")]
    private static partial Regex Tags();

    [GeneratedRegex(@"\[[^\]]*\]|\([^)]*\)")]
    private static partial Regex Sounds();

    [GeneratedRegex(@"(^|\s)[A-Z][A-Z .'-]{1,24}:\s")]
    private static partial Regex Speaker();

    [GeneratedRegex(@"<i>.*?</i>|\{\\i1\}.*?(\{\\i0?\}|$)", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex Italics();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();
}
