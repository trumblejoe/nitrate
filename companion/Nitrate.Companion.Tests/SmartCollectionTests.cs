using Nitrate.Companion.SmartCollections;
using Xunit;

namespace Nitrate.Companion.Tests;

public class SmartCollectionTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-11-20T00:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
    private static readonly Guid Movies = Guid.NewGuid();

    private static ItemFacts Film(string name, int year = 2020, double rating = 7, int minutes = 110, int daysAgo = 100, int height = 1080, bool hdr = false, string[]? genres = null, string type = "Movie") =>
        new(Guid.NewGuid(), type, Movies, genres ?? new[] { "Drama" }, new[] { "favourite" }, new[] { "A24" }, "PG-13", year, rating, minutes, Now.AddDays(-daysAgo), height * 16 / 9, height, hdr);

    [Fact]
    public void EveryConditionMustHoldAndListsMatchAnyEntry()
    {
        var rule = new SmartRule { Name = "Short 4K HDR", Types = new[] { "Movie" }, MaxMinutes = 120, Only4K = true, OnlyHdr = true, Genres = new[] { "comedy", "drama" } };
        Assert.True(rule.Matches(Film("a", height: 2160, hdr: true), Now));
        Assert.False(rule.Matches(Film("b", height: 1080, hdr: true), Now));
        Assert.False(rule.Matches(Film("c", height: 2160, hdr: false), Now));
        Assert.False(rule.Matches(Film("d", height: 2160, hdr: true, minutes: 150), Now));
        Assert.False(rule.Matches(Film("e", height: 2160, hdr: true, genres: new[] { "Horror" }), Now));
        Assert.False(rule.Matches(Film("f", height: 2160, hdr: true, type: "Series"), Now));
    }

    [Fact]
    public void YearsRatingAndRecentlyAdded()
    {
        var rule = new SmartRule { Name = "New and good", YearFrom = 2010, YearTo = 2019, MinRating = 7.5, AddedWithinDays = 30 };
        Assert.True(rule.Matches(Film("a", year: 2015, rating: 8, daysAgo: 3), Now));
        Assert.False(rule.Matches(Film("b", year: 2021, rating: 8, daysAgo: 3), Now));
        Assert.False(rule.Matches(Film("c", year: 2015, rating: 7, daysAgo: 3), Now));
        Assert.False(rule.Matches(Film("d", year: 2015, rating: 8, daysAgo: 45), Now));
        Assert.True(new SmartRule { Name = "Everything" }.Matches(Film("e"), Now));
    }

    [Fact]
    public void SelectKeepsTheNewestUpToTheLimit()
    {
        var old = Film("old", daysAgo: 300);
        var mid = Film("mid", daysAgo: 30);
        var fresh = Film("fresh", daysAgo: 1);
        Assert.Equal(new[] { fresh.Id, mid.Id }, new SmartRule { Name = "x", Limit = 2 }.Select(new[] { old, mid, fresh }, Now));
        Assert.Equal(3, new SmartRule { Name = "x" }.Select(new[] { old, mid, fresh }, Now).Count);
        Assert.Empty(new SmartRule { Name = "x", LibraryIds = new[] { Guid.NewGuid().ToString() } }.Select(new[] { old }, Now));
        Assert.Single(new SmartRule { Name = "x", LibraryIds = new[] { Movies.ToString("N") } }.Select(new[] { old }, Now));
    }

    [Fact]
    public void TheStoreRemembersEachRulesCollection()
    {
        var path = Path.Combine(Path.GetTempPath(), "nitrate-smart-" + Guid.NewGuid().ToString("N") + ".json");
        var collection = Guid.NewGuid();
        new SmartCollectionStore(path).Set("r1", collection);
        var again = new SmartCollectionStore(path);
        Assert.Equal(collection, again.Get("r1"));
        again.Remove("r1");
        Assert.Null(new SmartCollectionStore(path).Get("r1"));
        File.Delete(path);
    }
}
