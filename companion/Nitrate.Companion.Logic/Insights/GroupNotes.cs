using Nitrate.Companion.Chat;

namespace Nitrate.Companion.Insights;

/// <summary>Who is in a live (SyncPlay) session, as the server sees it.</summary>
public interface ILiveSessions
{
    /// <summary>
    /// The session's participants (user ids, <paramref name="caller"/> included) when the caller is
    /// watching in it on any of their devices; null when they aren't (or it's gone).
    /// </summary>
    IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller);

    /// <summary>What the session is playing now, if anything (the caller's own device first).</summary>
    Guid? Playing(Guid sessionId, Guid caller);
}

/// <summary>Not allowed: not in that session, or not in a watch group on the title.</summary>
public class NoteForbiddenException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="NoteForbiddenException"/> class.</summary>
    public NoteForbiddenException()
        : base("Not allowed.")
    {
    }
}

/// <summary>Notes someone brought into a live session ("Bring our earlier notes").</summary>
/// <param name="By">Who brought them (null: nobody has).</param>
/// <param name="Notes">The notes on the title asked about, earliest in it first.</param>
public record BroughtNotes(Guid? By, IReadOnlyList<NoteView> Notes);

/// <summary>Which group's notes are brought into a session ("Bring our earlier notes"), and by whom.</summary>
/// <param name="GroupId">The watch group.</param>
/// <param name="By">The participant who brought them.</param>
public record BroughtFlag(Guid GroupId, Guid By);

/// <summary>A live session's conversation on one title.</summary>
/// <param name="ChatId">The thread being written in the session (its group's current thread; null until someone writes).</param>
/// <param name="Along">The older thread replayed for everyone there, if someone picked one.</param>
/// <param name="By">Who picked it.</param>
/// <param name="Notes">That thread's messages, as the caller gets them (never kept for them unless they were there for them).</param>
public record SessionChatView(Guid? ChatId, Guid? Along, Guid? By, IReadOnlyList<NoteView> Notes);

/// <summary>
/// Group chat pinned to the timeline as the API serves it: one note per message, live sessions
/// getting each at once through the chat room (<see cref="ChatHub"/>, room per SyncPlay group, each
/// message only for its audience), and "Bring our earlier notes" (which group's notes a session
/// gets, while it lasts, kept in memory only).
/// </summary>
public class GroupNotes
{
    private readonly TimelineNoteBook _book;
    private readonly ChatHub _hub;
    private readonly ILiveSessions _live;
    private readonly Func<Guid, Guid, IReadOnlyList<GroupMembers>> _groupsHolding;
    private readonly Func<Guid, bool> _isPrivate;
    private readonly Func<bool> _keepLive;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, (BroughtFlag Flag, DateTimeOffset At)> _brought = new();
    private readonly Dictionary<(Guid Session, Guid Item), (Guid ChatId, Guid By, DateTimeOffset At)> _along = new();
    private readonly ThreadBook _threads;

    /// <summary>Initializes a new instance of the <see cref="GroupNotes"/> class.</summary>
    /// <param name="book">Where notes are kept.</param>
    /// <param name="hub">The live chat rooms.</param>
    /// <param name="live">Who is in which live session.</param>
    /// <param name="groupsHolding">A person's watch groups whose title holds an item (<c>PartnerHub.GroupsHolding</c>).</param>
    /// <param name="isPrivate">Whether an account is private.</param>
    /// <param name="keepLive">Whether watch-together chat is kept (the dashboard's "Keep watch-together chat").</param>
    /// <param name="time">The clock.</param>
    /// <param name="threads">The groups' threads (made over the same database when not given).</param>
    public GroupNotes(TimelineNoteBook book, ChatHub hub, ILiveSessions live, Func<Guid, Guid, IReadOnlyList<GroupMembers>> groupsHolding, Func<Guid, bool> isPrivate, Func<bool> keepLive, TimeProvider time, ThreadBook? threads = null)
    {
        _threads = threads ?? new ThreadBook(book.Db, book, time);
        _book = book;
        _hub = hub;
        _live = live;
        _groupsHolding = groupsHolding;
        _isPrivate = isPrivate;
        _keepLive = keepLive;
        _time = time;
    }

    /// <summary>The chat room of a live session.</summary>
    public static string Room(Guid sessionId) => "syncplay:" + sessionId.ToString("N");

