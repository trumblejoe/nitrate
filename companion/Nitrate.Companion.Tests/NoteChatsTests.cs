using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Chat histories: one per playback or session, made with the first message, building on earlier ones without copying them.</summary>
public sealed class NoteChatsTests : IDisposable
{
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Jessica = Guid.NewGuid();
    private static readonly Guid Hidden = Guid.NewGuid();
    private static readonly Guid Episode = Guid.NewGuid();
    private static readonly Guid Pair = Guid.NewGuid();
    private static readonly Guid Session = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-17T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly CompanionDb _db = new(":memory:");
    private readonly TimelineNoteBook _book;
    private readonly ChatHub _hub;
    private readonly Live _live = new();
    private readonly Dictionary<Guid, List<GroupMembers>> _groups = new();
    private readonly GroupNotes _notes;
    private readonly NoteChatBook _chats;

    public NoteChatsTests()
    {
        _book = new TimelineNoteBook(_db, _time);
        _hub = new ChatHub(_time);
        _chats = new NoteChatBook(_db, _book, _time);
        _notes = new GroupNotes(_book, _hub, _live, (user, _) => _groups.GetValueOrDefault(user) ?? new List<GroupMembers>(), id => id == Hidden, () => true, _time, _chats);
    }

    public void Dispose() => _db.Dispose();

    private sealed class Live : ILiveSessions
    {
        public Dictionary<Guid, List<Guid>> In { get; } = new();

        public IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller) =>
            In.TryGetValue(sessionId, out var people) && people.Contains(caller) ? people.ToList() : null;

