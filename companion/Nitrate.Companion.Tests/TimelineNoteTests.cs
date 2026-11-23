using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

public class TimelineNoteTests
{
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Lee = Guid.NewGuid();
    private static readonly Guid Pat = Guid.NewGuid();
    private static readonly Guid Episode = Guid.NewGuid();
    private static readonly Guid Pair = Guid.NewGuid();
    private static readonly Guid Other = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-20T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));

    private static GroupMembers Group(Guid id, params Guid[] members) => new(id, "The Bear", members);

    [Fact]
    public void ANoteGoesToTheWritersGroupsAsTheyWereThen()
    {
        using var db = new CompanionDb(":memory:");
        var book = new TimelineNoteBook(db, _time);
        var note = book.Pin(Sam, "sam", Episode, 754_000, ChatKind.Text, "  that score  ", new[] { Group(Pair, Sam, Kim) });
        Assert.Equal("that score", note.Text);
        Assert.Equal(new[] { Sam, Kim }, Assert.Single(note.Shares).Members);

        // Kim was in the group: sees it, with only her own group named (never who else it went to).
        var seen = Assert.Single(book.VisibleTo(Kim, Episode, new[] { Group(Pair, Sam, Kim) }));
        Assert.Equal(754_000, seen.PositionMs);
        Assert.Equal(new[] { Pair }, seen.GroupIds);
        // Lee joined later: wasn't there when it was written.
        Assert.Empty(book.VisibleTo(Lee, Episode, new[] { Group(Pair, Sam, Kim, Lee) }));
        // Pat isn't in the group at all.
        Assert.Empty(book.VisibleTo(Pat, Episode, Array.Empty<GroupMembers>()));
        // The writer always sees their own.
        Assert.Single(book.VisibleTo(Sam, Episode, Array.Empty<GroupMembers>()));
    }

    [Fact]
    public void LeavingTheGroupEndsSeeingItsNotes()
    {
        using var db = new CompanionDb(":memory:");
        var book = new TimelineNoteBook(db, _time);
        book.Pin(Sam, "sam", Episode, 60_000, ChatKind.Reaction, "😂", new[] { Group(Pair, Sam, Kim) });
        // Kim left: her groups on the title no longer hold that one.
        Assert.Empty(book.VisibleTo(Kim, Episode, Array.Empty<GroupMembers>()));
        // Sam left (Kim's group goes on without him): his notes are no longer shown to her.
        Assert.Empty(book.VisibleTo(Kim, Episode, new[] { Group(Pair, Kim, Lee) }));
        // A different group of Kim's with Sam in it doesn't open notes written to another group.
        Assert.Empty(book.VisibleTo(Kim, Episode, new[] { Group(Other, Sam, Kim) }));
    }

    [Fact]
    public void OnlyMembersCanPinAndMessagesFollowTheChatRules()
    {
        using var db = new CompanionDb(":memory:");
        var book = new TimelineNoteBook(db, _time);
        Assert.Throws<ChatRejectedException>(() => book.Pin(Pat, "pat", Episode, 0, ChatKind.Text, "hi", new[] { Group(Pair, Sam, Kim) }));
        Assert.Throws<ChatRejectedException>(() => book.Pin(Sam, "sam", Episode, 0, ChatKind.Text, "hi", Array.Empty<GroupMembers>()));
        Assert.Throws<ChatRejectedException>(() => book.Pin(Sam, "sam", Episode, 0, ChatKind.Text, "   ", new[] { Group(Pair, Sam, Kim) }));
        Assert.Throws<ChatRejectedException>(() => book.Pin(Sam, "sam", Episode, 0, ChatKind.Reaction, "lol!", new[] { Group(Pair, Sam, Kim) }));
        Assert.Throws<ChatRejectedException>(() => book.Pin(Sam, "sam", Episode, 0, ChatKind.Text, new string('a', ChatHub.MaxLength + 1), new[] { Group(Pair, Sam, Kim) }));
        for (var i = 0; i < ChatHub.BurstLimit; i++)
        {
            book.Pin(Sam, "sam", Episode, i * 1_000, ChatKind.Reaction, "🔥", new[] { Group(Pair, Sam, Kim) });
        }

        var tooFast = Assert.Throws<ChatRejectedException>(() => book.Pin(Sam, "sam", Episode, 9_000, ChatKind.Reaction, "🔥", new[] { Group(Pair, Sam, Kim) }));
        Assert.True(tooFast.TooFast);
        _time.Advance(ChatHub.BurstWindow);
        book.Pin(Sam, "sam", Episode, 10_000, ChatKind.Reaction, "🔥", new[] { Group(Pair, Sam, Kim) });
        // Kept in the order of the title, not of writing.
        Assert.Equal(new[] { 0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 10_000L }, book.VisibleTo(Kim, Episode, new[] { Group(Pair, Sam, Kim) }).Select(n => n.PositionMs));
    }

    [Fact]
    public void OnlyTheWriterRemovesANoteAndOldMomentsReadAsNotes()
    {
        using var db = new CompanionDb(":memory:");
        var book = new TimelineNoteBook(db, _time);
        var note = book.Pin(Sam, "sam", Episode, 60_000, ChatKind.Text, "look", new[] { Group(Pair, Sam, Kim) });
        Assert.False(book.Delete(Kim, Episode, note.Id));
        Assert.Single(book.All(Episode));
        Assert.True(book.Delete(Sam, Episode, note.Id));
        Assert.Empty(book.All(Episode));

        var moment = TimelineNoteBook.FromMoment(new Moment(Guid.NewGuid(), Sam, "sam", Episode, 75_000, null, Pair, 1));
        Assert.True(moment.Moment);
        Assert.Equal(string.Empty, moment.Text);
        Assert.Equal(new[] { Pair }, moment.GroupIds);
    }
}
