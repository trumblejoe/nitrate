using System.Text.Json;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>A watch group as one of its members sees it for a title: who is in it now.</summary>
/// <param name="Id">The group.</param>
/// <param name="Title">The group's title (show, film, playlist, collection).</param>
/// <param name="Members">Everyone in it now, the caller included.</param>
public record GroupMembers(Guid Id, string Title, IReadOnlyList<Guid> Members);

/// <summary>Who a pinned note went to: one watch group and its members when it was written.</summary>
/// <param name="GroupId">The group.</param>
/// <param name="Members">Its members at the time (the writer included).</param>
public record NoteShare(Guid GroupId, IReadOnlyList<Guid> Members);

/// <summary>
/// A chat message or reaction pinned to a point in a film or episode, for the writer's watch
/// groups on that title. Kept under the item (one database row each, kind <c>note</c>).
/// </summary>
/// <param name="Id">The note.</param>
/// <param name="UserId">Who wrote it.</param>
/// <param name="UserName">Their name when they wrote it.</param>
/// <param name="ItemId">The film or episode.</param>
/// <param name="PositionMs">Where in it.</param>
/// <param name="Kind">"Text" or "Reaction".</param>
/// <param name="Text">What was said (one emoji for a reaction).</param>
/// <param name="Shares">The groups it went to, each with its members then.</param>
/// <param name="At">When (ms).</param>
/// <param name="Audience">
/// Everyone who was in the conversation when it was written, the writer included: its groups'
/// members then, plus the live session's participants for a message sent in one. Exactly these
/// people may read it. Null on notes kept before audiences (read by the older rule).
/// </param>
/// <param name="SessionId">The live (SyncPlay) session it was sent in, if any.</param>
public record TimelineNote(Guid Id, Guid UserId, string UserName, Guid ItemId, long PositionMs, string Kind, string Text, IReadOnlyList<NoteShare> Shares, long At, IReadOnlyList<Guid>? Audience = null, Guid? SessionId = null);

/// <summary>A live (SyncPlay) session a message is sent in.</summary>
/// <param name="Id">The SyncPlay group.</param>
/// <param name="Participants">Who is in it now (user ids, the writer included).</param>
/// <param name="Keep">
/// False when the server keeps no watch-together chat: the participants still get it live, but
/// only the writer's groups keep it (nothing is kept when none of them is there).
/// </param>
public record LiveSession(Guid Id, IReadOnlyList<Guid> Participants, bool Keep = true);

/// <summary>A note as the apps get it: never who else it went to.</summary>
/// <param name="Id">The note (or the older moment's id).</param>
/// <param name="UserId">Who wrote it.</param>
/// <param name="UserName">Their name.</param>
/// <param name="ItemId">The film or episode.</param>
/// <param name="PositionMs">Where in it.</param>
/// <param name="Kind">"Text" or "Reaction".</param>
/// <param name="Text">What was said (empty for an older moment saved without words).</param>
/// <param name="GroupIds">The caller's groups it was shared in.</param>
/// <param name="At">When (ms).</param>
/// <param name="Moment">An older "Mark this moment" bookmark, read as a note.</param>
/// <param name="Brought">
/// Brought into a live session by someone who may read it ("Bring our earlier notes"): shown in
/// that session only, never kept for the caller.
/// </param>
public record NoteView(Guid Id, Guid UserId, string UserName, Guid ItemId, long PositionMs, string Kind, string Text, IReadOnlyList<Guid> GroupIds, long At, bool Moment = false, bool Brought = false);

/// <summary>One of the caller's groups on a title.</summary>
/// <param name="Id">The group.</param>
/// <param name="Title">Its title's name.</param>
public record NoteGroupView(Guid Id, string Title);

/// <summary>A title's notes the caller may see, and their groups on it (empty: nobody to write to).</summary>
/// <param name="Groups">The caller's watch groups whose title holds the item.</param>
/// <param name="Notes">The notes, earliest in the title first.</param>
public record NotesPage(IReadOnlyList<NoteGroupView> Groups, IReadOnlyList<NoteView> Notes);

