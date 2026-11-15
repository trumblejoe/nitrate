using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Chat;
using Xunit;

namespace Nitrate.Companion.Tests;

public class ChatHubTests
{
    private static readonly Guid Ann = Guid.NewGuid();
    private static readonly Guid Bob = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly ChatHub _hub;

    public ChatHubTests() => _hub = new ChatHub(_time);

    [Fact]
    public async Task MessagesAreNumberedAndReadAfterAPoint()
    {
        _hub.Post("r", Ann, "ann", ChatKind.Text, " hello ", 1000);
        _hub.Post("r", Bob, "bob", ChatKind.Reaction, "😂", 2000);
        var all = await _hub.WaitAsync("r", -1, TimeSpan.Zero, default);
        Assert.Equal(new long[] { 1, 2 }, all.Messages.Select(m => m.Seq));
        Assert.Equal("hello", all.Messages[0].Text);
        Assert.Equal(2, all.Latest);

        var after = await _hub.WaitAsync("r", 1, TimeSpan.Zero, default);
        Assert.Equal("😂", Assert.Single(after.Messages).Text);
        Assert.Empty((await _hub.WaitAsync("other", 0, TimeSpan.Zero, default)).Messages);
    }

    [Fact]
    public async Task AWaitingReaderWakesOnANewMessage()
    {
        var waiting = _hub.WaitAsync("r", 0, TimeSpan.FromSeconds(30), default);
        Assert.False(waiting.IsCompleted);
        _hub.Post("r", Ann, "ann", ChatKind.Text, "now", null);
        var page = await waiting.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal("now", Assert.Single(page.Messages).Text);
    }

    [Fact]
    public async Task AWaitEndsEmptyWhenNothingComes()
    {
        var waiting = _hub.WaitAsync("r", 0, TimeSpan.FromSeconds(30), default);
        _time.Advance(TimeSpan.FromSeconds(31));
        var page = await waiting.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(page.Messages);
        Assert.Equal(0, page.Latest);
    }

    [Fact]
    public void BadMessagesAreRejected()
    {
        Assert.Throws<ChatRejectedException>(() => _hub.Post("r", Ann, "ann", ChatKind.Text, "   ", null));
        Assert.Throws<ChatRejectedException>(() => _hub.Post("r", Ann, "ann", ChatKind.Text, new string('a', ChatHub.MaxLength + 1), null));
        Assert.Throws<ChatRejectedException>(() => _hub.Post("r", Ann, "ann", ChatKind.Reaction, "lol what", null));
        _hub.Post("r", Ann, "ann", ChatKind.Reaction, "👍🏽", null);
    }

    [Fact]
    public void SendingTooFastIsSlowedDownPerPerson()
    {
        for (var i = 0; i < ChatHub.BurstLimit; i++)
        {
            _hub.Post("r", Ann, "ann", ChatKind.Text, $"m{i}", null);
        }

        var e = Assert.Throws<ChatRejectedException>(() => _hub.Post("r", Ann, "ann", ChatKind.Text, "more", null));
        Assert.True(e.TooFast);
        _hub.Post("r", Bob, "bob", ChatKind.Text, "I can still talk", null);
        _time.Advance(ChatHub.BurstWindow);
        _hub.Post("r", Ann, "ann", ChatKind.Text, "and now me", null);
    }

    [Fact]
    public async Task OnlyRecentMessagesAreKeptAndLateJoinersGetABacklog()
    {
        for (var i = 0; i < ChatHub.Kept + 20; i++)
        {
            _hub.Post("r", Guid.NewGuid(), "x", ChatKind.Text, $"m{i}", null);
        }

        var joined = await _hub.WaitAsync("r", -1, TimeSpan.Zero, default);
        Assert.Equal(ChatHub.Backlog, joined.Messages.Count);
        Assert.Equal(ChatHub.Kept + 20, joined.Latest);
        Assert.Equal(ChatHub.Kept, (await _hub.WaitAsync("r", 0, TimeSpan.Zero, default)).Messages.Count);
    }

    [Fact]
    public async Task UnusedRoomsAreDropped()
    {
        _hub.Post("old", Ann, "ann", ChatKind.Text, "hi", null);
        _time.Advance(ChatHub.RoomLifetime + TimeSpan.FromMinutes(1));
        _hub.Post("new", Ann, "ann", ChatKind.Text, "hi", null);
        Assert.Empty((await _hub.WaitAsync("old", -1, TimeSpan.Zero, default)).Messages);
    }
}
