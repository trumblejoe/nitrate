using Nitrate.Companion.TestClipsCode;
using Xunit;

namespace Nitrate.Companion.Tests;

public class TestClipTests
{
    [Fact]
    public void EveryCheckClipShipsAndOpens()
    {
        var clips = TestClipCatalog.All();
        Assert.Equal(9, clips.Count);
        Assert.Equal(4, clips.Count(c => c.Kind == TestClipKind.Picture));
        Assert.All(clips, c => Assert.True(c.Size > 100_000, c.Id));
        var hdr10 = TestClipCatalog.Open("hdr10");
        Assert.NotNull(hdr10);
        Assert.Equal("video/mp4", hdr10.Value.ContentType);
        hdr10.Value.Stream.Dispose();
        Assert.Null(TestClipCatalog.Open("../Plugin"));
    }
}