/// <summary>
/// Chat pinned to the timeline: every message or reaction sent while playing is one note, kept at
/// that point of the film or episode with its audience, and pops up for the others when they reach
/// it, however much later they watch (and at once in a live session, through the chat room). Its
/// audience is everyone in the conversation when it was written: the writer's watch groups on the
/// title (their members then), or in a live (SyncPlay) session its participants plus the members
/// of the writer's groups on the title that have someone else there. Who may read a note: exactly
/// its audience ("was there for" it). Someone who leaves a group keeps the notes from their time in
/// it and never gets anything written after; someone who joins later never sees what came before.
/// Notes kept before audiences are read by the older rule: the writer, and those who were in one of
/// its groups when it was written and still share it with the writer. Nobody is ever told who else
/// is in a note's audience; the apps also only show notes up to where the viewer has got (spoilers).
/// </summary>
public class TimelineNoteBook
{
    /// <summary>The database kind.</summary>
    public const string Kind = "note";

    /// <summary>Most notes kept per title (the newest).</summary>
    public const int MaxPerTitle = 5_000;

    private readonly CompanionDb _db;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, List<DateTimeOffset>> _recent = new();
    private readonly Dictionary<Guid, TimelineNote> _lastLive = new();

    /// <summary>Initializes a new instance of the <see cref="TimelineNoteBook"/> class.</summary>
    public TimelineNoteBook(CompanionDb db, TimeProvider time)
    {
        _db = db;
        _time = time;
    }

    /// <summary>How long a pin without a session that repeats one just sent in a live session is taken as that one (older apps sent both).</summary>
    public static readonly TimeSpan SameMessage = TimeSpan.FromSeconds(10);

    /// <summary>
    /// Pins a message at <paramref name="positionMs"/> in the item, once, with its audience: the
    /// writer's groups on it (<paramref name="groups"/>, from the partner hub: only groups the writer
    /// is in), or in a live <paramref name="session"/> its participants plus those of the writer's
    /// groups the session is for (someone else from the group is there).
    /// </summary>
    /// <exception cref="ChatRejectedException">Empty, too long, not one emoji, too fast, or nobody to write to.</exception>
    public TimelineNote Pin(Guid user, string userName, Guid itemId, long positionMs, ChatKind kind, string? text, IReadOnlyList<GroupMembers> groups, LiveSession? session = null)
    {
        var body = ChatHub.Check(kind, text);
        var mineGroups = groups.Where(g => g.Members.Contains(user));
        if (session is not null)
        {
            // The groups this session is for: someone else from the group is in it.
            mineGroups = mineGroups.Where(g => g.Members.Any(m => m != user && session.Participants.Contains(m)));
        }

        var shares = mineGroups.Select(g => new NoteShare(g.Id, g.Members.ToList())).ToList();
        if (shares.Count == 0 && session is null)
        {
            throw new ChatRejectedException("You're not in a watch group for this title.");
        }

        var now = _time.GetUtcNow();
        lock (_lock)
        {
            // An older app sends a live message twice (to the session, then pinned): the second is the first.
            if (session is null && _lastLive.TryGetValue(user, out var last) && last.ItemId == itemId && last.Text == body && last.Kind == kind.ToString()
                && now.ToUnixTimeMilliseconds() - last.At < SameMessage.TotalMilliseconds)
            {
                return last;
            }

            var mine = _recent.TryGetValue(user, out var list) ? list : _recent[user] = new List<DateTimeOffset>();
            mine.RemoveAll(t => now - t >= ChatHub.BurstWindow);
            if (mine.Count >= ChatHub.BurstLimit)
            {
                throw new ChatRejectedException("You're sending messages too fast.", tooFast: true);
            }

            mine.Add(now);
        }

        var keepFor = session is { Keep: true } ? session.Participants : Array.Empty<Guid>();
        var audience = new[] { user }.Concat(shares.SelectMany(s => s.Members)).Concat(keepFor).Distinct().ToList();
        var note = new TimelineNote(Guid.NewGuid(), user, userName, itemId, Math.Max(0, positionMs), kind.ToString(), body, shares, now.ToUnixTimeMilliseconds(), audience, session?.Id);
        if (session is not null)
        {
            lock (_lock)
            {
                _lastLive[user] = note;
            }
        }

        // Kept unless it's a live message on a server that keeps no watch-together chat, with none of the writer's groups there.
        if (audience.Count > 1 || shares.Count > 0)
        {
            var owner = itemId.ToString("N");
            _db.PutRecord(Kind, owner, note.Id.ToString("N"), JsonSerializer.Serialize(note), note.At);
            _db.KeepNewest(Kind, owner, MaxPerTitle);
        }

        return note;
    }

    /// <summary>Every note kept on a title, earliest written first (for the access rules to filter).</summary>
    public IReadOnlyList<TimelineNote> All(Guid itemId) =>
        _db.Records(Kind, itemId.ToString("N")).Select(r => DbDiaryStorage.Read<TimelineNote>(r.Value)).OfType<TimelineNote>().ToList();

