using Nitrate.Companion.Timing;
using Xunit;

namespace Nitrate.Companion.Tests;

public class TimingTests
{
    private static ProbedPacket A(long pts, long dur = 32) => new(1, pts, pts, dur);

    private static ProbedPacket V(long dts, long dur = 42) => new(0, dts + 80, dts, dur);

    private static readonly ProbedStream Video = new(0, "video", 0, 23.976, 23.976);

    [Fact]
    public void AFileInStepHasNothingToSay()
    {
        var audio = new ProbedStream(1, "audio", 20);
        var packets = Enumerable.Range(0, 300).Select(i => A(20 + (i * 32))).Concat(Enumerable.Range(0, 200).Select(i => V(i * 42))).ToList();
        Assert.Empty(TimingFinder.Find(new[] { Video, audio }, packets));
    }

    [Fact]
    public void TheSoundStartingLateIsNamedWithItsOffset()
    {
        var issue = Assert.Single(TimingFinder.Find(new[] { Video, new ProbedStream(1, "audio", 420) }, Array.Empty<ProbedPacket>()));
        Assert.Equal("start-offset", issue.Kind);
        Assert.Equal(420, issue.AmountMs);
        Assert.Contains("0.42 s after the picture", issue.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void GapsAndBackwardsTimestampsAreFound()
    {
        var packets = new List<ProbedPacket> { A(0), A(32), A(64), A(1_064), A(1_096), A(900) };
        var issues = TimingFinder.Find(new[] { Video, new ProbedStream(1, "audio", 0) }, packets);
        var gap = Assert.Single(issues, i => i.Kind == "audio-gap");
        Assert.Equal(968, gap.AmountMs);
        Assert.Equal(64, gap.AtMs);
        Assert.Single(issues, i => i.Kind == "audio-backwards");
    }

    [Fact]
    public void AVariableFrameRateIsNamed()
    {
        var phone = new ProbedStream(0, "video", 0, 29.2, 60);
        Assert.Contains(TimingFinder.Find(new[] { phone }, Array.Empty<ProbedPacket>()), i => i.Kind == "variable-frame-rate");
    }

    [Fact]
    public void FfprobesAnswerIsRead()
    {
        const string json = """
            {"packets":[{"stream_index":1,"pts_time":"0.021333","dts_time":"0.021333","duration_time":"0.032000"}],
             "streams":[{"index":0,"codec_type":"video","start_time":"0.000000","avg_frame_rate":"24000/1001","r_frame_rate":"24000/1001"},
                        {"index":1,"codec_type":"audio","start_time":"0.021333","avg_frame_rate":"0/0","r_frame_rate":"0/0"}]}
            """;
        var (streams, packets) = TimingFinder.Parse(json);
        Assert.Equal(2, streams.Count);
        Assert.Equal(21, streams[1].StartMs);
        Assert.Null(streams[1].AverageFps);
        Assert.Equal(23.976, streams[0].AverageFps!.Value, 3);
        Assert.Equal(32, Assert.Single(packets).DurationMs);
    }

    [Fact]
    public void TheStoreKeepsOneResultPerFileVersion()
    {
        var store = new TimingStore(null);
        var id = Guid.NewGuid();
        Assert.True(store.Needs(id, "a|1"));
        store.Put(id, new TimingResult("a|1", new[] { new TimingIssue("start-offset", null, 400, "x") }, DateTimeOffset.UtcNow));
        Assert.False(store.Needs(id, "a|1"));
        Assert.True(store.Needs(id, "a|2"));
        Assert.Single(store.WithIssues());
        store.KeepOnly(new HashSet<Guid>());
        Assert.Empty(store.WithIssues());
    }
}