    /// <summary>The caller's groups on a title and the notes on it they may read (their audience's).</summary>
    public NotesPage Page(Guid caller, Guid itemId)
    {
        var groups = _groupsHolding(caller, itemId);
        return new NotesPage(groups.Select(g => new NoteGroupView(g.Id, g.Title)).ToList(), _book.VisibleTo(caller, itemId, groups));
    }

    /// <summary>
    /// Writes a message once, as a note: for the caller's watch groups on the title, or in the live
    /// session <paramref name="sessionId"/> (one the caller is watching in) for its participants and
    /// the groups it's for, who get it at once.
    /// </summary>
    /// <param name="onlyGroups">The watch groups the viewer's playback counts for (the player's "Counts for"): the note goes to these of their groups on the title only; null: all of them.</param>
    /// <exception cref="NoteForbiddenException">Not in that session, or (without one) in no watch group on the title.</exception>
    /// <exception cref="ChatRejectedException">The message breaks the chat's rules.</exception>
    public NoteView Pin(Guid caller, string name, Guid itemId, long positionMs, ChatKind kind, string? text, Guid? sessionId, IReadOnlyCollection<Guid>? onlyGroups = null)
    {
        var groups = _groupsHolding(caller, itemId);
        if (onlyGroups is not null)
        {
            groups = groups.Where(g => onlyGroups.Contains(g.Id)).ToList();
        }

        LiveSession? session = null;
        if (sessionId is { } id)
        {
            session = new LiveSession(id, _live.Participants(id, caller) ?? throw new NoteForbiddenException(), _keepLive());
        }
        else if (groups.Count == 0)
        {
            throw new NoteForbiddenException();
        }

        var chat = ThreadFor(caller, groups, session is null ? null : SessionGroup(session.Participants, caller, itemId));
        var note = _book.Pin(caller, name, itemId, positionMs, kind, text, groups, session, chat);
        if (session is not null)
        {
            Deliver(session, note);
        }

        return TimelineNoteBook.View(note, caller, groups)!;
    }

    /// <summary>
    /// A message sent to a live session's chat the older way (<c>POST /Nitrate/Chat/{id}/Messages</c>):
    /// a note on what the session is playing when that's known, else only delivered to who's there.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not in that session.</exception>
    /// <exception cref="ChatRejectedException">The message breaks the chat's rules.</exception>
    public ChatMessage SendLive(Guid caller, string name, Guid sessionId, ChatKind kind, string? text, long? positionMs)
    {
        var participants = _live.Participants(sessionId, caller) ?? throw new NoteForbiddenException();
        var session = new LiveSession(sessionId, participants, _keepLive());
        if (_live.Playing(sessionId, caller) is { } itemId)
        {
            var groups = _groupsHolding(caller, itemId);
            var note = _book.Pin(caller, name, itemId, positionMs ?? 0, kind, text, groups, session, ThreadFor(caller, groups, SessionGroup(participants, caller, itemId)));
            return Deliver(session, note);
        }

        return _hub.Post(Room(sessionId), caller, name, kind, text, positionMs, participants.Append(caller).ToHashSet());
    }

    /// <summary>A live session's messages for the caller (only those they were there for), long polled.</summary>
    /// <exception cref="NoteForbiddenException">Not in that session.</exception>
    public Task<ChatPage> Live(Guid caller, Guid sessionId, long after, TimeSpan wait, CancellationToken cancellationToken)
    {
        if (_live.Participants(sessionId, caller) is null)
        {
            throw new NoteForbiddenException();
        }

        return _hub.WaitAsync(Room(sessionId), after, wait, cancellationToken, caller);
    }

    /// <summary>
    /// "Bring our earlier notes": while the session lasts, everyone in it gets the notes on what it
    /// plays that the caller may read and that went to <paramref name="groupId"/> (a group the caller
    /// is or was in; any other brings nothing). Null stops it (only the one who brought them, or
    /// anyone once they've left). A later bring replaces an earlier one.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not in that session.</exception>
    public void Bring(Guid caller, Guid sessionId, Guid? groupId) => BringNow(caller, sessionId, groupId);

    /// <summary>
    /// The thread a message goes into: the current one of the (first) group it goes to, on that
    /// group's title; in a live session someone outside the group (a guest) writes into the
    /// session's group's thread (<paramref name="sessionGroup"/>); none otherwise.
    /// </summary>
    private Func<IReadOnlyList<NoteShare>, Guid?> ThreadFor(Guid writer, IReadOnlyList<GroupMembers> groups, GroupMembers? sessionGroup) => shares =>
    {
        var group = shares.Select(x => groups.FirstOrDefault(g => g.Id == x.GroupId)).FirstOrDefault(g => g is not null && g.TitleId != Guid.Empty) ?? sessionGroup;
        return group is null || group.TitleId == Guid.Empty ? null : _threads.CurrentOrNew(group.Id, group.TitleId, writer).Id;
    };