    /// <summary>
    /// The notes on a title <paramref name="caller"/> may read, given the caller's groups on it now
    /// (<paramref name="callerGroups"/>), earliest in the title first.
    /// </summary>
    public IReadOnlyList<NoteView> VisibleTo(Guid caller, Guid itemId, IReadOnlyList<GroupMembers> callerGroups) =>
        All(itemId).Select(n => View(n, caller, callerGroups)).OfType<NoteView>().OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();

    /// <summary>The note as the caller sees it, or null when they may not (see the class summary).</summary>
    public static NoteView? View(TimelineNote note, Guid caller, IReadOnlyList<GroupMembers> callerGroups)
    {
        IReadOnlyList<Guid> groups;
        if (note.UserId == caller)
        {
            groups = note.Shares.Select(s => s.GroupId).ToList();
        }
        else if (note.Audience is { } audience)
        {
            // Was there for it: in its audience. Nothing else counts, now or later.
            if (!audience.Contains(caller))
            {
                return null;
            }

            groups = note.Shares.Where(s => s.Members.Contains(caller)).Select(s => s.GroupId).ToList();
        }
        else
        {
            // Was in that group when it was written, and still shares it with the writer.
            groups = note.Shares
                .Where(s => s.Members.Contains(caller))
                .Where(s => callerGroups.Any(g => g.Id == s.GroupId && g.Members.Contains(caller) && g.Members.Contains(note.UserId)))
                .Select(s => s.GroupId).ToList();
            if (groups.Count == 0)
            {
                return null;
            }
        }

        return new NoteView(note.Id, note.UserId, note.UserName, note.ItemId, note.PositionMs, note.Kind, note.Text, groups, note.At);
    }

    /// <summary>Whether <paramref name="caller"/> may read the note (see the class summary).</summary>
    public static bool Readable(TimelineNote note, Guid caller, IReadOnlyList<GroupMembers> callerGroups) => View(note, caller, callerGroups) is not null;

    /// <summary>
    /// "Bring our earlier notes": the notes on a title that <paramref name="bringer"/> may read
    /// (given their groups on it now, <paramref name="bringerGroups"/>) and that went to
    /// <paramref name="groupId"/>, as <paramref name="viewer"/> (someone in the live session) gets
    /// them: marked brought, never naming a group, and leaving out notes by private accounts the
    /// viewer wasn't there for. Nothing here makes them readable by the viewer afterwards.
    /// </summary>
    public IReadOnlyList<NoteView> Brought(Guid bringer, Guid groupId, Guid itemId, IReadOnlyList<GroupMembers> bringerGroups, Guid viewer, Func<Guid, bool> isPrivate) =>
        All(itemId)
            .Where(n => n.Shares.Any(s => s.GroupId == groupId) && Readable(n, bringer, bringerGroups))
            .Where(n => n.UserId == bringer || n.UserId == viewer || !isPrivate(n.UserId) || (n.Audience ?? n.Shares.SelectMany(s => s.Members).ToList()).Contains(viewer))
            .Select(n => new NoteView(n.Id, n.UserId, n.UserName, n.ItemId, n.PositionMs, n.Kind, n.Text, Array.Empty<Guid>(), n.At, Brought: true))
            .OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();

    /// <summary>An older moment (one's own, or shared with a group one is in) read as a note.</summary>
    public static NoteView FromMoment(Moment m) =>
        new(m.Id, m.UserId, m.UserName, m.ItemId, m.PositionMs, "Text", m.Note ?? string.Empty, m.GroupId is { } g ? new[] { g } : Array.Empty<Guid>(), m.At, Moment: true);

    /// <summary>Forgets one of the caller's own notes on a title; false when there's none of theirs.</summary>
    public bool Delete(Guid caller, Guid itemId, Guid id)
    {
        var owner = itemId.ToString("N");
        if (_db.Record(Kind, owner, id.ToString("N")) is not { } json || DbDiaryStorage.Read<TimelineNote>(json) is not { } note || note.UserId != caller)
        {
            return false;
        }

        _db.DeleteRecord(Kind, owner, id.ToString("N"));
        return true;
    }

    /// <summary>Forgets notes written before <paramref name="beforeAt"/> (ms).</summary>
    public void DeleteOlder(long beforeAt) => _db.DeleteOlder(Kind, beforeAt);
}
