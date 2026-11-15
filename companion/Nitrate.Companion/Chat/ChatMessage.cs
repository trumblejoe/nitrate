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
public record ChatMessage(long Seq, Guid UserId, string UserName, ChatKind Kind, string Text, long? PositionMs, DateTimeOffset SentAt);

/// <summary>Messages after a point, and the newest number in the room.</summary>
/// <param name="Messages">Oldest first.</param>
/// <param name="Latest">The room's newest message number (0 when empty): ask for messages after it next.</param>
public record ChatPage(IReadOnlyList<ChatMessage> Messages, long Latest);
