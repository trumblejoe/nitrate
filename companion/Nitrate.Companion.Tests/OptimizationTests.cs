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
    public void BecauseGathersSimilarForTheShowsAndFilmsPlayed()
    {
        var recent = new[]
        {
            new BecauseRecent(G(1), "Episode", "Pilot", G(10), "The Night Reel"),
            new BecauseRecent(G(2), "Episode", "Two", G(10), "The Night Reel"),
            new BecauseRecent(G(3), "Movie", "Dune", null, null),
            new BecauseRecent(G(4), "Movie", "Alien", null, null),
        };
        // Episodes count as their show, each once, most recent first; nothing else is decided here.
        Assert.Equal(new[] { G(10), G(3) }, BecauseGather.TitlesOf(recent, 2));
        Assert.Equal(new[] { G(10), G(3), G(4) }, BecauseGather.TitlesOf(recent, 9));
    }

    [Fact]
    public void BecauseIsKeptPerPerson()
    {
        var store = new BecauseStore();
        Assert.Null(store.Get(G(1)));
        store.Put(G(1), new BecauseFacts(DateTimeOffset.UnixEpoch, Array.Empty<BecauseRecent>(), new[] { new BecauseSimilar(G(2), new[] { G(3) }) }));
        Assert.Equal(G(3), store.Get(G(1))!.Similar.Single().ItemIds.Single());
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
    public void TheHeaviestFileIsTheMostPixelsThenTheHighestBitRate()
    {
        HeaviestFile F(int n, string codec, int w, int h, double? fps, int? rate) =>
            FormatIndex.LoadsOf(G(n), new[] { new MediaStream { Type = MediaStreamType.Video, Codec = codec, Width = w, Height = h, RealFrameRate = (float?)fps, BitRate = rate } }, 60_000_0000).Single();
        var picked = FormatIndex.Pick(new[]
        {
            F(1, "VC1", 1920, 1080, 23.976, 18_000_000),
            F(2, "vc1", 1920, 1080, 23.976, 24_000_000),
            F(3, "vc1", 1280, 720, 29.97, 30_000_000),
            F(4, "mpeg2video", 720, 480, 29.97, 8_000_000),
        });
        Assert.Equal(new[] { ("mpeg2video", G(4)), ("vc1", G(2)) }, picked.Select(f => (f.Codec, f.ItemId)));
        Assert.Equal(60_000L, picked[1].RunTimeMs);
        Assert.Empty(FormatIndex.LoadsOf(G(5), new[] { new MediaStream { Type = MediaStreamType.Video, Codec = "vc1" } }, null));
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
