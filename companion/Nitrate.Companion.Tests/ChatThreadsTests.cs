using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>One conversation per watch group and title (threads), the creator starting new ones, older ones replayed.</summary>
public sealed class ChatThreadsTests : IDisposable
{
    private static readonly Guid Sam = Guid.NewGuid();
    private static readonly Guid Kim = Guid.NewGuid();
    private static readonly Guid Jessica = Guid.NewGuid();
    private static readonly Guid Hidden = Guid.NewGuid();
    private static readonly Guid Show = Guid.NewGuid();
    private static readonly Guid Episode = Guid.NewGuid();
    private static readonly Guid Episode2 = Guid.NewGuid();
    private static readonly Guid Film = Guid.NewGuid();
    private static readonly Guid Pair = Guid.NewGuid();
    private static readonly Guid Session = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-17T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly CompanionDb _db = new(":memory:");
    private readonly TimelineNoteBook _book;
    private readonly ChatHub _hub;
    private readonly Live _live = new();
    private readonly Dictionary<Guid, List<GroupMembers>> _groups = new();
    private readonly GroupNotes _notes;
    private readonly ThreadBook _threads;

    public ChatThreadsTests()
    {
        _book = new TimelineNoteBook(_db, _time);
        _hub = new ChatHub(_time);
        _threads = new ThreadBook(_db, _book, _time);
        _notes = new GroupNotes(_book, _hub, _live, (user, item) => (_groups.GetValueOrDefault(user) ?? new List<GroupMembers>()).Select(g => g with { TitleId = item == Film ? Film : Show }).ToList(), id => id == Hidden, () => true, _time, _threads);
    }

    public void Dispose() => _db.Dispose();

    private sealed class Live : ILiveSessions
    {
        public Dictionary<Guid, List<Guid>> In { get; } = new();

        public IReadOnlyList<Guid>? Participants(Guid sessionId, Guid caller) =>
            In.TryGetValue(sessionId, out var people) && people.Contains(caller) ? people.ToList() : null;

        public Guid? Playing(Guid sessionId, Guid caller) => null;
    }

    /// <summary>A group made by the first of <paramref name="members"/>, on the show and the film.</summary>
    private void Group(params Guid[] members)
    {
        _groups.Clear();
        foreach (var m in members)
        {
            _groups[m] = new List<GroupMembers> { new(Pair, "The Bear", members, Show, members[0]) };
        }
    }

    private NoteView Write(Guid who, Guid item, long at, string text, Guid? session = null)
    {
        _time.Advance(TimeSpan.FromSeconds(30));
        return _notes.Pin(who, who == Sam ? "sam" : who == Kim ? "kim" : who == Hidden ? "quiet" : "jessica", item, at, ChatKind.Text, text, session);
    }

    [Fact]
    public void TheGroupHasOneConversationAcrossTheShowWhoeverWritesWhenever()
    {
        Group(Sam, Kim);
        Assert.Empty(Assert.Single(_notes.Threads(Sam, Episode).Threads).Threads);

        var a = Write(Sam, Episode, 60_000, "here we go");
        var b = Write(Kim, Episode, 90_000, "wow");
        var c = Write(Kim, Episode2, 10_000, "next one");
        Assert.NotNull(a.ChatId);
        Assert.Equal(a.ChatId, b.ChatId);
        Assert.Equal(a.ChatId, c.ChatId);

        // Kim, watching episode 1 later, gets the group's conversation on it (to pop up as she reaches it).
        var page = _notes.Threads(Kim, Episode);
        var group = Assert.Single(page.Threads);
        var thread = Assert.Single(group.Threads);
        Assert.True(thread.Current);
        Assert.Equal(2, thread.Count);
        Assert.Equal(new[] { "here we go", "wow" }, page.Notes.Select(n => n.Text));
        // The film is another title: another conversation.
        Assert.NotEqual(a.ChatId, Write(Sam, Film, 5_000, "film").ChatId);
    }