    /// <summary>The watch group a live session is for: one of a participant's groups on the title with two or more of them there (the caller's own first).</summary>
    private GroupMembers? SessionGroup(IReadOnlyList<Guid> participants, Guid caller, Guid itemId) =>
        participants.Prepend(caller).Distinct().SelectMany(p => _groupsHolding(p, itemId))
            .FirstOrDefault(g => g.TitleId != Guid.Empty && g.Members.Count(participants.Contains) >= 2);

    /// <summary>Older apps' chat histories: their groups on the title, and no chats (they're threads now).</summary>
    public ChatsPage Chats(Guid caller, Guid itemId) =>
        new(_groupsHolding(caller, itemId).Select(g => new NoteGroupView(g.Id, g.Title)).ToList(), Array.Empty<object>());

    /// <summary>A part's conversations for the caller: their groups' threads and the current threads' messages they may read.</summary>
    public ThreadsPage Threads(Guid caller, Guid itemId) => _threads.Page(caller, itemId, _groupsHolding(caller, itemId));

    /// <summary>One of the caller's groups' threads on a part (to replay an older one): its messages they may read; null when it isn't theirs.</summary>
    public IReadOnlyList<NoteView>? ThreadNotes(Guid caller, Guid itemId, Guid threadId) => _threads.Read(caller, itemId, threadId, _groupsHolding(caller, itemId));

    /// <summary>
    /// Starts a new thread for a group on the title holding <paramref name="itemId"/>: only the
    /// group's creator. What's written from now goes into it; the earlier ones stay to replay.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not the creator of one of their groups on the title.</exception>
    public ChatThread StartThread(Guid caller, Guid itemId, Guid groupId)
    {
        var group = _groupsHolding(caller, itemId).FirstOrDefault(g => g.Id == groupId);
        if (group is null || group.Creator != caller || group.TitleId == Guid.Empty)
        {
            throw new NoteForbiddenException();
        }

        return _threads.StartNew(group.Id, group.TitleId, caller);
    }

    /// <summary>Makes one of the caller's groups' threads a favourite of theirs, or not (kept from the time rules, <see cref="ChatKeeping"/>).</summary>
    /// <exception cref="NoteForbiddenException">Not one of their groups' threads.</exception>
    public void FavouriteThread(Guid caller, Guid itemId, Guid threadId, bool favourite)
    {
        if (!_threads.SetFavourite(caller, threadId, favourite, _groupsHolding(caller, itemId)))
        {
            throw new NoteForbiddenException();
        }
    }

    /// <summary>The groups' threads beside the notes.</summary>
    public ThreadBook ThreadBook => _threads;

    /// <summary>
    /// Someone in a live session picks an older thread (one of their groups') to replay for everyone
    /// there (null stops it); the others are told through the session's chat room.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not in that session, or a thread that isn't theirs.</exception>
    public void PlayAlong(Guid caller, Guid sessionId, Guid itemId, Guid? chatId)
    {
        _ = _live.Participants(sessionId, caller) ?? throw new NoteForbiddenException();
        if (chatId is { } c && _threads.Find(c, _groupsHolding(caller, itemId)) is null)
        {
            throw new NoteForbiddenException();
        }

        var now = _time.GetUtcNow();
        lock (_lock)
        {
            foreach (var old in _along.Where(a => now - a.Value.At > ChatHub.RoomLifetime).Select(a => a.Key).ToList())
            {
                _along.Remove(old);
            }

            if (chatId is { } id)
            {
                _along[(sessionId, itemId)] = (id, caller, now);
            }
            else
            {
                _along.Remove((sessionId, itemId));
            }
        }

        _hub.Bump(Room(sessionId));
    }

    /// <summary>
    /// A live session's conversation on a title for the caller (someone in it now): the thread being
    /// written there (the current thread of the caller's group someone else there is in), and the
    /// older thread replayed for everyone with its messages as the one who picked it may read them
    /// (while they're still there), never kept for the caller.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not in that session (or it's over).</exception>
    public SessionChatView SessionChat(Guid caller, Guid sessionId, Guid itemId)
    {
        var participants = _live.Participants(sessionId, caller) ?? throw new NoteForbiddenException();
        var group = SessionGroup(participants, caller, itemId);
        var writing = group is null ? null : _threads.Current(group.Id, group.TitleId)?.Id;
        (Guid ChatId, Guid By, DateTimeOffset At)? along;
        lock (_lock)
        {
            along = _along.TryGetValue((sessionId, itemId), out var a) && _time.GetUtcNow() - a.At <= ChatHub.RoomLifetime ? a : null;
        }

        if (along is not { } picked || !participants.Contains(picked.By))
        {
            return new SessionChatView(writing, null, null, Array.Empty<NoteView>());
        }

        return new SessionChatView(writing, picked.ChatId, picked.By, _threads.ReadFor(picked.By, itemId, picked.ChatId, _groupsHolding(picked.By, itemId), caller, _isPrivate));
    }

