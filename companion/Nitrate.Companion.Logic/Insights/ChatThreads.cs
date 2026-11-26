using System.Text.Json;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>
/// One conversation of a watch group on one of its titles (a show, a film, a playlist or a
/// collection): every message any member writes on any part of that title, whenever they watch,
/// goes into the group's current thread for it. Stored per part (each note carries its thread's id
/// in <see cref="TimelineNote.ChatId"/>) and linked by the thread. The group's creator can start a
/// new thread (a rewatch); the earlier ones are kept and can be replayed. Kept under the group and
/// title (one database row each, kind <c>thread</c>).
/// </summary>
/// <param name="Id">The thread.</param>
/// <param name="GroupId">The watch group.</param>
/// <param name="TitleId">The group's title it's on.</param>
/// <param name="Started">When it was started (ms).</param>
/// <param name="StartedBy">Who started it (the first writer, or the creator starting a new one).</param>
public record ChatThread(Guid Id, Guid GroupId, Guid TitleId, long Started, Guid StartedBy);

/// <summary>A thread as one person sees it on one part.</summary>
/// <param name="Id">The thread.</param>
/// <param name="Started">When it was started (ms).</param>
/// <param name="Count">How many of its messages on this part the caller may read.</param>
/// <param name="Current">The group's current thread for the title (what's written goes here).</param>
/// <param name="Favourite">The caller made it a favourite: never deleted by the time rules.</param>
public record ThreadView(Guid Id, long Started, int Count, bool Current, bool Favourite = false);

/// <summary>One of the caller's watch groups on a title, with its threads on it, the current one first.</summary>
/// <param name="GroupId">The group.</param>
/// <param name="Title">Its title's name.</param>
/// <param name="TitleId">Its title holding the part.</param>
/// <param name="CanStart">The caller is the group's creator: they may start a new thread.</param>
/// <param name="Threads">Its threads on the title, newest first (the first is current); empty until someone writes.</param>
public record ThreadGroupView(Guid GroupId, string Title, Guid TitleId, bool CanStart, IReadOnlyList<ThreadView> Threads);

/// <summary>
/// A part's conversations for the caller: their groups on it, each group's threads, and the
/// messages of the current threads on the part they may read (the apps pop them up as playback
/// reaches them).
/// </summary>
/// <param name="Groups">The caller's watch groups whose title holds the part.</param>
/// <param name="Threads">Each of those groups' threads on its title.</param>
/// <param name="Notes">The current threads' messages on the part the caller may read, earliest in it first.</param>
public record ThreadsPage(IReadOnlyList<NoteGroupView> Groups, IReadOnlyList<ThreadGroupView> Threads, IReadOnlyList<NoteView> Notes);

/// <summary>One person's favourite thread (kind <c>threadfav</c> under the thread, one row per person).</summary>
/// <param name="UserId">Whose favourite.</param>
/// <param name="ThreadId">The thread.</param>
/// <param name="At">When it was made one (ms).</param>
public record ThreadFavourite(Guid UserId, Guid ThreadId, long At);

/// <summary>Older apps' chat histories page: still answered, with no chats (they're threads now).</summary>
/// <param name="Groups">The caller's watch groups whose title holds the item.</param>
/// <param name="Chats">Always empty.</param>
public record ChatsPage(IReadOnlyList<NoteGroupView> Groups, IReadOnlyList<object> Chats);

/// <summary>
/// The watch groups' threads over the timeline notes (<see cref="TimelineNoteBook"/>). Who may read
/// a message stays its audience's alone (<see cref="TimelineNoteBook.View"/>): a thread never makes
/// anything readable to anyone who wasn't there for it.
/// </summary>
public class ThreadBook
{
    /// <summary>The database kind of threads.</summary>
    public const string Kind = "thread";

    /// <summary>The database kind of people's favourite threads.</summary>
    public const string FavouriteKind = "threadfav";

    private readonly CompanionDb _db;
    private readonly TimelineNoteBook _notes;
    private readonly TimeProvider _time;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="ThreadBook"/> class.</summary>
    public ThreadBook(CompanionDb db, TimelineNoteBook notes, TimeProvider time)
    {
        _db = db;
        _notes = notes;
        _time = time;
    }