    [Fact]
    public void OnlyTheCreatorStartsANewThreadAndTheOlderOneCanBeReplayed()
    {
        Group(Sam, Kim);
        var first = Write(Kim, Episode, 60_000, "first time").ChatId!.Value;
        Assert.False(Assert.Single(_notes.Threads(Kim, Episode).Threads).CanStart);
        Assert.True(Assert.Single(_notes.Threads(Sam, Episode).Threads).CanStart);
        Assert.Throws<NoteForbiddenException>(() => _notes.StartThread(Kim, Episode, Pair));

        _time.Advance(TimeSpan.FromDays(300));
        var rewatch = _notes.StartThread(Sam, Episode, Pair);
        Assert.Equal(rewatch.Id, Write(Kim, Episode, 61_000, "second time").ChatId);

        var page = _notes.Threads(Kim, Episode);
        var threads = Assert.Single(page.Threads).Threads;
        Assert.Equal(new[] { rewatch.Id, first }, threads.Select(t => t.Id));
        Assert.Equal(new[] { true, false }, threads.Select(t => t.Current));
        // Only the current thread pops up unasked; the older one is replayed when picked.
        Assert.Equal(new[] { "second time" }, page.Notes.Select(n => n.Text));
        Assert.Equal(new[] { "first time" }, _notes.ThreadNotes(Kim, Episode, first)!.Select(n => n.Text));
        // Someone outside the group gets nothing of it, nor learns it exists.
        Assert.Null(_notes.ThreadNotes(Jessica, Episode, first));
    }

    [Fact]
    public void AThreadNeverMakesAMessageReadableToSomeoneWhoWasntThere()
    {
        Group(Sam, Kim);
        Write(Sam, Episode, 60_000, "before Jessica");
        // Jessica joins later: the same thread, but only what's written from now is hers.
        Group(Sam, Kim, Jessica);
        Write(Kim, Episode, 70_000, "after Jessica");
        Assert.Equal(new[] { "after Jessica" }, _notes.Threads(Jessica, Episode).Notes.Select(n => n.Text));
        Assert.Equal(2, _notes.Threads(Kim, Episode).Notes.Count);
        Assert.Equal(1, Assert.Single(Assert.Single(_notes.Threads(Jessica, Episode).Threads).Threads).Count);
    }

    [Fact]
    public async Task ASessionWritesIntoTheGroupsThreadAndAnOlderOneReplaysForEveryoneThereOnly()
    {
        Group(Sam, Kim, Hidden);
        var older = Write(Kim, Episode, 60_000, "the kitchen!").ChatId!.Value;
        _time.Advance(TimeSpan.FromMinutes(1));
        Write(Hidden, Episode, 65_000, "yes chef");
        _notes.StartThread(Sam, Episode, Pair);

        // Tonight Kim watches with Jessica (in no group) and Sam.
        _live.In[Session] = new List<Guid> { Kim, Jessica, Sam };
        Assert.Throws<NoteForbiddenException>(() => _notes.PlayAlong(Jessica, Session, Episode, older));
        var before = (await _notes.Live(Jessica, Session, -1, TimeSpan.Zero, default)).Brought;
        _notes.PlayAlong(Kim, Session, Episode, older);
        Assert.True((await _notes.Live(Jessica, Session, -1, TimeSpan.Zero, default)).Brought > before);

        var forJessica = _notes.SessionChat(Jessica, Session, Episode);
        Assert.Equal(older, forJessica.Along);
        Assert.Equal(Kim, forJessica.By);
        // Never the private account's words to someone who wasn't there for them; brought, never kept.
        Assert.Equal(new[] { "the kitchen!" }, forJessica.Notes.Select(n => n.Text));
        Assert.All(forJessica.Notes, n => Assert.True(n.Brought));

        // What's written in the session goes into the group's current thread.
        var a = Write(Jessica, Episode, 70_000, "hello", Session);
        var b = Write(Sam, Episode, 75_000, "hey", Session);
        Assert.Equal(a.ChatId, b.ChatId);
        Assert.Equal(_threads.Current(Pair, Show)!.Id, a.ChatId);
        Assert.Equal(a.ChatId, _notes.SessionChat(Kim, Session, Episode).ChatId);

        // After the session, Jessica keeps nothing of the older thread.
        _live.In.Clear();
        Assert.Throws<NoteForbiddenException>(() => _notes.SessionChat(Jessica, Session, Episode));
        Assert.Null(_notes.ThreadNotes(Jessica, Episode, older));
    }

    [Fact]
    public void AFavouriteIsEachPersonsOwnAndOnlyForTheirGroupsThreads()
    {
        Group(Sam, Kim);
        var thread = Write(Sam, Episode, 60_000, "remember this").ChatId!.Value;
        _notes.FavouriteThread(Kim, Episode, thread, true);
        Assert.True(Assert.Single(Assert.Single(_notes.Threads(Kim, Episode).Threads).Threads).Favourite);
        Assert.False(Assert.Single(Assert.Single(_notes.Threads(Sam, Episode).Threads).Threads).Favourite);
        Assert.Throws<NoteForbiddenException>(() => _notes.FavouriteThread(Jessica, Episode, thread, true));
        _notes.FavouriteThread(Kim, Episode, thread, false);
        Assert.Empty(_threads.Favourites(thread));
    }

