using Jellyfin.Data.Enums;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Api;
using Nitrate.Companion.Formats;
using Nitrate.Companion.QueryTimes;
using Nitrate.Companion.Recommendations;
using Xunit;

namespace Nitrate.Companion.Tests;

public class OptimizationTests
{
    private static Guid G(int n) => new(n, 0, 0, new byte[8]);

    [Fact]
    public async Task BecauseRowsFollowTheAppsRules()
    {
        var seeds = BecauseRows.Seeds(new[] { new BecauseSeed(G(1), "Dune"), new BecauseSeed(G(1), "Dune"), new BecauseSeed(G(2), "Alien"), new BecauseSeed(G(3), "Heat") }, 6);
        Assert.Equal(3, seeds.Count);
        var asked = new List<Guid>();
        var rows = await BecauseRows.BuildAsync(
            seeds,
            seed =>
            {
                asked.Add(seed.Id);
                // Alien's similar titles are mostly already in Dune's row: too few left for a row.
                IReadOnlyList<Guid> like = seed.Id == G(1) ? new[] { G(10), G(11), G(12), G(13), G(2) }
                    : seed.Id == G(2) ? new[] { G(10), G(11), G(20) }
                    : new[] { G(30), G(31), G(32), G(33) };
                return Task.FromResult(like);
            },
            id => id == G(13));
        Assert.Equal(new[] { "Dune", "Heat" }, rows.Select(r => r.SeedName));
        // Seeds and watched titles never come back.
        Assert.Equal(new[] { G(10), G(11), G(12) }, rows[0].ItemIds);
        Assert.Equal(3, asked.Count);
        // Watching more since leaves too few: the row isn't shown.
        Assert.Empty(BecauseRows.Showing(rows[0], id => id == G(10), 16));
        Assert.Equal(2, BecauseRows.Showing(rows[1], id => false, 2).Count);
    }

    [Fact]
    public void BecauseIsKeptPerPerson()
    {
        var store = new BecauseStore();
        Assert.Null(store.Get(G(1)));
        store.Put(G(1), new BecauseSet(DateTimeOffset.UnixEpoch, new[] { new BecauseRow(G(2), "Dune", new[] { G(3) }) }));
        Assert.Equal("Dune", store.Get(G(1))!.Rows.Single().SeedName);
        Assert.Null(store.Get(G(9)));
    }

    [Fact]
    public void LatestListsOnlyFilmsAndShows()
    {
        var parsed = HomeController.ParseLatest($"{G(1)}:Movie, {G(2)}:Series,{G(3)}:Music,nonsense,{G(1)}:Movie");
        Assert.Equal(new[] { (G(1), BaseItemKind.Movie), (G(2), BaseItemKind.Series) }, parsed);
        Assert.Empty(HomeController.ParseLatest(null));
    }

    [Fact]
    public void FormatsSummarizeStreams()
    {
        var (video, audio) = FormatIndex.Summarize(new[]
        {
            new MediaStream { Type = MediaStreamType.Video, Codec = "hevc", BitDepth = 10, DvProfile = 8 },
            new MediaStream { Type = MediaStreamType.Audio, Codec = "eac3" },
            new MediaStream { Type = MediaStreamType.Audio, Codec = "eac3" },
            new MediaStream { Type = MediaStreamType.Audio, Codec = "truehd" },
            new MediaStream { Type = MediaStreamType.Subtitle, Codec = "srt" },
        });
        var v = video.Single();
        Assert.Equal(("hevc", 10, 8), (v.Codec, v.BitDepth, v.DvProfile));
        Assert.False(string.IsNullOrEmpty(v.RangeType));
        Assert.Equal(new[] { "eac3", "truehd" }, audio);
    }

    [Fact]
    public void HouseholdShowsShapesNeverValues()
    {
        var time = new FakeTimeProvider(DateTimeOffset.FromUnixTimeMilliseconds(1_000));
        var store = new QueryTimeStore(time);
        var shape = QueryTimeStore.ShapeOf("GET", $"/Items/{G(5)}/Similar", new[] { "limit", "userId", "searchTerm" });
        Assert.Equal("/Items/{id}/Similar?searchterm&userid", shape);
        Assert.Equal("POST /Sessions/Playing", QueryTimeStore.ShapeOf("POST", "/Sessions/Playing", Array.Empty<string>()));
        store.Record(G(1), "a-1", 100, shape);
        store.Record(G(1), "a-2", 300, shape);
        store.Record(G(2), "b-1", 2_000, shape);
        store.Record(G(2), "b-2", 10, "/Shows/NextUp");
        var household = store.Household();
        Assert.Equal(shape, household[0].Shape);
        Assert.Equal(3, household[0].Count);
        Assert.Equal(2, household[0].People);
        Assert.Equal(300, household[0].MedianMs);
        Assert.Equal(2_000, household[0].MaxMs);
        time.Advance(QueryTimeStore.Keep + TimeSpan.FromMinutes(1));
        Assert.Empty(store.Household());
    }

    [Fact]
    public void ChangeCountersSayWhatChanged()
    {
        var counters = new Nitrate.Companion.Changes.ChangeCounters();
        var before = counters.For(G(1), null);
        counters.ItemChanged(Nitrate.Companion.Changes.ChangeCounters.Kind.Title);
        counters.WatchChanged(G(2));
        var after = counters.For(G(1), null);
        Assert.Equal(before.Epoch, after.Epoch);
        Assert.Equal(before.Library + 1, after.Library);
        // Someone else's watching isn't this person's change.
        Assert.Equal(before.Watch, after.Watch);
        Assert.Equal(before.Views, after.Views);
        counters.ItemChanged(Nitrate.Companion.Changes.ChangeCounters.Kind.Playlist);
        Assert.Equal(after.Library, counters.For(G(1), null).Library);
        Assert.Equal(after.Playlists + 1, counters.For(G(1), null).Playlists);
        counters.ItemChanged(Nitrate.Companion.Changes.ChangeCounters.Kind.Library);
        Assert.Equal(after.Views + 1, counters.For(G(1), null).Views);
        counters.WatchChanged(G(1));
        Assert.Equal(1, counters.For(G(1), null).Watch);
        Assert.Equal(new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero).UtcTicks, counters.For(G(1), new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero)).Because);
        // A new run of the logic: a new epoch, so apps load everything once.
        Assert.NotEqual(counters.Epoch, new Nitrate.Companion.Changes.ChangeCounters().Epoch);
    }
}