    /// <summary>The notes the threads are made of.</summary>
    public TimelineNoteBook Notes => _notes;

    private static string Owner(Guid groupId, Guid titleId) => groupId.ToString("N") + "|" + titleId.ToString("N");

    /// <summary>A group's threads on a title, newest first (the first is current).</summary>
    public IReadOnlyList<ChatThread> Of(Guid groupId, Guid titleId) =>
        _db.Records(Kind, Owner(groupId, titleId)).Select(r => DbDiaryStorage.Read<ChatThread>(r.Value)).OfType<ChatThread>()
            .OrderByDescending(t => t.Started).ToList();

    /// <summary>The group's current thread on a title, or null before anyone wrote there.</summary>
    public ChatThread? Current(Guid groupId, Guid titleId) => Of(groupId, titleId).FirstOrDefault();

    /// <summary>The current thread a message goes into, started by <paramref name="writer"/> when there's none yet.</summary>
    public ChatThread CurrentOrNew(Guid groupId, Guid titleId, Guid writer)
    {
        lock (_lock)
        {
            return Current(groupId, titleId) ?? Put(new ChatThread(Guid.NewGuid(), groupId, titleId, Now(), writer));
        }
    }

    /// <summary>Starts a new thread (the group's creator, before a rewatch): what's written from now goes into it.</summary>
    public ChatThread StartNew(Guid groupId, Guid titleId, Guid by)
    {
        lock (_lock)
        {
            // Never two threads started in the same millisecond (the newest is the current one).
            var at = Math.Max(Now(), (Current(groupId, titleId)?.Started ?? 0) + 1);
            return Put(new ChatThread(Guid.NewGuid(), groupId, titleId, at, by));
        }
    }

    private ChatThread Put(ChatThread thread)
    {
        _db.PutRecord(Kind, Owner(thread.GroupId, thread.TitleId), thread.Id.ToString("N"), JsonSerializer.Serialize(thread), thread.Started);
        return thread;
    }

    /// <summary>Forgets a thread and its favourites (its notes go separately).</summary>
    public void Delete(ChatThread thread)
    {
        _db.DeleteRecord(Kind, Owner(thread.GroupId, thread.TitleId), thread.Id.ToString("N"));
        foreach (var f in Favourites(thread.Id))
        {
            _db.DeleteRecord(FavouriteKind, thread.Id.ToString("N"), f.UserId.ToString("N"));
        }
    }

    /// <summary>Every thread kept (for the time rules).</summary>
    public IReadOnlyList<ChatThread> All() =>
        _db.Records(Kind, null).Select(r => DbDiaryStorage.Read<ChatThread>(r.Value)).OfType<ChatThread>().ToList();

    private long Now() => _time.GetUtcNow().ToUnixTimeMilliseconds();

    /// <summary>
    /// A part's conversations for <paramref name="caller"/>: each of their groups on it with its
    /// threads (counting only messages on this part they may read), and the current threads'
    /// messages they may read.
    /// </summary>
    public ThreadsPage Page(Guid caller, Guid itemId, IReadOnlyList<GroupMembers> callerGroups)
    {
        var notes = _notes.All(itemId);
        var views = new List<ThreadGroupView>();
        var current = new HashSet<Guid>();
        foreach (var g in callerGroups)
        {
            var threads = g.TitleId == Guid.Empty ? Array.Empty<ChatThread>() : Of(g.Id, g.TitleId);
            if (threads.FirstOrDefault() is { } c)
            {
                current.Add(c.Id);
            }

            var mine = threads.Select((t, i) => new ThreadView(
                t.Id, t.Started,
                notes.Count(n => n.ChatId == t.Id && TimelineNoteBook.Readable(n, caller, callerGroups)),
                i == 0,
                IsFavourite(caller, t.Id))).ToList();
            views.Add(new ThreadGroupView(g.Id, g.Title, g.TitleId, g.Creator == caller, mine));
        }

        var readable = notes.Where(n => n.ChatId is { } id && current.Contains(id))
            .Select(n => TimelineNoteBook.View(n, caller, callerGroups)).OfType<NoteView>()
            .OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();
        return new ThreadsPage(callerGroups.Select(g => new NoteGroupView(g.Id, g.Title)).ToList(), views, readable);
    }