    /// <summary>Which earlier chats play along in which sessions, for the next logic (never written anywhere).</summary>
    public IReadOnlyList<AlongState> ExportAlong()
    {
        lock (_lock)
        {
            return _along.Select(a => new AlongState(a.Key.Session, a.Key.Item, a.Value.ChatId, a.Value.By, a.Value.At)).ToList();
        }
    }

    /// <summary>Takes the chats playing along that the previous logic had.</summary>
    public void ImportAlong(IEnumerable<AlongState> along)
    {
        lock (_lock)
        {
            foreach (var a in along)
            {
                _along[(a.SessionId, a.ItemId)] = (a.ChatId, a.By, a.At);
            }
        }
    }

    /// <summary>Which sessions have notes brought in, for the next logic (never written anywhere).</summary>
    public IReadOnlyList<BroughtState> ExportState()
    {
        lock (_lock)
        {
            return _brought.Select(b => new BroughtState(b.Key, b.Value.Flag.GroupId, b.Value.Flag.By, b.Value.At)).ToList();
        }
    }

    /// <summary>Takes the brought notes the previous logic had.</summary>
    public void ImportState(IEnumerable<BroughtState> brought)
    {
        lock (_lock)
        {
            foreach (var b in brought)
            {
                _brought[b.SessionId] = (new BroughtFlag(b.GroupId, b.By), b.At);
            }
        }
    }

    private void BringNow(Guid caller, Guid sessionId, Guid? groupId)
    {
        var participants = _live.Participants(sessionId, caller) ?? throw new NoteForbiddenException();
        var now = _time.GetUtcNow();
        lock (_lock)
        {
            foreach (var old in _brought.Where(b => now - b.Value.At > ChatHub.RoomLifetime).Select(b => b.Key).ToList())
            {
                _brought.Remove(old);
            }

            if (groupId is { } g)
            {
                _brought[sessionId] = (new BroughtFlag(g, caller), now);
            }
            else if (_brought.TryGetValue(sessionId, out var b) && (b.Flag.By == caller || !participants.Contains(b.Flag.By)))
            {
                _brought.Remove(sessionId);
            }
        }

        _hub.Bump(Room(sessionId));
    }

    /// <summary>
    /// The notes brought into the session on <paramref name="itemId"/>, for the caller (someone in
    /// it now): only while the one who brought them is still there, re-checked against what they may
    /// read now. Never stored for the caller: after the session nothing here can be read.
    /// </summary>
    /// <exception cref="NoteForbiddenException">Not in that session (or it's over).</exception>
    public BroughtNotes Brought(Guid caller, Guid sessionId, Guid itemId)
    {
        var participants = _live.Participants(sessionId, caller) ?? throw new NoteForbiddenException();
        BroughtFlag? flag;
        lock (_lock)
        {
            flag = _brought.TryGetValue(sessionId, out var b) && _time.GetUtcNow() - b.At <= ChatHub.RoomLifetime ? b.Flag : null;
        }

        if (flag is null || !participants.Contains(flag.By))
        {
            return new BroughtNotes(null, Array.Empty<NoteView>());
        }

        return new BroughtNotes(flag.By, _book.Brought(flag.By, flag.GroupId, itemId, _groupsHolding(flag.By, itemId), caller, _isPrivate));
    }

    private ChatMessage Deliver(LiveSession session, TimelineNote note)
    {
        var kind = Enum.TryParse<ChatKind>(note.Kind, out var k) ? k : ChatKind.Text;
        // Live to who's in the session now (its audience, when it's kept for them).
        var audience = session.Participants.Append(note.UserId).ToHashSet();
        return _hub.Deliver(
            Room(session.Id),
            new ChatMessage(0, note.UserId, note.UserName, kind, note.Text, note.PositionMs, DateTimeOffset.FromUnixTimeMilliseconds(note.At), note.Id, note.ItemId),
            audience);
    }
}
