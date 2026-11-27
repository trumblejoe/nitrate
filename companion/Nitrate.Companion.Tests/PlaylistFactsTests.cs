using Nitrate.Companion.Recommendations;
using Xunit;

namespace Nitrate.Companion.Tests;

public class PlaylistFactsTests
{
    [Fact]
    public void AnAnswerIsPutTogetherFromTheShapeAndKeptStates()
    {
        var movie = Guid.NewGuid();
        var show = Guid.NewGuid();
        var ep1 = Guid.NewGuid();
        var ep2 = Guid.NewGuid();
        var shape = new PlaylistShape(Guid.NewGuid(), new[]
        {
            new EntryShape(movie, "Movie", null, null, null, null),
            new EntryShape(show, "Series", null, null, null, new[]
            {
                new EpisodeShape(ep1, null, 1, 1, false),
                new EpisodeShape(ep2, null, 1, 2, true),
            }),
        }, DateTime.UtcNow);
        var states = new WatchStates();
        var reads = 0;
        WatchState Read(Guid id)
        {
            reads++;
            return id == ep1 ? new WatchState(true, 1_000, 0) : new WatchState(false, null, 0);
        }

        Func<Guid, WatchState> state = id => states.Get(id, () => Read(id));
        var first = PlaylistFacts.Assemble(shape, state);
        Assert.Equal(4, reads);
        Assert.True(first.Entries[1].Episodes![0].Played);
        Assert.True(first.Entries[1].Episodes![1].Missing);

        // A watch event replaces the kept state: the next answer has it, with nothing read again.
        states.Put(movie, new WatchState(false, 2_000, 600_000));
        var second = PlaylistFacts.Assemble(shape, state);
        Assert.Equal(4, reads);
        Assert.Equal((2_000L, 600_000L), (second.Entries[0].LastPlayedMs!.Value, second.Entries[0].PositionMs));
    }

    [Fact]
    public void ASaveIsNeverOverwrittenByAnOlderRead()
    {
        var states = new WatchStates();
        var id = Guid.NewGuid();
        // The save's event lands while the first read (taken before the save) is still under way.
        var got = states.Get(id, () =>
        {
            states.Put(id, new WatchState(true, 5, 0));
            return new WatchState(false, null, 0);
        });
        Assert.True(got.Played);
        Assert.True(states.Get(id, () => new WatchState(false, null, 0)).Played);
    }
}
