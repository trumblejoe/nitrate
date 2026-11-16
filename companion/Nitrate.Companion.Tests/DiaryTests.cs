using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Diary;
using Xunit;

namespace Nitrate.Companion.Tests;

public class DiaryTests
{
    private sealed class MemoryStorage : IDiaryStorage
    {
        public Dictionary<Guid, List<DiaryEntry>> Saved { get; } = new();

        public List<DiaryEntry> Load(Guid userId) => Saved.TryGetValue(userId, out var l) ? l.ToList() : new();

        public void Save(Guid userId, List<DiaryEntry> entries) => Saved[userId] = entries.ToList();
    }

    private static readonly Guid Ann = Guid.NewGuid();
    private static readonly Guid Bob = Guid.NewGuid();
    private static readonly DiaryTitle Film = new(Guid.NewGuid(), "Movie", "Silver Screen", null, null, null, null, 2021, 6_000_000, new[] { "Drama" });

    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-03-01T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly MemoryStorage _storage = new();

    [Fact]
    public void RecordsEachWatchOnce()
    {
        var diary = new DiaryHub(_storage, _time);
        Assert.NotNull(diary.Finished(Ann, Film));
        // Another stop report for the same watch.
        _time.Advance(TimeSpan.FromMinutes(2));
        Assert.Null(diary.Finished(Ann, Film));
        // Watched again the next day: a second entry, so the diary knows it's a rewatch.
        _time.Advance(TimeSpan.FromDays(1));
        Assert.NotNull(diary.Finished(Ann, Film));
        Assert.Equal(2, diary.List(Ann, null, null).Count);
        Assert.Empty(diary.List(Bob, null, null));
        Assert.Equal(2, _storage.Saved[Ann].Count);
    }

    [Fact]
    public void RatingsNotesAndDates()
    {
        var diary = new DiaryHub(_storage, _time);
        var e = diary.Log(Ann, Film, 1_000, rating: 14, note: "  Loved it  ");
        Assert.Equal(10, e.Rating);
        Assert.Equal("Loved it", e.Note);
        Assert.True(e.Logged);
        var edited = diary.Edit(Ann, e.Id, new DiaryEdit(7, null, null))!;
        Assert.Equal(7, edited.Rating);
        Assert.Equal("Loved it", edited.Note);
        var cleared = diary.Edit(Ann, e.Id, new DiaryEdit(0, string.Empty, 2_000))!;
        Assert.Null(cleared.Rating);
        Assert.Null(cleared.Note);
        Assert.Equal(2_000, cleared.WatchedAt);
        // Nobody else's.
        Assert.Null(diary.Edit(Bob, e.Id, new DiaryEdit(3, null, null)));
        Assert.False(diary.Delete(Bob, e.Id));
        Assert.True(diary.Delete(Ann, e.Id));
        Assert.Empty(diary.List(Ann, null, null));
    }

    [Fact]
    public void BetweenDatesNewestFirst()
    {
        var diary = new DiaryHub(_storage, _time);
        diary.Log(Ann, Film, 100, null, null);
        diary.Log(Ann, Film, 300, null, null);
        diary.Log(Ann, Film, 200, null, null);
        Assert.Equal(new long[] { 300, 200 }, diary.List(Ann, 150, null).Select(e => e.WatchedAt));
        Assert.Equal(new long[] { 200, 100 }, diary.List(Ann, null, 300).Select(e => e.WatchedAt));
        // Kept across restarts.
        Assert.Equal(3, new DiaryHub(_storage, _time).List(Ann, null, null).Count);
    }
}
