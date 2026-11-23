namespace Nitrate.Companion.Chat;

/// <summary>What a chat message is.</summary>
public enum ChatKind
{
    /// <summary>Something typed.</summary>
    Text,

    /// <summary>A quick reaction (one emoji), easy to send with a TV remote.</summary>
    Reaction,
}

/// <summary>One message in a room.</summary>
/// <param name="Seq">Its place in the room, counting up from 1.</param>
/// <param name="UserId">Who sent it.</param>
/// <param name="UserName">Their name when they sent it.</param>
/// <param name="Kind">Text or a reaction.</param>
/// <param name="Text">The message.</param>
/// <param name="PositionMs">Where the sender was in what's playing, if anything.</param>
/// <param name="SentAt">When the server got it.</param>
/// <param name="NoteId">The note it is kept as (a message in a live session is one note, delivered here at once), if any.</param>
/// <param name="ItemId">The film or episode the note is pinned to, if any.</param>
public record ChatMessage(long Seq, Guid UserId, string UserName, ChatKind Kind, string Text, long? PositionMs, DateTimeOffset SentAt, Guid? NoteId = null, Guid? ItemId = null);

/// <summary>Messages after a point, and the newest number in the room.</summary>
/// <param name="Messages">Oldest first.</param>
/// <param name="Latest">The room's newest message number (0 when empty): ask for messages after it next.</param>
/// <param name="Brought">Counts up whenever someone brings (or stops bringing) earlier notes into the session: ask for them again.</param>
public record ChatPage(IReadOnlyList<ChatMessage> Messages, long Latest, long Brought = 0);
