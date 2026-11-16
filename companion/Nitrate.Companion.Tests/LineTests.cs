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
}
