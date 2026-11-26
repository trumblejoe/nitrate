using Nitrate.Companion.TestClipsCode;
using Xunit;

namespace Nitrate.Companion.Tests;

public class TestClipTests
{
    [Fact]
    public void EveryCheckClipShipsAndOpens()
    {
        var clips = TestClipCatalog.All();
        Assert.Equal(10, clips.Count);
        Assert.Equal(4, clips.Count(c => c.Kind == TestClipKind.Picture));
        // The playback test's disc-like profile 7 clip: big (disc-like bitrate), Matroska, not for the TV check.
        var heavy = Assert.Single(clips, c => c.Kind == TestClipKind.Playback);
        Assert.Equal("dolby_vision_7_fel_heavy", heavy.Id);
        Assert.True(heavy.Size > 10_000_000);
        Assert.Equal("video/x-matroska", heavy.ContentType);
        Assert.All(clips, c => Assert.True(c.Size > 100_000, c.Id));
        var hdr10 = TestClipCatalog.Open("hdr10");
        Assert.NotNull(hdr10);
        Assert.Equal("video/mp4", hdr10.Value.ContentType);
        hdr10.Value.Stream.Dispose();
        Assert.Null(TestClipCatalog.Open("../Plugin"));
    }
}
