using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>
/// One chat history on a film or episode: the messages written in one playback (or one Group Watch
/// Session), kept as notes carrying its id. Never copied or changed once others build on it: a chat
/// written while replaying an earlier one keeps only the new messages and a link to that one
/// (<paramref name="BuildsOn"/>). Kept under the item (one database row each, kind <c>notechat</c>).
/// </summary>
/// <param name="Id">The chat.</param>
/// <param name="ItemId">The film or episode.</param>
/// <param name="GroupId">The watch group it was written for (the first the first message went to), if any.</param>
/// <param name="Created">When its first message was written (ms).</param>
/// <param name="By">Who wrote its first message.</param>
/// <param name="BuildsOn">The earlier chat that was playing along when it was written, if any.</param>
/// <param name="SessionId">The live session it was written in, if any (everyone there shares it).</param>
public record NoteChat(Guid Id, Guid ItemId, Guid? GroupId, long Created, Guid By, Guid? BuildsOn = null, Guid? SessionId = null);

/// <summary>A chat history as one person may see it: only what they were in the audience of counts.</summary>
/// <param name="Id">The chat.</param>
/// <param name="GroupId">The watch group it was written for, if it's one of the caller's (else null).</param>
/// <param name="Created">When its first message the caller may read was written (ms).</param>
/// <param name="BuildsOn">The chat it builds on, when the caller may see that one too.</param>
/// <param name="SessionId">The live session it was written in, if any.</param>
/// <param name="People">Who wrote in it besides the caller, in order of their first message.</param>
/// <param name="Count">How many of its own messages the caller may read (not counting what it builds on).</param>
/// <param name="Earlier">Notes kept before chat histories, gathered as one "Earlier notes" chat per group.</param>
public record ChatView(Guid Id, Guid? GroupId, long Created, Guid? BuildsOn, Guid? SessionId, IReadOnlyList<string> People, int Count, bool Earlier = false);

/// <summary>A title's chat histories for the caller, newest first, and their watch groups on it.</summary>
/// <param name="Groups">The caller's watch groups whose title holds the item.</param>
/// <param name="Chats">The chats with at least one message the caller may read, newest first.</param>
public record ChatsPage(IReadOnlyList<NoteGroupView> Groups, IReadOnlyList<ChatView> Chats);

/// <summary>A chat to play along with: its notes and those of the chats it builds on, the caller's to read.</summary>
/// <param name="Id">The chat.</param>
/// <param name="Chain">The chat, then each it builds on in turn.</param>
/// <param name="Notes">Their notes the caller may read, earliest in the title first.</param>
public record ChatNotes(Guid Id, IReadOnlyList<Guid> Chain, IReadOnlyList<NoteView> Notes);

/// <summary>
/// Chat histories over the timeline notes (<see cref="TimelineNoteBook"/>). Notes kept before chat
/// histories carry no chat id and are read, unchanged, as one chat per live session they were sent
/// in and one "Earlier notes" chat per watch group (their ids derived from the item and the session
/// or group, so nothing is rewritten and their audiences stay as they were).
/// </summary>
public class NoteChatBook
{
    /// <summary>The database kind.</summary>
    public const string Kind = "notechat";

    /// <summary>How deep a chain of chats building on each other is followed.</summary>
    public const int MaxChain = 50;

    private readonly CompanionDb _db;
    private readonly TimelineNoteBook _notes;
    private readonly TimeProvider _time;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="NoteChatBook"/> class.</summary>
    public NoteChatBook(CompanionDb db, TimelineNoteBook notes, TimeProvider time)
    {
        _db = db;
        _notes = notes;
        _time = time;
    }

    /// <summary>The notes on a title.</summary>
    public TimelineNoteBook Notes => _notes;

    /// <summary>The chat an older note (kept without one) is read as: its session's, else its first group's "Earlier notes".</summary>
    public static Guid LegacyId(TimelineNote note) =>
        note.SessionId is { } s ? Derived(note.ItemId, "session:" + s.ToString("N")) : Derived(note.ItemId, "group:" + (note.Shares.FirstOrDefault()?.GroupId ?? Guid.Empty).ToString("N"));

