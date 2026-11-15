using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Channels;
using Xunit;

namespace Nitrate.Companion.Tests;

public class ChannelTests
{
    private static readonly Guid Admin = Guid.NewGuid();
    private static readonly Guid Kid = Guid.NewGuid();
    private static readonly Guid Guest = Guid.NewGuid();
    private static readonly Guid ShowA = Guid.NewGuid();
    private static readonly Guid ShowB = Guid.NewGuid();
    private static readonly Guid Cartoons = Guid.NewGuid();

    // Saturday 2026-11-14, 07:10 UTC.
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-14T07:10:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly FakeLibrary _library = new();
    private readonly MemoryChannelStorage _storage = new();

    private sealed class MemoryChannelStorage : IChannelStorage
    {
        public Dictionary<Guid, StoredChannel> Saved { get; } = new();

        public IEnumerable<StoredChannel> LoadAll() => Saved.Values;

        public void Save(StoredChannel channel) => Saved[channel.Id] = channel;

        public void Delete(Guid id) => Saved.Remove(id);
    }

    private sealed class FakeLibrary : IChannelLibrary
    {
        public Dictionary<Guid, List<PlayItem>> Sources { get; } = new();

        public Dictionary<Guid, string> Names { get; } = new();

        public HashSet<Guid> HiddenFromKid { get; } = new();

        public List<Guid> Show(Guid id, string name, int episodes, int minutes)
        {
            var list = Enumerable.Range(1, episodes).Select(_ => new PlayItem(Guid.NewGuid(), minutes * 60_000L)).ToList();
            Sources[id] = list;
            for (var i = 0; i < list.Count; i++)
            {
                Names[list[i].Id] = $"{name} {i + 1}";
            }

            return list.Select(x => x.Id).ToList();
        }

        public IReadOnlyList<PlayItem> Expand(ChannelSource source) => Sources.GetValueOrDefault(source.Id) ?? new List<PlayItem>();

        public bool CanSee(Guid itemId, Guid userId) => userId != Kid || !HiddenFromKid.Contains(itemId);

        public ProgramLabel? Describe(Guid itemId) => Names.TryGetValue(itemId, out var n) ? new ProgramLabel(n, null, "Episode") : null;
    }

    private ChannelHub Hub() => new(_storage, _library, _time, TimeZoneInfo.Utc);

    private static ChannelEdit Edit(params Guid[] shows) => new(
        "Comfort TV", 3, shows.Select(s => new ChannelSource(SourceKind.Series, s, "Show")).ToList(), ChannelMix.Rotate, 30, null, true, null);

    private List<string> Titles(IEnumerable<GuideProgram> programs) => programs.Select(p => p.Title).ToList();

    [Fact]
    public void ShowsTakeTurnsInOrderStartingOnTheHalfHour()
    {
        _library.Show(ShowA, "A", 5, 22);
        _library.Show(ShowB, "B", 5, 45);
        var hub = Hub();
        var c = hub.Create(Edit(ShowA, ShowB), Admin, true);
        var programs = c.Playout.Programs.Take(5).ToList();
        Assert.Equal(new[] { "A 1", "B 1", "A 2", "B 2", "A 3" }, programs.Select(p => _library.Names[p.ItemId]));
        // Begins on the boundary before now, and each start is on :00 or :30.
        Assert.Equal(DateTimeOffset.Parse("2026-11-14T07:00:00Z", System.Globalization.CultureInfo.InvariantCulture), programs[0].Start);
        Assert.All(programs, p => Assert.Equal(0, p.Start.Minute % 30));
        Assert.Equal(programs[0].Start.AddMinutes(30), programs[1].Start);
        Assert.Equal(programs[1].Start.AddMinutes(60), programs[2].Start);
    }

    [Fact]
    public void InSequenceAShowRunsToItsEndBeforeTheNext()
    {
        _library.Show(ShowA, "A", 3, 30);
        _library.Show(ShowB, "B", 2, 30);
        var c = Hub().Create(Edit(ShowA, ShowB) with { Mix = ChannelMix.Sequential }, Admin, true);
        Assert.Equal(new[] { "A 1", "A 2", "A 3", "B 1", "B 2", "A 1" }, c.Playout.Programs.Take(6).Select(p => _library.Names[p.ItemId]));
    }

