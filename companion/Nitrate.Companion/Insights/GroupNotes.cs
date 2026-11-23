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

    /// <summary>Initializes a new instance of the <see cref="GroupNotes"/> class.</summary>
    /// <param name="book">Where notes are kept.</param>
    /// <param name="hub">The live chat rooms.</param>
    /// <param name="live">Who is in which live session.</param>
    /// <param name="groupsHolding">A person's watch groups whose title holds an item (<c>PartnerHub.GroupsHolding</c>).</param>
    /// <param name="isPrivate">Whether an account is private.</param>
    /// <param name="keepLive">Whether watch-together chat is kept (the dashboard's "Keep watch-together chat").</param>
    /// <param name="time">The clock.</param>
    public GroupNotes(TimelineNoteBook book, ChatHub hub, ILiveSessions live, Func<Guid, Guid, IReadOnlyList<GroupMembers>> groupsHolding, Func<Guid, bool> isPrivate, Func<bool> keepLive, TimeProvider time)
    {
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

        var note = _book.Pin(caller, name, itemId, positionMs, kind, text, groups, session);
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
            var note = _book.Pin(caller, name, itemId, positionMs ?? 0, kind, text, _groupsHolding(caller, itemId), session);
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
    public void Bring(Guid caller, Guid sessionId, Guid? groupId)
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
