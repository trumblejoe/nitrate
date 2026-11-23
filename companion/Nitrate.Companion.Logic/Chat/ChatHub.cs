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

    /// <summary>
    /// Adds a message to a room and wakes everyone waiting there. With an <paramref name="audience"/>,
    /// only those people get it (someone who joins the room later never sees it).
    /// </summary>
    public ChatMessage Post(string roomId, Guid userId, string userName, ChatKind kind, string? text, long? positionMs, IReadOnlyCollection<Guid>? audience = null)
    {
        var body = Check(kind, text);
        var now = _time.GetUtcNow();
        lock (_lock)
        {
            Sweep(now);
            var room = RoomFor(roomId, now);
            var recent = room.Messages.Count(m => m.Message.UserId == userId && now - m.Message.SentAt < BurstWindow);
            if (recent >= BurstLimit)
            {
                throw new ChatRejectedException("You're sending messages too fast.", tooFast: true);
            }
        }

        return Deliver(roomId, new ChatMessage(0, userId, userName, kind, body, positionMs, now), audience);
    }

    /// <summary>
    /// Hands a message already accepted elsewhere (a note pinned in a live session) to a room's
    /// <paramref name="audience"/> (null: everyone in the room), numbered as the room's next.
    /// </summary>
    public ChatMessage Deliver(string roomId, ChatMessage message, IReadOnlyCollection<Guid>? audience)
    {
        var now = _time.GetUtcNow();
        TaskCompletionSource waiting;
        lock (_lock)
        {
            Sweep(now);
            var room = RoomFor(roomId, now);
            message = message with { Seq = ++room.Latest };
            room.Messages.Add(new Entry(message, audience?.ToHashSet()));
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

    /// <summary>Something about the room changed besides its messages (notes brought in): wakes everyone waiting.</summary>
    public void Bump(string roomId)
    {
        var now = _time.GetUtcNow();
        TaskCompletionSource waiting;
        lock (_lock)
        {
            var room = RoomFor(roomId, now);
            room.Brought++;
            room.LastUsed = now;
            waiting = room.Changed;
            room.Changed = NewSignal();
        }

        waiting.TrySetResult();
    }

    /// <summary>The message as it will be kept: trimmed, and within the rules.</summary>
    /// <exception cref="ChatRejectedException">Empty, too long or not one emoji.</exception>
    public static string Check(ChatKind kind, string? text)
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

        return body;
    }

    /// <summary>
    /// Messages after <paramref name="after"/> (a negative number: the recent backlog). With none
    /// yet, waits up to <paramref name="wait"/> for one before answering with an empty page (or
    /// with the room's <see cref="ChatPage.Brought"/> moved on). Only messages for
    /// <paramref name="viewer"/> (in their audience, or sent to everyone) are in the page.
    /// </summary>
    public async Task<ChatPage> WaitAsync(string roomId, long after, TimeSpan wait, CancellationToken cancellationToken, Guid? viewer = null)
    {
        Task changed;
        lock (_lock)
        {
            var room = RoomFor(roomId, _time.GetUtcNow());
            var page = PageOf(room, after, viewer);
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
            return PageOf(RoomFor(roomId, _time.GetUtcNow()), after, viewer);
        }
    }

    /// <summary>Answers every waiting request now, as if its wait ran out (the logic is being swapped out).</summary>
    public void WakeAll()
    {
        List<TaskCompletionSource> waiting;
        lock (_lock)
        {
            waiting = _rooms.Values.Select(r => r.Changed).ToList();
            foreach (var room in _rooms.Values)
            {
                room.Changed = NewSignal();
            }
        }

        waiting.ForEach(w => w.TrySetResult());
    }

    /// <summary>The rooms, for the next logic (so message numbers carry on).</summary>
    public ChatState ExportState()
    {
        lock (_lock)
        {
            return new ChatState(_rooms.Select(r => new ChatRoomState(
                r.Key,
                r.Value.Messages.Select(m => new ChatEntryState(m.Message, m.Audience?.ToList())).ToList(),
                r.Value.Latest,
                r.Value.Brought,
                r.Value.LastUsed)).ToList());
        }
    }

    /// <summary>Takes the rooms the previous logic had.</summary>
    public void ImportState(ChatState state)
    {
        lock (_lock)
        {
            foreach (var r in state.Rooms)
            {
                var room = new Room { LastUsed = r.LastUsed, Changed = NewSignal(), Latest = r.Latest, Brought = r.Brought };
                room.Messages.AddRange(r.Messages.Select(m => new Entry(m.Message, m.Audience?.ToHashSet())));
                _rooms[r.Id] = room;
            }
        }
    }

    private static TaskCompletionSource NewSignal() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private static ChatPage PageOf(Room room, long after, Guid? viewer)
    {
        var mine = room.Messages.Where(m => viewer is not { } v || m.Audience is null || m.Audience.Contains(v));
        var messages = after < 0
            ? mine.TakeLast(Backlog).Select(m => m.Message).ToList()
            : mine.Where(m => m.Message.Seq > after).Select(m => m.Message).ToList();
        return new ChatPage(messages, room.Latest, room.Brought);
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

    private sealed record Entry(ChatMessage Message, HashSet<Guid>? Audience);

    private sealed class Room
    {
        public List<Entry> Messages { get; } = new();

        public long Latest { get; set; }

        public long Brought { get; set; }

        public DateTimeOffset LastUsed { get; set; }

        public required TaskCompletionSource Changed { get; set; }
    }
}
