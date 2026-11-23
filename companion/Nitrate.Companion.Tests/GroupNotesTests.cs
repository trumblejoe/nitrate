using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Group chat as the API serves it: one note per message, live sessions, brought notes.</summary>
public sealed class GroupNotesTests : IDisposable
{
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Jessica = Guid.NewGuid();
    private static readonly Guid Hidden = Guid.NewGuid();
    private static readonly Guid Episode = Guid.NewGuid();
    private static readonly Guid Pair = Guid.NewGuid();
    private static readonly Guid Session = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-20T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly CompanionDb _db = new(":memory:");
    private readonly TimelineNoteBook _book;
    private readonly ChatHub _hub;
    private readonly Live _live = new();
    private readonly Dictionary<Guid, List<GroupMembers>> _groups = new();
    private readonly GroupNotes _notes;
    private bool _keep = true;

    public GroupNotesTests()
    {
        _book = new TimelineNoteBook(_db, _time);
        _hub = new ChatHub(_time);
        _notes = new GroupNotes(_book, _hub, _live, (user, _) => _groups.GetValueOrDefault(user) ?? new List<GroupMembers>(), id => id == Hidden, () => _keep, _time);
    }

    public void Dispose() => _db.Dispose();

    private sealed class Live : ILiveSessions
    {
        public Dictionary<Guid, List<Guid>> In { get; } = new();

        public Guid? Item { get; set; }

        public IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller) =>
            In.TryGetValue(sessionId, out var people) && people.Contains(caller) ? people.ToList() : null;