    [Fact]
    public void ThreadsGoTenYearsAfterTheirNewestMessageUnlessAFavouriteOrRememberedSomeone()
    {
        var keeping = new ChatKeeping(_db, _book, _threads);
        Assert.Equal(ChatKeeping.DefaultDays, keeping.Settings.DeleteAfterDays);
        Group(Sam, Kim);
        var old = Write(Sam, Episode, 60_000, "long ago").ChatId!.Value;
        _notes.StartThread(Sam, Episode, Pair);
        var favourite = Write(Kim, Film, 10_000, "a favourite").ChatId!.Value;
        _notes.FavouriteThread(Sam, Film, favourite, true);

        _time.Advance(TimeSpan.FromDays(ChatKeeping.DefaultDays - 1));
        // A conversation still going is never cut: a message now keeps the whole thread.
        var current = Write(Kim, Episode2, 5_000, "still going").ChatId!.Value;
        Assert.Equal(0, keeping.Prune(_time.GetUtcNow()));
        _time.Advance(TimeSpan.FromDays(2));
        Assert.Equal(1, keeping.Prune(_time.GetUtcNow()));
        var left = _threads.All().Select(t => t.Id).ToList();
        Assert.DoesNotContain(old, left);
        Assert.Contains(favourite, left);
        Assert.Contains(current, left);
        Assert.Null(_notes.ThreadNotes(Sam, Episode, old));
    }

    [Fact]
    public void ARememberedPersonsThreadsStayAndZeroMeansNever()
    {
        var keeping = new ChatKeeping(_db, _book, _threads);
        keeping.Save(new ChatKeepingSettings(7, new[] { Kim, Kim }));
        Assert.Equal(new[] { Kim }, keeping.Settings.Kept);
        Group(Sam, Kim);
        var withKim = Write(Kim, Episode, 60_000, "kim").ChatId!.Value;
        var samsOwn = Write(Sam, Film, 90_000, "just sam").ChatId!.Value;
        _time.Advance(TimeSpan.FromDays(8));
        keeping.Prune(_time.GetUtcNow());
        var left = _threads.All().Select(t => t.Id).ToList();
        Assert.Contains(withKim, left);
        Assert.DoesNotContain(samsOwn, left);

        keeping.Save(new ChatKeepingSettings(0));
        Assert.Null(keeping.Days(keeping.Settings));
        _time.Advance(TimeSpan.FromDays(365 * 30));
        Assert.Equal(0, keeping.Prune(_time.GetUtcNow()));
    }

    [Fact]
    public void ChatHistoriesKeptBeforeThreadsAreDeletedOnce()
    {
        _db.PutRecord("notechat", Episode.ToString("N"), "a", "{}", 1);
        _db.PutRecord("note", Episode.ToString("N"), "b", "{}", 1);
        _threads.ForgetChatHistoriesOnce();
        Assert.Empty(_db.Records("notechat", null));
        Assert.Empty(_db.Records("note", null));
        // Only once: notes written afterwards stay.
        Group(Sam, Kim);
        Write(Sam, Episode, 1_000, "new");
        _threads.ForgetChatHistoriesOnce();
        Assert.Single(_book.All(Episode));
    }

    [Fact]
    public void TheDashboardsSettingsSayWhatIsSetAndSaveOnlyWhatChanged()
    {
        var keeping = new ChatKeeping(_db, _book, _threads);
        keeping.Save(new ChatKeepingSettings(14, new[] { Hidden }));
        var page = Nitrate.Companion.Api.SettingsController.Page(keeping.Settings, []);
        var fields = page.Sections.First().Fields;
        Assert.Equal(14, fields.Single(f => f.Key == Nitrate.Companion.Api.SettingsController.ChatDays).Value.GetInt32());
        var users = fields.Single(f => f.Key == Nitrate.Companion.Api.SettingsController.ChatKeepFrom);
        Assert.Equal("users", users.Type);
        Assert.Equal(new[] { Hidden.ToString("D") }, users.Value.EnumerateArray().Select(v => v.GetString()));
    }
}