    /// <summary>The chat a note belongs to.</summary>
    public static Guid ChatOf(TimelineNote note) => note.ChatId ?? LegacyId(note);

    private static Guid Derived(Guid itemId, string what)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes("nitrate-chat|" + itemId.ToString("N") + "|" + what));
        return new Guid(hash.AsSpan(0, 16));
    }

    /// <summary>The chats kept on a title (not the older notes' ones, which are derived).</summary>
    public IReadOnlyList<NoteChat> Stored(Guid itemId) =>
        _db.Records(Kind, itemId.ToString("N")).Select(r => DbDiaryStorage.Read<NoteChat>(r.Value)).OfType<NoteChat>().ToList();

    /// <summary>One kept chat, or null.</summary>
    public NoteChat? Get(Guid itemId, Guid id) =>
        _db.Record(Kind, itemId.ToString("N"), id.ToString("N")) is { } json ? DbDiaryStorage.Read<NoteChat>(json) : null;

    private void Put(NoteChat chat) => _db.PutRecord(Kind, chat.ItemId.ToString("N"), chat.Id.ToString("N"), JsonSerializer.Serialize(chat), chat.Created);

    /// <summary>
    /// Every chat on a title with at least one message <paramref name="caller"/> may read, newest
    /// first, each counting only those messages (a chat it builds on is named only when the caller
    /// may see that one too).
    /// </summary>
    public IReadOnlyList<ChatView> Visible(Guid caller, Guid itemId, IReadOnlyList<GroupMembers> callerGroups)
    {
        var stored = Stored(itemId).ToDictionary(c => c.Id);
        var mineGroups = callerGroups.Select(g => g.Id).ToHashSet();
        var views = new List<ChatView>();
        foreach (var chat in _notes.All(itemId).GroupBy(ChatOf))
        {
            var readable = chat.Select(n => (Note: n, View: TimelineNoteBook.View(n, caller, callerGroups))).Where(x => x.View is not null).OrderBy(x => x.Note.At).ToList();
            if (readable.Count == 0)
            {
                continue;
            }

            var first = readable[0].Note;
            var people = readable.Where(x => x.Note.UserId != caller).Select(x => x.Note.UserName).Distinct().ToList();
            if (stored.TryGetValue(chat.Key, out var kept))
            {
                views.Add(new ChatView(chat.Key, kept.GroupId is { } g && (mineGroups.Contains(g) || readable.Any(x => x.View!.GroupIds.Contains(g))) ? g : null, first.At, kept.BuildsOn, kept.SessionId, people, readable.Count));
            }
            else
            {
                var group = readable.SelectMany(x => x.View!.GroupIds).Cast<Guid?>().FirstOrDefault();
                views.Add(new ChatView(chat.Key, group, first.At, null, first.SessionId, people, readable.Count, Earlier: first.SessionId is null));
            }
        }

        var listed = views.Select(v => v.Id).ToHashSet();
        return views.Select(v => v.BuildsOn is { } b && !listed.Contains(b) ? v with { BuildsOn = null } : v)
            .OrderByDescending(v => v.Created).ToList();
    }

    /// <summary>The chat, then each it builds on in turn (a loop or a chain deeper than <see cref="MaxChain"/> stops there).</summary>
    public IReadOnlyList<Guid> Chain(Guid itemId, Guid chatId)
    {
        var stored = Stored(itemId).ToDictionary(c => c.Id);
        var chain = new List<Guid>();
        var seen = new HashSet<Guid>();
        Guid? at = chatId;
        while (at is { } id && seen.Add(id) && chain.Count < MaxChain)
        {
            chain.Add(id);
            at = stored.TryGetValue(id, out var c) ? c.BuildsOn : null;
        }

        return chain;
    }

    /// <summary>
    /// A chat to play along with, for <paramref name="caller"/>: the notes of it and the chats it
    /// builds on that they may read (each written once: nothing is duplicated). Null when they may
    /// read none of the chat's own messages.
    /// </summary>
    public ChatNotes? Read(Guid caller, Guid itemId, Guid chatId, IReadOnlyList<GroupMembers> callerGroups)
    {
        var chain = Chain(itemId, chatId);
        var all = _notes.All(itemId);
        var notes = all.Where(n => chain.Contains(ChatOf(n)))
            .Select(n => (Note: n, View: TimelineNoteBook.View(n, caller, callerGroups)))
            .Where(x => x.View is not null).ToList();
        if (!notes.Any(x => ChatOf(x.Note) == chatId))
        {
            return null;
        }

        return new ChatNotes(chatId, chain, notes.Select(x => x.View!).OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList());
    }

    /// <summary>
    /// The notes of a chat and those it builds on that <paramref name="picker"/> may read, as
    /// <paramref name="viewer"/> (someone in the same live session) gets them: marked brought, never
    /// naming a group, leaving out notes by private accounts the viewer wasn't there for. Never kept
    /// for the viewer.
    /// </summary>
    public IReadOnlyList<NoteView> ReadFor(Guid picker, Guid itemId, Guid chatId, IReadOnlyList<GroupMembers> pickerGroups, Guid viewer, Func<Guid, bool> isPrivate)
    {
        var chain = Chain(itemId, chatId);
        return _notes.All(itemId)
            .Where(n => chain.Contains(ChatOf(n)) && TimelineNoteBook.Readable(n, picker, pickerGroups))
            .Where(n => n.UserId == picker || n.UserId == viewer || !isPrivate(n.UserId) || (n.Audience ?? n.Shares.SelectMany(s => s.Members).ToList()).Contains(viewer))
            .Select(n => new NoteView(n.Id, n.UserId, n.UserName, n.ItemId, n.PositionMs, n.Kind, n.Text, Array.Empty<Guid>(), n.At, Brought: n.UserId != viewer && n.Audience?.Contains(viewer) != true, ChatId: ChatOf(n)))
            .OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();
    }

    /// <summary>
    /// Whether <paramref name="caller"/> may see the chat at all (at least one of its own messages),
    /// so it may be played along or built on.
    /// </summary>
    public bool Listed(Guid caller, Guid itemId, Guid chatId, IReadOnlyList<GroupMembers> callerGroups) =>
        _notes.All(itemId).Any(n => ChatOf(n) == chatId && TimelineNoteBook.Readable(n, caller, callerGroups));

    /// <summary>
    /// The chat a message written now goes into, made when it's the first: <paramref name="current"/>
    /// (one this playback or session wrote already) while it builds on the same chat, or still on
    /// none (it then takes <paramref name="buildsOn"/>); otherwise a new chat building on
    /// <paramref name="buildsOn"/>. A chat of someone else's, or another session's, is never written to;
    /// in a live session everyone writes into the session's chat.
    /// </summary>
    public NoteChat For(Guid writer, Guid itemId, Guid? groupId, Guid? current, Guid? buildsOn, Guid? sessionId)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
            // In a live session, the chat the session is writing (whoever wrote first).
            var chat = (current is { } c ? Get(itemId, c) : null) ?? (sessionId is { } s ? OfSession(itemId, s) : null);
            if (chat is not null && (chat.SessionId != sessionId || (sessionId is null && chat.By != writer)))
            {
                chat = null;
            }

            if (chat is not null && chat.BuildsOn != buildsOn)
            {
                if (chat.BuildsOn is null && buildsOn is not null && chat.Id != buildsOn && !Chain(itemId, buildsOn.Value).Contains(chat.Id))
                {
                    // Still being written, building on nothing yet: it builds on what's playing along now.
                    chat = chat with { BuildsOn = buildsOn };
                    Put(chat);
                }
                else
                {
                    chat = null;
                }
            }

            if (chat is null)
            {
                chat = new NoteChat(Guid.NewGuid(), itemId, groupId, now, writer, buildsOn, sessionId);
                Put(chat);
            }

            return chat;
        }
    }

    /// <summary>The newest chat written in a live session on a title, if any.</summary>
    public NoteChat? OfSession(Guid itemId, Guid sessionId) =>
        Stored(itemId).Where(c => c.SessionId == sessionId).OrderByDescending(c => c.Created).FirstOrDefault();

    /// <summary>Forgets chats begun before <paramref name="beforeAt"/> (ms).</summary>
    public void DeleteOlder(long beforeAt) => _db.DeleteOlder(Kind, beforeAt);
}
