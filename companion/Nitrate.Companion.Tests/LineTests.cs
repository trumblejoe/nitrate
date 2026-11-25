using Nitrate.Companion.Lines;
using Xunit;

namespace Nitrate.Companion.Tests;

public class LineTests
{
    private static readonly Guid Film = Guid.NewGuid();
    private static readonly Guid Show = Guid.NewGuid();

    private const string Srt = """
        1
        00:01:00,000 --> 00:01:02,000
        <i>Something's in the water.</i>

        2
        00:01:03,000 --> 00:01:05,500
        BRODY: You're gonna need
        a bigger boat.

        3
        00:01:06,000 --> 00:01:07,000
        [engine roars]

        4
        00:01:08,000 --> 00:01:09,000
        - Get back!
        - Now!
        """;

    [Fact]
    public void ReadsWhatsSaid()
    {
        var cues = SubtitleText.Parse(Srt);
        Assert.Equal(3, cues.Count);
        Assert.Equal("Something's in the water.", cues[0].Text);
        Assert.Equal(new LineCue(63_000, 65_500, "You're gonna need a bigger boat."), cues[1]);
        Assert.Equal("Get back! Now!", cues[2].Text);
    }

    [Fact]
    public void FindsLinesByWhatsTyped()
    {
        using var index = new LineIndex(":memory:");
        index.Replace(Film, "v1", new[] { ("eng", SubtitleText.Parse(Srt)) });
        index.Replace(Show, "v1", new[] { ("eng", (IReadOnlyList<LineCue>)new[] { new LineCue(5_000, 6_000, "We need a boat by Friday.") }) });

        var hits = index.Search("gonna need a bigger b", 10, _ => true);
        var hit = Assert.Single(hits);
        Assert.Equal(Film, hit.ItemId);
        Assert.Equal(63_000, hit.StartMs);
        Assert.Equal("Something's in the water.", hit.Before);
        Assert.Equal("Get back! Now!", hit.After);

        // Accents and case don't matter; word order does.
        Assert.Single(index.Search("YOU’RE GONNA", 10, _ => true));
        Assert.Empty(index.Search("boat bigger", 10, _ => true));
        Assert.Equal(2, index.Search("boat", 10, _ => true).Count);
        Assert.Empty(index.Search("!!!", 10, _ => true));
    }

    [Fact]
    public void OnlyTitlesTheCallerCanSee()
    {
        using var index = new LineIndex(":memory:");
        index.Replace(Film, "v1", new[] { ("eng", SubtitleText.Parse(Srt)) });
        Assert.Empty(index.Search("bigger boat", 10, id => id != Film));
    }

    [Fact]
    public void OneHitPerMomentAcrossLanguages()
    {
        using var index = new LineIndex(":memory:");
        IReadOnlyList<LineCue> en = new[] { new LineCue(1_000, 2_000, "Hello there") };
        IReadOnlyList<LineCue> en2 = new[] { new LineCue(1_200, 2_000, "Hello there") };
        index.Replace(Film, "v1", new[] { ("eng", en), ("eng-sdh", en2) });
        Assert.Single(index.Search("hello", 10, _ => true));
    }

    [Fact]
    public void ReindexesOnlyWhenChanged()
    {
        using var index = new LineIndex(":memory:");
        Assert.True(index.NeedsIndex(Film, "v1"));
        index.Replace(Film, "v1", new[] { ("eng", SubtitleText.Parse(Srt)) });
        Assert.False(index.NeedsIndex(Film, "v1"));
        Assert.True(index.NeedsIndex(Film, "v2"));
        index.Replace(Film, "v2", new[] { ("eng", (IReadOnlyList<LineCue>)new[] { new LineCue(0, 1, "Fresh words") }) });
        Assert.Empty(index.Search("bigger boat", 10, _ => true));
        Assert.Single(index.Search("fresh", 10, _ => true));
        Assert.Equal((1, 1L), index.Count());
        index.Remove(Film);
        Assert.Equal((0, 0L), index.Count());
        Assert.Empty(index.Search("fresh", 10, _ => true));
    }

    [Fact]
    public void SongsAndItalicsAreMarked()
    {
        var cues = SubtitleText.Parse("""
            1
            00:00:01,000 --> 00:00:03,000
            ♪ Hold me closer ♪

            2
            00:00:04,000 --> 00:00:06,000
            <i>Somewhere far away</i>

            3
            00:00:07,000 --> 00:00:09,000
            <i>He said</i> stop.
            """);
        Assert.Equal(new[] { CueMarks.Sung, CueMarks.Italic, CueMarks.None }, cues.Select(c => c.Marks));
        Assert.Equal("Hold me closer", cues[0].Text);
    }