    [Fact]
    public void ATimeSlotPlaysItsOwnLineup()
    {
        _library.Show(ShowA, "A", 50, 30);
        _library.Show(Cartoons, "Toon", 50, 30);
        var mornings = new ChannelBlock("Saturday cartoons", new[] { 6 }, 8 * 60, 10 * 60, new[] { new ChannelSource(SourceKind.Series, Cartoons, "Toons") });
        var c = Hub().Create(Edit(ShowA) with { Blocks = new[] { mornings } }, Admin, true);
        var at = (string time) => c.Playout.Programs.First(p => p.Start == DateTimeOffset.Parse("2026-11-14T" + time + ":00Z", System.Globalization.CultureInfo.InvariantCulture));
        Assert.Equal("A 2", _library.Names[at("07:30").ItemId]);
        Assert.Equal("Toon 1", _library.Names[at("08:00").ItemId]);
        Assert.Equal("Saturday cartoons", at("09:30").Block);
        Assert.Equal("Toon 4", _library.Names[at("09:30").ItemId]);
        // After the slot the show carries on where it was.
        Assert.Equal("A 3", _library.Names[at("10:00").ItemId]);
        // Next Saturday the cartoons carry on too.
        var nextWeek = Hub().Guide(Admin, true, DateTimeOffset.Parse("2026-11-21T08:00:00Z", System.Globalization.CultureInfo.InvariantCulture), DateTimeOffset.Parse("2026-11-21T08:30:00Z", System.Globalization.CultureInfo.InvariantCulture));
        Assert.Equal("Toon 5", nextWeek.Single().Programs.Single().Title);
    }

    [Fact]
    public void TuningInStartsWhatsOnFromTheBeginningThenKeepsOrder()
    {
        _library.Show(ShowA, "A", 10, 30);
        var hub = Hub();
        var c = hub.Create(Edit(ShowA), Admin, true);
        _time.Advance(TimeSpan.FromMinutes(45)); // 07:55: "A 2" started at 07:30.
        var tuned = hub.Tune(c.Id, Guest, false, 3);
        Assert.Equal(new[] { "A 2", "A 3", "A 4" }, Titles(tuned));
        Assert.True(tuned[0].Start < _time.GetUtcNow());
    }

    [Fact]
    public void TheGuideIsTheSameForEveryoneAndKeptBetweenRestarts()
    {
        _library.Show(ShowA, "A", 10, 30);
        _library.Show(ShowB, "B", 10, 30);
        var c = Hub().Create(Edit(ShowA, ShowB) with { Mix = ChannelMix.Shuffle }, Admin, true);
        var from = _time.GetUtcNow();
        var first = Titles(Hub().Guide(Admin, true, from, from.AddHours(6)).Single().Programs);
        var again = Titles(Hub().Guide(Guest, false, from, from.AddHours(6)).Single().Programs);
        Assert.Equal(first, again);
        Assert.Equal(c.Id, _storage.Saved.Keys.Single());
    }

    [Fact]
    public void AShuffledShowPlaysEveryEpisodeOnceARound()
    {
        var ids = _library.Show(ShowA, "A", 8, 30);
        var c = Hub().Create(Edit() with { Sources = new[] { new ChannelSource(SourceKind.Series, ShowA, "A", Shuffle: true) } }, Admin, true);
        var round = c.Playout.Programs.Take(8).Select(p => p.ItemId).ToList();
        Assert.Equal(ids.OrderBy(x => x), round.OrderBy(x => x));
        Assert.NotEqual(ids, round);
    }

