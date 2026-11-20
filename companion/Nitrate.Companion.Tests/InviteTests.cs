using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Together;
using Xunit;

namespace Nitrate.Companion.Tests;

public class InviteTests
{
    private readonly FakeTimeProvider _time = new(DateTimeOffset.FromUnixTimeMilliseconds(1_000));
    private readonly Guid _joe = Guid.NewGuid();
    private readonly Guid _sam = Guid.NewGuid();
    private readonly Guid _kim = Guid.NewGuid();

    [Fact]
    public void InvitesReachOnlyThoseWhoCanReceiveThem()
    {
        var hub = new InviteHub(_time);
        // Kim is private: the invite is dropped, and Joe can't tell.
        hub.Send(_joe, "joe", new[] { _sam, _kim, _joe }, "g1", "m1", "Nitrate", to => to != _kim);
        var sams = hub.For(_sam);
        Assert.Single(sams);
        Assert.Equal("joe", sams[0].FromName);
        Assert.Equal("g1", sams[0].GroupId);
        Assert.Empty(hub.For(_kim));
        Assert.Empty(hub.For(_joe));
    }

    [Fact]
    public void ANewerInviteToTheSameGroupReplacesTheOlderAndInvitesLapse()
    {
        var hub = new InviteHub(_time);
        hub.Send(_joe, "joe", new[] { _sam }, "g1", null, null, _ => true);
        _time.Advance(TimeSpan.FromMinutes(5));
        hub.Send(_joe, "joe", new[] { _sam }, "g1", "m2", "Second", _ => true);
        Assert.Equal("Second", Assert.Single(hub.For(_sam)).ItemName);
        _time.Advance(InviteHub.Keep + TimeSpan.FromMinutes(1));
        Assert.Empty(hub.For(_sam));
    }

    [Fact]
    public void OnlyTheInviteeDismisses()
    {
        var hub = new InviteHub(_time);
        hub.Send(_joe, "joe", new[] { _sam }, "g1", null, null, _ => true);
        var id = hub.For(_sam)[0].Id;
        Assert.False(hub.Dismiss(_joe, id));
        Assert.True(hub.Dismiss(_sam, id));
        Assert.Empty(hub.For(_sam));
    }

    [Fact]
    public void AGroupIsNeeded() =>
        Assert.Throws<ArgumentException>(() => new InviteHub(_time).Send(_joe, "joe", new[] { _sam }, " ", null, null, _ => true));
}