    /// <summary>The thread, when it's one of <paramref name="callerGroups"/>' threads on their titles.</summary>
    public ChatThread? Find(Guid threadId, IReadOnlyList<GroupMembers> callerGroups) =>
        callerGroups.Where(g => g.TitleId != Guid.Empty).SelectMany(g => Of(g.Id, g.TitleId)).FirstOrDefault(t => t.Id == threadId);

    /// <summary>
    /// A thread's messages on a part that <paramref name="caller"/> may read (to replay an older
    /// one); null when it isn't one of their groups' threads.
    /// </summary>
    public IReadOnlyList<NoteView>? Read(Guid caller, Guid itemId, Guid threadId, IReadOnlyList<GroupMembers> callerGroups)
    {
        if (Find(threadId, callerGroups) is null)
        {
            return null;
        }

        return _notes.All(itemId).Where(n => n.ChatId == threadId)
            .Select(n => TimelineNoteBook.View(n, caller, callerGroups)).OfType<NoteView>()
            .OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();
    }

    /// <summary>
    /// A thread's messages on a part that <paramref name="picker"/> may read, as
    /// <paramref name="viewer"/> (someone in the same live session) gets them: marked brought,
    /// never naming a group, leaving out messages by private accounts the viewer wasn't there for.
    /// Never kept for the viewer.
    /// </summary>
    public IReadOnlyList<NoteView> ReadFor(Guid picker, Guid itemId, Guid threadId, IReadOnlyList<GroupMembers> pickerGroups, Guid viewer, Func<Guid, bool> isPrivate) =>
        _notes.All(itemId)
            .Where(n => n.ChatId == threadId && TimelineNoteBook.Readable(n, picker, pickerGroups))
            .Where(n => n.UserId == picker || n.UserId == viewer || !isPrivate(n.UserId) || (n.Audience ?? n.Shares.SelectMany(s => s.Members).ToList()).Contains(viewer))
            .Select(n => new NoteView(n.Id, n.UserId, n.UserName, n.ItemId, n.PositionMs, n.Kind, n.Text, Array.Empty<Guid>(), n.At, Brought: n.UserId != viewer && n.Audience?.Contains(viewer) != true, ChatId: threadId))
            .OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList();

    /// <summary>Who made a thread one of their favourites.</summary>
    public IReadOnlyList<ThreadFavourite> Favourites(Guid threadId) =>
        _db.Records(FavouriteKind, threadId.ToString("N")).Select(r => DbDiaryStorage.Read<ThreadFavourite>(r.Value)).OfType<ThreadFavourite>().ToList();

    private bool IsFavourite(Guid user, Guid threadId) => _db.Record(FavouriteKind, threadId.ToString("N"), user.ToString("N")) is not null;

    /// <summary>
    /// Makes a thread one of <paramref name="user"/>'s favourites, or not. Only one of their groups'
    /// threads can be made one; taking one away always works.
    /// </summary>
    /// <returns>False when it isn't one of their groups' threads.</returns>
    public bool SetFavourite(Guid user, Guid threadId, bool favourite, IReadOnlyList<GroupMembers> userGroups)
    {
        if (!favourite)
        {
            _db.DeleteRecord(FavouriteKind, threadId.ToString("N"), user.ToString("N"));
            return true;
        }

        if (Find(threadId, userGroups) is null)
        {
            return false;
        }

        var at = Now();
        _db.PutRecord(FavouriteKind, threadId.ToString("N"), user.ToString("N"), JsonSerializer.Serialize(new ThreadFavourite(user, threadId, at)), at);
        return true;
    }

    /// <summary>
    /// Once, as threads arrive: the chats kept before them (per-playback chat histories, their
    /// favourites and their notes) are deleted; they were test data.
    /// </summary>
    public void ForgetChatHistoriesOnce()
    {
        const string flagKind = "migrations";
        const string flag = "threads.v1";
        if (_db.Pair(flagKind, flag) is not null)
        {
            return;
        }

        _db.Execute("DELETE FROM records WHERE kind IN ('note', 'notechat', 'chatfav')");
        _db.PutPair(flagKind, flag, "done");
    }
}