        public Guid? Playing(Guid sessionId, Guid caller) => Item;
    }

    /// <summary>Sam and Kim (and Hidden, a private account) are a watch group on the show.</summary>
    private void Group(params Guid[] members)
    {
        _groups.Clear();
        foreach (var m in members)
        {
            _groups[m] = new List<GroupMembers> { new(Pair, "The Bear", members) };
        }
    }

    private Task<ChatPage> Poll(Guid who, long after = -1) => _notes.Live(who, Session, after, TimeSpan.Zero, default);

    [Fact]
    public async Task AMessageInALiveSessionIsWrittenOnceAndReachesTheSessionAtOnce()
    {
        Group(Sam, Kim);
        _live.In[Session] = new List<Guid> { Sam, Kim, Jessica };
        var sent = _notes.Pin(Sam, "sam", Episode, 754_000, ChatKind.Text, "that score", Session);

        // One record, with its audience: the session (it's for the pair: Kim is there) and the group.
        var kept = Assert.Single(_book.All(Episode));
        Assert.Equal(sent.Id, kept.Id);
        Assert.Equal(Session, kept.SessionId);
        Assert.Equal(new[] { Sam, Kim, Jessica }.OrderBy(x => x), kept.Audience!.OrderBy(x => x));
        Assert.Equal(new[] { Pair }, kept.Shares.Select(s => s.GroupId));

        // The same note, live, for everyone there.
        foreach (var who in new[] { Kim, Jessica })
        {
            var live = Assert.Single((await Poll(who)).Messages);
            Assert.Equal(sent.Id, live.NoteId);
            Assert.Equal(Episode, live.ItemId);
            Assert.Equal(754_000, live.PositionMs);
        }

        // And later, in their history: Jessica was there for it though she's in no group.
        Assert.Single(_notes.Page(Jessica, Episode).Notes);
        Assert.Single(_notes.Page(Kim, Episode).Notes);
    }

    [Fact]
    public async Task TheOlderChatEndpointWritesOneNoteAndAnOlderAppsSecondCopyIsTheSame()
    {
        Group(Sam, Kim);
        _live.In[Session] = new List<Guid> { Sam, Kim };
        _live.Item = Episode;
        var live = _notes.SendLive(Sam, "sam", Session, ChatKind.Reaction, "😂", 40_000);
        Assert.NotNull(live.NoteId);
        // An older app pinned it too, straight after: no second record.
        var pinned = _notes.Pin(Sam, "sam", Episode, 40_000, ChatKind.Reaction, "😂", null);
        Assert.Equal(live.NoteId, pinned.Id);
        Assert.Single(_book.All(Episode));
        Assert.Single((await Poll(Kim)).Messages);

        // Nothing playing: delivered to who's there, kept nowhere.
        _live.Item = null;
        _notes.SendLive(Sam, "sam", Session, ChatKind.Text, "brb", null);
        Assert.Single(_book.All(Episode));
        Assert.Equal(2, (await Poll(Kim)).Messages.Count);
    }

    [Fact]
    public async Task SomeoneJoiningTheSessionLaterGetsNothingFromBeforeAndCantReadIt()
    {
        Group(Sam, Kim);
        _live.In[Session] = new List<Guid> { Sam, Kim };
        _notes.Pin(Sam, "sam", Episode, 10_000, ChatKind.Text, "before", Session);
        _live.In[Session].Add(Jessica);
        // The backlog on joining leaves out what she wasn't there for; what follows reaches her.
        Assert.Empty((await Poll(Jessica)).Messages);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Kim, "kim", Episode, 20_000, ChatKind.Text, "after", Session);
        Assert.Equal(new[] { "after" }, (await Poll(Jessica)).Messages.Select(m => m.Text));
        Assert.Equal(new[] { "after" }, _notes.Page(Jessica, Episode).Notes.Select(n => n.Text));
        // Outside the session there's no reading its chat.
        await Assert.ThrowsAsync<NoteForbiddenException>(() => _notes.Live(Guid.NewGuid(), Session, -1, TimeSpan.Zero, default));
    }

    [Fact]
    public void ASessionNotForTheGroupStaysWithItsParticipants()
    {
        Group(Sam, Kim);
        // Sam watches with Jessica; nobody else from his group is there.
        _live.In[Session] = new List<Guid> { Sam, Jessica };
        var note = _notes.Pin(Sam, "sam", Episode, 5_000, ChatKind.Text, "hi", Session);
        Assert.Empty(note.GroupIds);
        Assert.Single(_notes.Page(Jessica, Episode).Notes);
        Assert.Empty(_notes.Page(Kim, Episode).Notes);
        // Not in the session: refused (and with no group, nothing to write to).
        Assert.Throws<NoteForbiddenException>(() => _notes.Pin(Jessica, "jessica", Episode, 5_000, ChatKind.Text, "hi", Guid.NewGuid()));
        Assert.Throws<NoteForbiddenException>(() => _notes.Pin(Jessica, "jessica", Episode, 5_000, ChatKind.Text, "hi", null));
    }

    [Fact]
    public void WithoutKeepingChatALiveMessageIsKeptOnlyForTheGroup()
    {
        _keep = false;
        Group(Sam, Kim);
        _live.In[Session] = new List<Guid> { Sam, Kim, Jessica };
        _notes.Pin(Sam, "sam", Episode, 5_000, ChatKind.Text, "hi", Session);
        Assert.Equal(new[] { Sam, Kim }, Assert.Single(_book.All(Episode)).Audience);
        Assert.Empty(_notes.Page(Jessica, Episode).Notes);
    }

    [Fact]
    public async Task BroughtNotesReachALaterJoinerOnlyWhileTheSessionLasts()
    {
        // Sam and Kim watched separately and wrote notes; Hidden (private) wrote one too.
        Group(Sam, Kim, Hidden);
        _notes.Pin(Kim, "kim", Episode, 60_000, ChatKind.Text, "the kitchen!", null);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Hidden, "quiet", Episode, 70_000, ChatKind.Text, "yes chef", null);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Sam, "sam", Episode, 80_000, ChatKind.Reaction, "🔥", null);

        // Now Sam rewatches with Jessica (in no group) and brings the pair's notes in.
        _live.In[Session] = new List<Guid> { Sam };
        Assert.Throws<NoteForbiddenException>(() => _notes.Bring(Jessica, Session, Pair));
        var before = (await Poll(Sam)).Brought;
        _notes.Bring(Sam, Session, Pair);
        Assert.True((await Poll(Sam)).Brought > before);

        // Jessica joins later: she gets them for the session (not the private account's), marked brought, naming no group.
        _live.In[Session].Add(Jessica);
        var brought = _notes.Brought(Jessica, Session, Episode);
        Assert.Equal(Sam, brought.By);
        Assert.Equal(new[] { "the kitchen!", "🔥" }, brought.Notes.Select(n => n.Text));
        Assert.All(brought.Notes, n => Assert.True(n.Brought));
        Assert.All(brought.Notes, n => Assert.Empty(n.GroupIds));
        // But they're not hers: GET /Nitrate/Notes doesn't have them, during the session or after.
        Assert.Empty(_notes.Page(Jessica, Episode).Notes);
        _live.In[Session].Remove(Jessica);
        Assert.Throws<NoteForbiddenException>(() => _notes.Brought(Jessica, Session, Episode));
        Assert.Empty(_notes.Page(Jessica, Episode).Notes);

        // Only while the one who brought them is there; Sam stopping ends it too.
        _live.In[Session] = new List<Guid> { Jessica, Kim };
        Assert.Empty(_notes.Brought(Jessica, Session, Episode).Notes);
        _live.In[Session].Add(Sam);
        Assert.Equal(2, _notes.Brought(Jessica, Session, Episode).Notes.Count);
        _notes.Bring(Kim, Session, null);
        Assert.Equal(2, _notes.Brought(Jessica, Session, Episode).Notes.Count);
        _notes.Bring(Sam, Session, null);
        Assert.Empty(_notes.Brought(Jessica, Session, Episode).Notes);
    }

    [Fact]
    public void OnlyNotesTheBringerMayReadAreBrought()
    {
        // Kim wrote while Sam wasn't in the group yet; then Sam joined.
        Group(Kim, Jessica);
        _notes.Pin(Kim, "kim", Episode, 60_000, ChatKind.Text, "before Sam", null);
        Group(Sam, Kim, Jessica);
        _time.Advance(TimeSpan.FromMinutes(1));
        _notes.Pin(Kim, "kim", Episode, 90_000, ChatKind.Text, "with Sam", null);
        var stranger = Guid.NewGuid();
        _live.In[Session] = new List<Guid> { Sam, stranger };
        _notes.Bring(Sam, Session, Pair);
        Assert.Equal(new[] { "with Sam" }, _notes.Brought(stranger, Session, Episode).Notes.Select(n => n.Text));
        // A group Sam was never in brings nothing.
        _notes.Bring(Sam, Session, Guid.NewGuid());
        Assert.Empty(_notes.Brought(stranger, Session, Episode).Notes);
    }

    [Fact]
    public void APrivateAccountsBroughtNoteReachesOnlyItsOwnAudience()
    {
        // Hidden (a private account) wrote a note to the group; Sam brings the group's notes into a session.
        Group(Sam, Kim, Hidden);
        _notes.Pin(Hidden, "quiet", Episode, 70_000, ChatKind.Text, "yes chef", null);
        _live.In[Session] = new List<Guid> { Sam, Kim, Jessica };
        _notes.Bring(Sam, Session, Pair);
        // Kim was there for it (the group it went to): it reaches her in the session.
        Assert.Equal(new[] { "yes chef" }, _notes.Brought(Kim, Session, Episode).Notes.Select(n => n.Text));
        // Jessica, outside its audience, never gets it, nor learns it exists.
        Assert.Empty(_notes.Brought(Jessica, Session, Episode).Notes);
        Assert.Empty(_notes.Page(Jessica, Episode).Notes);
    }
}
