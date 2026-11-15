using System.Globalization;

namespace Nitrate.Companion.Chat;

/// <summary>Thrown when a message can't be accepted; the text says why.</summary>
public class ChatRejectedException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="ChatRejectedException"/> class.</summary>
    public ChatRejectedException(string message, bool tooFast = false)
        : base(message) => TooFast = tooFast;

    /// <summary>Gets a value indicating whether the sender should slow down (rather than change the message).</summary>
    public bool TooFast { get; }
}

/// <summary>
/// Chat rooms kept in memory: each holds its recent messages, numbered, and wakes everyone waiting
/// for new ones (long polling). Rooms nobody has used for a day are dropped. Who may use a room is
/// decided by the caller.
/// </summary>
public class ChatHub
{
    /// <summary>Longest message, in characters.</summary>
    public const int MaxLength = 500;

    /// <summary>Messages kept per room.</summary>
    public const int Kept = 300;

    /// <summary>Messages someone joining late gets.</summary>
    public const int Backlog = 50;

    /// <summary>Messages one person may send in <see cref="BurstWindow"/>.</summary>
    public const int BurstLimit = 6;

    /// <summary>The window for <see cref="BurstLimit"/>.</summary>
    public static readonly TimeSpan BurstWindow = TimeSpan.FromSeconds(5);

    /// <summary>How long an unused room is kept.</summary>
    public static readonly TimeSpan RoomLifetime = TimeSpan.FromDays(1);

    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<string, Room> _rooms = new(StringComparer.Ordinal);

    /// <summary>Initializes a new instance of the <see cref="ChatHub"/> class.</summary>
    public ChatHub(TimeProvider time) => _time = time;

    /// <summary>Adds a message to a room and wakes everyone waiting there.</summary>
    public ChatMessage Post(string roomId, Guid userId, string userName, ChatKind kind, string? text, long? positionMs)
    {
        var body = (text ?? string.Empty).Trim();
        if (body.Length == 0)
        {
            throw new ChatRejectedException("The message is empty.");
        }

        if (kind == ChatKind.Reaction && new StringInfo(body).LengthInTextElements > 2)
        {
            throw new ChatRejectedException("A reaction is a single emoji.");
        }

        if (body.Length > MaxLength)
        {
            throw new ChatRejectedException($"Messages are at most {MaxLength} characters.");
        }

        var now = _time.GetUtcNow();
        TaskCompletionSource waiting;
        ChatMessage message;
        lock (_lock)
        {
            Sweep(now);
            var room = RoomFor(roomId, now);
            var recent = room.Messages.Count(m => m.UserId == userId && now - m.SentAt < BurstWindow);
            if (recent >= BurstLimit)
            {
                throw new ChatRejectedException("You're sending messages too fast.", tooFast: true);
            }

            message = new ChatMessage(++room.Latest, userId, userName, kind, body, positionMs, now);
            room.Messages.Add(message);
            if (room.Messages.Count > Kept)
            {
                room.Messages.RemoveRange(0, room.Messages.Count - Kept);
            }

            room.LastUsed = now;
            waiting = room.Changed;
            room.Changed = NewSignal();
        }

        waiting.TrySetResult();
        return message;
    }

    /// <summary>
    /// Messages after <paramref name="after"/> (a negative number: the recent backlog). With none
    /// yet, waits up to <paramref name="wait"/> for one before answering with an empty page.
    /// </summary>
    public async Task<ChatPage> WaitAsync(string roomId, long after, TimeSpan wait, CancellationToken cancellationToken)
    {
        Task changed;
        lock (_lock)
        {
            var room = RoomFor(roomId, _time.GetUtcNow());
            var page = PageOf(room, after);
            if (page.Messages.Count > 0 || wait <= TimeSpan.Zero)
            {
                return page;
            }

            changed = room.Changed.Task;
        }

        try
        {
            await changed.WaitAsync(wait, _time, cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            // Nothing new in time: an empty page.
        }

        cancellationToken.ThrowIfCancellationRequested();
        lock (_lock)
        {
            return PageOf(RoomFor(roomId, _time.GetUtcNow()), after);
        }
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static ChatPage PageOf(Room room, long after)
    {
        var messages = after < 0
            ? room.Messages.TakeLast(Backlog).ToList()
            : room.Messages.Where(m => m.Seq > after).ToList();
        return new ChatPage(messages, room.Latest);
    }

    private Room RoomFor(string roomId, DateTimeOffset now)
    {
        if (!_rooms.TryGetValue(roomId, out var room))
        {
            room = new Room { LastUsed = now, Changed = NewSignal() };
            _rooms[roomId] = room;
        }

        return room;
    }

    private void Sweep(DateTimeOffset now)
    {
        foreach (var id in _rooms.Where(r => now - r.Value.LastUsed > RoomLifetime).Select(r => r.Key).ToList())
        {
            _rooms[id].Changed.TrySetResult();
            _rooms.Remove(id);
        }
    }

    private sealed class Room
    {
        public List<ChatMessage> Messages { get; } = new();

        public long Latest { get; set; }

        public DateTimeOffset LastUsed { get; set; }

        public required TaskCompletionSource Changed { get; set; }
    }
}