        public Guid? Playing(Guid sessionId, Guid caller) => null;
    }

    private void Group(params Guid[] members)
    {
        _groups.Clear();
        foreach (var m in members)
        {
            _groups[m] = new List<GroupMembers> { new(Pair, "The Bear", members) };
        }
    }

    private NoteView Write(Guid who, long at, string text, Guid? chatId = null, Guid? buildsOn = null, Guid? session = null)
    {
        _time.Advance(TimeSpan.FromSeconds(30));
        return _notes.Pin(who, who == Sam ? "sam" : who == Kim ? "kim" : who == Hidden ? "quiet" : "jessica", Episode, at, ChatKind.Text, text, session, null, new ChatWrite(chatId, buildsOn));
    }

    [Fact]
    public void AChatIsMadeOnlyWithItsFirstMessageAndTheRestOfThePlaybackGoesIntoIt()
    {
        Group(Sam, Kim);
        Assert.Empty(_notes.Chats(Sam, Episode).Chats);
        Assert.Empty(_chats.Stored(Episode));

        var first = Write(Sam, 60_000, "here we go");
        Assert.NotNull(first.ChatId);
        var second = Write(Sam, 90_000, "wow", first.ChatId);
        Assert.Equal(first.ChatId, second.ChatId);
        Assert.Single(_chats.Stored(Episode));

        // Kim, watching on her own later, starts a chat of her own: Sam's is never written to by anyone else.
        var hers = Write(Kim, 70_000, "hi", first.ChatId);
        Assert.NotEqual(first.ChatId, hers.ChatId);

        var listed = _notes.Chats(Kim, Episode).Chats;
        Assert.Equal(new[] { hers.ChatId, first.ChatId }, listed.Select(c => (Guid?)c.Id));
        Assert.Equal(new[] { "sam" }, listed[1].People);
        Assert.Equal(2, listed[1].Count);
        Assert.Equal(Pair, listed[1].GroupId);
        Assert.Empty(listed[0].People);
    }

    [Fact]
    public void AChatBuildingOnAnotherKeepsOnlyItsNewMessagesAndPlaysBothWithoutCopies()
    {
        Group(Sam, Kim);
        var tuesday = Write(Sam, 60_000, "tuesday one").ChatId!.Value;
        Write(Kim, 80_000, "tuesday two", tuesday);
        // Kim's went into a chat of her own (Tuesday's is Sam's); Sam writes again into his.
        Write(Sam, 90_000, "tuesday three", tuesday);

        // Tonight Kim replays Tuesday's and writes: only her new message is kept in the new chat.
        var tonight = Write(Kim, 70_000, "tonight", null, tuesday);
        var chat = _chats.Get(Episode, tonight.ChatId!.Value)!;
        Assert.Equal(tuesday, chat.BuildsOn);
        Assert.Equal(new[] { "tonight" }, _book.All(Episode).Where(n => n.ChatId == chat.Id).Select(n => n.Text));

        var played = _notes.Chat(Sam, Episode, chat.Id)!;
        Assert.Equal(new[] { chat.Id, tuesday }, played.Chain);
        Assert.Equal(new[] { "tuesday one", "tonight", "tuesday three" }, played.Notes.Select(n => n.Text));
        Assert.Equal(played.Notes.Count, played.Notes.Select(n => n.Id).Distinct().Count());

        var listed = _notes.Chats(Sam, Episode).Chats.Single(c => c.Id == chat.Id);
        Assert.Equal(tuesday, listed.BuildsOn);
        Assert.Equal(1, listed.Count);
    }

    [Fact]
    public void LoopsAndLongChainsStop()
    {
        Group(Sam, Kim);
        var a = Write(Sam, 10_000, "a").ChatId!.Value;
        var b = Write(Sam, 20_000, "b", null, a).ChatId!.Value;
        // A loop written straight into the store (never made by the API): reading it still ends.
        var stored = _chats.Get(Episode, a)!;
        _db.PutRecord(NoteChatBook.Kind, Episode.ToString("N"), a.ToString("N"), System.Text.Json.JsonSerializer.Serialize(stored with { BuildsOn = b }), stored.Created);
        Assert.Equal(new[] { b, a }, _chats.Chain(Episode, b));
        Assert.Equal(new[] { "a", "b" }, _notes.Chat(Sam, Episode, b)!.Notes.Select(n => n.Text));
    }

    [Fact]
    public void AChatIsListedOnlyForWhoWasInTheAudienceOfItsMessagesAndShowsOnlyThose()
    {
        Group(Kim, Jessica);
        var before = Write(Kim, 60_000, "before Sam").ChatId!.Value;
        Group(Sam, Kim, Jessica);
        var after = Write(Kim, 90_000, "with Sam", before);
        // Same chat (Kim's, building on nothing): Sam sees only what he was there for.
        Assert.Equal(before, after.ChatId);
        var listed = Assert.Single(_notes.Chats(Sam, Episode).Chats);
        Assert.Equal(1, listed.Count);
        Assert.Equal(new[] { "with Sam" }, _notes.Chat(Sam, Episode, before)!.Notes.Select(n => n.Text));

        // A stranger sees no chats and can't read one by its id.
        var stranger = Guid.NewGuid();
        Assert.Empty(_notes.Chats(stranger, Episode).Chats);
        Assert.Null(_notes.Chat(stranger, Episode, before));
    }

    [Fact]
    public void BuildingOnAChatOneCantSeeLinksNothing()
    {
        Group(Kim, Jessica);
        var theirs = Write(Kim, 60_000, "just us").ChatId!.Value;
        Group(Sam, Kim);
        var mine = Write(Sam, 70_000, "hello", null, theirs);
        Assert.Null(_chats.Get(Episode, mine.ChatId!.Value)!.BuildsOn);
        // Kim sees both, but Sam's isn't said to build on hers.
        Assert.All(_notes.Chats(Kim, Episode).Chats, c => Assert.Null(c.BuildsOn));
    }

    [Fact]
    public void OlderNotesReadAsOneChatPerSessionAndOneEarlierNotesChatPerGroupUnchanged()
    {
        Group(Sam, Kim);
        _live.In[Session] = new List<Guid> { Sam, Kim };
        // Written the older way (no chat): two outside a session, two in one.
        _notes.Pin(Sam, "sam", Episode, 10_000, ChatKind.Text, "old one", null);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Kim, "kim", Episode, 20_000, ChatKind.Text, "old two", null);
        _time.Advance(TimeSpan.FromDays(1));
        _notes.Pin(Sam, "sam", Episode, 30_000, ChatKind.Text, "live one", Session);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Kim, "kim", Episode, 40_000, ChatKind.Text, "live two", Session);
        var notesBefore = _book.All(Episode).Select(n => System.Text.Json.JsonSerializer.Serialize(n)).ToList();

        var chats = _notes.Chats(Kim, Episode).Chats;
        Assert.Equal(2, chats.Count);
        Assert.Equal(Session, chats[0].SessionId);
        Assert.False(chats[0].Earlier);
        Assert.True(chats[1].Earlier);
        Assert.Equal(Pair, chats[1].GroupId);
        Assert.Equal(new[] { "live one", "live two" }, _notes.Chat(Kim, Episode, chats[0].Id)!.Notes.Select(n => n.Text));
        Assert.Equal(new[] { "old one", "old two" }, _notes.Chat(Sam, Episode, chats[1].Id)!.Notes.Select(n => n.Text));
        // The same ids every time, and nothing rewritten.
        Assert.Equal(chats.Select(c => c.Id), _notes.Chats(Sam, Episode).Chats.Select(c => c.Id));
        Assert.Equal(notesBefore, _book.All(Episode).Select(n => System.Text.Json.JsonSerializer.Serialize(n)));
        Assert.Empty(_chats.Stored(Episode));
        // The older endpoint still has them all.
        Assert.Equal(4, _notes.Page(Kim, Episode).Notes.Count);
    }

    [Fact]
    public async Task ASessionSharesOneChatAndAnEarlierChatPickedThereReachesEveryoneOnlyForTheSession()
    {
        // Sam and Kim (and Hidden, a private account) wrote on Tuesday, before chat histories: one "Earlier notes" chat.
        Group(Sam, Kim, Hidden);
        _notes.Pin(Kim, "kim", Episode, 60_000, ChatKind.Text, "the kitchen!", null);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Hidden, "quiet", Episode, 65_000, ChatKind.Text, "yes chef", null);
        var tuesday = Assert.Single(_notes.Chats(Kim, Episode).Chats).Id;

        // Tonight Kim watches with Jessica (in no group) and Sam.
        _live.In[Session] = new List<Guid> { Kim, Jessica, Sam };
        Assert.Throws<NoteForbiddenException>(() => _notes.PlayAlong(Guid.NewGuid(), Session, Episode, tuesday));
        Assert.Throws<NoteForbiddenException>(() => _notes.PlayAlong(Jessica, Session, Episode, tuesday));
        var before = (await _notes.Live(Jessica, Session, -1, TimeSpan.Zero, default)).Brought;
        _notes.PlayAlong(Kim, Session, Episode, tuesday);
        Assert.True((await _notes.Live(Jessica, Session, -1, TimeSpan.Zero, default)).Brought > before);

        var forJessica = _notes.SessionChat(Jessica, Session, Episode);
        Assert.Equal(tuesday, forJessica.Along);
        Assert.Equal(Kim, forJessica.By);
        Assert.Equal(new[] { "the kitchen!" }, forJessica.Notes.Select(n => n.Text));
        Assert.All(forJessica.Notes, n => Assert.True(n.Brought));
        // Sam was there for both: his, not brought.
        Assert.Equal(new[] { "the kitchen!", "yes chef" }, _notes.SessionChat(Sam, Session, Episode).Notes.Where(n => !n.Brought).Select(n => n.Text));

        // Both write: one chat, the session's, building on Tuesday's.
        var a = Write(Jessica, 70_000, "hello", null, null, Session);
        var b = Write(Sam, 75_000, "hey", Guid.NewGuid(), null, Session);
        Assert.Equal(a.ChatId, b.ChatId);
        Assert.Equal(a.ChatId, _notes.SessionChat(Kim, Session, Episode).ChatId);
        Assert.Equal(tuesday, _chats.Get(Episode, a.ChatId!.Value)!.BuildsOn);

        // After the session, Jessica keeps tonight's (she was there) but never Tuesday's.
        _live.In.Clear();
        Assert.Throws<NoteForbiddenException>(() => _notes.SessionChat(Jessica, Session, Episode));
        var hers = Assert.Single(_notes.Chats(Jessica, Episode).Chats);
        Assert.Null(hers.BuildsOn);
        Assert.Equal(new[] { "hello", "hey" }, _notes.Chat(Jessica, Episode, hers.Id)!.Notes.Select(n => n.Text));
        // Sam, who can see Tuesday's, plays tonight's with it.
        Assert.Equal(new[] { "the kitchen!", "yes chef", "hello", "hey" }, _notes.Chat(Sam, Episode, hers.Id)!.Notes.Select(n => n.Text));
    }

    [Fact]
    public void PickingAgainOrStoppingIsForAnyoneThereAndOnlyWhileThePickerStays()
    {
        Group(Sam, Kim);
        var tuesday = Write(Sam, 60_000, "one").ChatId!.Value;
        _live.In[Session] = new List<Guid> { Sam, Jessica };
        _notes.PlayAlong(Sam, Session, Episode, tuesday);
        Assert.Single(_notes.SessionChat(Jessica, Session, Episode).Notes);
        _live.In[Session] = new List<Guid> { Jessica, Kim };
        Assert.Empty(_notes.SessionChat(Jessica, Session, Episode).Notes);
        _live.In[Session].Add(Sam);
        _notes.PlayAlong(Jessica, Session, Episode, null);
        Assert.Null(_notes.SessionChat(Sam, Session, Episode).Along);
    }
}