    [Fact]
    public void ALanguagesLinesBetweenTimesWithTheirMarks()
    {
        using var index = new LineIndex(":memory:");
        Assert.False(index.Indexed(Film));
        IReadOnlyList<LineCue> en = new[] { new LineCue(1_000, 2_000, "One"), new LineCue(5_000, 6_000, "Two", CueMarks.Sung), new LineCue(9_000, 9_500, "Three") };
        IReadOnlyList<LineCue> fr = new[] { new LineCue(1_000, 2_000, "Un") };
        index.Replace(Film, "v1", new (string?, IReadOnlyList<LineCue>)[] { ("fre", fr), ("eng", en) });
        Assert.True(index.Indexed(Film));
        var cues = index.Cues(Film, 1_000, 9_000);
        Assert.Equal(new[] { "One", "Two" }, cues.Select(c => c.Cue.Text));
        Assert.All(cues, c => Assert.Equal("eng", c.Language));
        Assert.Equal(CueMarks.Sung, cues[1].Cue.Marks);
    }

    [Fact]
    public void AnIndexFromBeforeMarksGainsTheColumn()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-lines-" + Guid.NewGuid().ToString("N") + ".db");
        using (var old = new Microsoft.Data.Sqlite.SqliteConnection("Data Source=" + path + ";Pooling=False"))
        {
            old.Open();
            using var cmd = old.CreateCommand();
            cmd.CommandText = "CREATE TABLE cues(id INTEGER PRIMARY KEY, item TEXT NOT NULL, seq INTEGER NOT NULL, start INTEGER NOT NULL, finish INTEGER NOT NULL, lang TEXT, text TEXT NOT NULL);"
                + "CREATE TABLE items(item TEXT PRIMARY KEY, version TEXT NOT NULL, lines INTEGER NOT NULL);"
                + $"INSERT INTO cues(item, seq, start, finish, lang, text) VALUES ('{Film:N}', 1, 1000, 2000, 'eng', 'Old line');"
                + $"INSERT INTO items VALUES ('{Film:N}', 'v1', 1);";
            cmd.ExecuteNonQuery();
        }

        using var index = new LineIndex(path);
        var cue = Assert.Single(index.Cues(Film, 0, 10_000)).Cue;
        Assert.Equal(CueMarks.None, cue.Marks);
    }
}

public class CachedSubtitleTests
{
    [Fact]
    public void AssScriptsReadLikeSrt()
    {
        const string script = """
            [Script Info]
            Title: x

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,Hello, there.\NHow are you?
            Dialogue: 0,0:01:02.25,0:01:04.00,Default,,0,0,0,,{\i1}A voice off screen{\i0}
            Dialogue: 0,0:01:05.00,0:01:06.00,Default,,0,0,0,,{\p1}m 0 0 l 10 0 10 10{\p0}
            Comment: 0,0:01:07.00,0:01:08.00,Default,,0,0,0,,not said
            """;
        var cues = Nitrate.Companion.Lines.SubtitleText.ParseAny(script, ".ass");
        Assert.Equal(2, cues.Count);
        Assert.Equal((1_500L, 3_000L, "Hello, there. How are you?"), (cues[0].StartMs, cues[0].EndMs, cues[0].Text));
        Assert.Equal(62_250, cues[1].StartMs);
        Assert.True(cues[1].Marks.HasFlag(Nitrate.Companion.Lines.CueMarks.Italic));
        // SRT and WebVTT read as SRT.
        Assert.Single(Nitrate.Companion.Lines.SubtitleText.ParseAny("WEBVTT\n\n00:01.000 --> 00:02.000\nHi there\n", ".vtt"));
    }

    [Fact]
    public void OnlyAFreshCachedCopyIsUsed()
    {
        var dir = Path.Combine(Path.GetTempPath(), "nitrate-subs-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            IEnumerable<string> Candidates(int index) => new[] { "srt", "ass" }.Select(e => Path.Combine(dir, $"{index}.{e}"));
            var modified = DateTime.UtcNow.AddHours(-1);
            Assert.Null(Nitrate.Companion.Lines.LineIndexJob.CachedCopy(Candidates, 3, modified));
            File.WriteAllText(Path.Combine(dir, "3.ass"), string.Empty);
            Assert.Null(Nitrate.Companion.Lines.LineIndexJob.CachedCopy(Candidates, 3, modified));
            File.WriteAllText(Path.Combine(dir, "3.ass"), "[Events]");
            Assert.Equal(Path.Combine(dir, "3.ass"), Nitrate.Companion.Lines.LineIndexJob.CachedCopy(Candidates, 3, modified));
            // Older than the media file: extracted from an earlier version of it.
            File.SetLastWriteTimeUtc(Path.Combine(dir, "3.ass"), modified.AddMinutes(-5));
            Assert.Null(Nitrate.Companion.Lines.LineIndexJob.CachedCopy(Candidates, 3, modified));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }
}
