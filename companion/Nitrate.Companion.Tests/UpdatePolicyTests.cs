using Nitrate.Companion.Updates;
using Xunit;

namespace Nitrate.Companion.Tests;

public class UpdatePolicyTests
{
    private static readonly DateTime Night = new(2026, 11, 21, 4, 30, 0);

    [Fact]
    public void RestartsOnlyAtNightWithAnUpdateWaitingAndNothingPlaying()
    {
        Assert.True(UpdatePolicy.ShouldRestart(true, true, Night, 4, 0));
        Assert.False(UpdatePolicy.ShouldRestart(false, true, Night, 4, 0)); // switched off
        Assert.False(UpdatePolicy.ShouldRestart(true, false, Night, 4, 0)); // nothing waiting
        Assert.False(UpdatePolicy.ShouldRestart(true, true, Night, 4, 1)); // someone's watching
        Assert.False(UpdatePolicy.ShouldRestart(true, true, Night.AddHours(2), 4, 0)); // past the window
        Assert.False(UpdatePolicy.ShouldRestart(true, true, Night.AddHours(-1), 4, 0)); // before it
    }

    [Fact]
    public void AWindowCanRunPastMidnight()
    {
        Assert.True(UpdatePolicy.ShouldRestart(true, true, new DateTime(2026, 11, 22, 0, 30, 0), 23, 0));
        Assert.False(UpdatePolicy.ShouldRestart(true, true, new DateTime(2026, 11, 22, 1, 30, 0), 23, 0));
    }

    [Fact]
    public void AnUpdateIsWaitingOnlyWhenANewerCopyIsInstalledBesideTheRunningOne()
    {
        Assert.True(UpdatePolicy.UpdateWaiting(new[] { (new Version(0, 1, 0, 62), true), (new Version(0, 1, 0, 70), false) }));
        Assert.False(UpdatePolicy.UpdateWaiting(new[] { (new Version(0, 1, 0, 62), true) }));
        Assert.False(UpdatePolicy.UpdateWaiting(new[] { (new Version(0, 1, 0, 62), true), (new Version(0, 1, 0, 54), false) }));
    }

    [Fact]
    public void TheWaitingVersionIsTheNewestCopyAboveTheRunningOne()
    {
        Assert.Equal(new Version(0, 1, 0, 72), UpdatePolicy.WaitingVersion(new[] { (new Version(0, 1, 0, 62), true), (new Version(0, 1, 0, 70), false), (new Version(0, 1, 0, 72), false) }));
        Assert.Null(UpdatePolicy.WaitingVersion(new[] { (new Version(0, 1, 0, 62), true), (new Version(0, 1, 0, 54), false) }));
    }

    [Fact]
    public void RestartNowOnlyFinishesAWaitingUpdateAndNeverStopsSomethingPlaying()
    {
        Assert.Equal(RestartAnswer.Restarting, UpdatePolicy.CanRestartNow(true, 0));
        Assert.Equal(RestartAnswer.SomethingPlaying, UpdatePolicy.CanRestartNow(true, 2));
        Assert.Equal(RestartAnswer.NothingWaiting, UpdatePolicy.CanRestartNow(false, 0));
    }
}