    [Fact]
    public void ChangingTheLineupKeepsWhatsOnAndEachShowsPlace()
    {
        _library.Show(ShowA, "A", 20, 30);
        _library.Show(ShowB, "B", 20, 30);
        var hub = Hub();
        var c = hub.Create(Edit(ShowA), Admin, true);
        _time.Advance(TimeSpan.FromMinutes(65)); // 08:15: "A 3" is on.
        var updated = hub.Update(c.Id, Edit(ShowA, ShowB), true);
        var from = _time.GetUtcNow();
        Assert.Equal(new[] { "A 3", "B 1", "A 4", "B 2", "A 5" }, Titles(hub.Guide(Admin, true, from, from.AddHours(2)).Single().Programs));
        var restarted = hub.Update(c.Id, Edit(ShowA), true, startOver: true);
        Assert.Equal("A 1", _library.Names[restarted.Playout.Programs[0].ItemId]);
        Assert.Equal(updated.Id, restarted.Id);
    }

    [Fact]
    public void ChannelsAreSeenOnlyByThoseGrantedThem()
    {
        _library.Show(ShowA, "A", 4, 30);
        var hub = Hub();
        var family = hub.Create(Edit(ShowA) with { Everyone = false, Users = new[] { Kid } }, Admin, true);
        Assert.Single(hub.List(Kid, false));
        Assert.Empty(hub.List(Guest, false));
        Assert.Single(hub.List(Guest, true));
        // Not in the guide either, and asking for it by id is the same as it not existing.
        var now = _time.GetUtcNow();
        Assert.Empty(hub.Guide(Guest, false, now, now.AddHours(3)));
        Assert.Single(hub.Guide(Kid, false, now, now.AddHours(3)));
        Assert.Equal(ChannelError.NotFound, Assert.Throws<ChannelException>(() => hub.Get(family.Id, Guest, false)).Error);
        Assert.Equal(ChannelError.NotFound, Assert.Throws<ChannelException>(() => hub.Tune(family.Id, Guest, false, 5)).Error);
        Assert.Equal(ChannelError.NotAdmin, Assert.Throws<ChannelException>(() => hub.Update(family.Id, Edit(ShowA), false)).Error);
        Assert.Equal(ChannelError.NotAdmin, Assert.Throws<ChannelException>(() => hub.Create(Edit(ShowA), Kid, false)).Error);
    }

    [Fact]
    public void TitlesAViewerCantWatchAreMarkedAndSkippedWhenTuning()
    {
        var ids = _library.Show(ShowA, "A", 6, 30);
        _library.HiddenFromKid.Add(ids[1]);
        var hub = Hub();
        var c = hub.Create(Edit(ShowA), Admin, true);
        var from = _time.GetUtcNow();
        var guide = hub.Guide(Kid, false, from, from.AddHours(2)).Single().Programs;
        Assert.False(guide.Single(p => p.ItemId == ids[1]).Available);
        Assert.Equal(new[] { "A 1", "A 3", "A 4" }, Titles(hub.Tune(c.Id, Kid, false, 3)));
    }

    [Fact]
    public void TheScheduleRunsAheadAndForgetsThePast()
    {
        _library.Show(ShowA, "A", 3, 30);
        var hub = Hub();
        var c = hub.Create(Edit(ShowA), Admin, true);
        _time.Advance(TimeSpan.FromDays(5));
        var now = _time.GetUtcNow();
        var programs = hub.Guide(Admin, true, now, now.AddHours(3)).Single().Programs;
        Assert.Equal(7, programs.Count);
        Assert.All(_storage.Saved[c.Id].Playout.Programs, p => Assert.True(p.End >= now - ChannelScheduler.KeepPast));
        Assert.True(_storage.Saved[c.Id].Playout.Programs[^1].End >= now + ChannelHub.Ahead);
    }

    [Fact]
    public void NothingToPlayIsRefused()
    {
        var e = Assert.Throws<ChannelException>(() => Hub().Create(Edit(), Admin, true));
        Assert.Equal(ChannelError.Invalid, e.Error);
        Assert.Equal(ChannelError.Invalid, Assert.Throws<ChannelException>(() => Hub().Create(Edit(ShowA) with { AlignMinutes = 7 }, Admin, true)).Error);
    }
}
