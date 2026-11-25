using MediaBrowser.Controller.Entities;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>A group watch is kept out of the person's own history: their own state from before is put back.</summary>
public class GroupPlayTests
{
    private static readonly Guid Sam = Guid.Parse("00000000-0000-0000-0000-0000000000a1");
    private static readonly Guid Episode = Guid.Parse("00000000-0000-0000-0000-0000000000e1");
    private static readonly Guid Group = Guid.Parse("00000000-0000-0000-0000-0000000000f1");

    [Fact]
    public void PutsBackWhatAPlaybackChanges()
    {
        // As Jellyfin leaves it once a playback has run to the end.
        var data = new UserItemData { Key = "k", Played = true, PlaybackPositionTicks = 0, PlayCount = 3, LastPlayedDate = DateTime.UtcNow, IsFavorite = true };
        GroupPlays.Apply(data, new OwnState(false, 120_000, 2, 1_700_000_000_000));
        Assert.False(data.Played);
        Assert.Equal(120_000 * TimeSpan.TicksPerMillisecond, data.PlaybackPositionTicks);
        Assert.Equal(2, data.PlayCount);
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1_700_000_000_000).UtcDateTime, data.LastPlayedDate);
        // Nothing else of theirs changes.
        Assert.True(data.IsFavorite);
        GroupPlays.Apply(data, new OwnState(true, 0, 0, null));
        Assert.Null(data.LastPlayedDate);
    }

    [Fact]
    public void KeepsTheStateFromBeforeTheFirstSaying()
    {
        var time = new FakeTimeProvider(DateTimeOffset.UnixEpoch);
        var plays = new GroupPlays(null!, null!, null!, null!, time);
        plays.Playing(Sam, Episode, new[] { Group }, new OwnState(true, 0, 1, 10));
        // Said again (the groups changed part-way): the state from before the playback stays.
        plays.Playing(Sam, Episode, new[] { Group, Guid.NewGuid() }, new OwnState(false, 0, 2, 20));
        Assert.Equal(2, plays.For(Sam, Episode)!.Count);
        Assert.True(plays.ExportState().Single().Before.Played);
        // "Just me" after all: nothing is put back.
        plays.NotForGroups(Sam, Episode);
        Assert.Null(plays.For(Sam, Episode));
    }

    [Fact]
    public void ForgetsOnesThatNeverStopped()
    {
        var time = new FakeTimeProvider(DateTimeOffset.UnixEpoch);
        var plays = new GroupPlays(null!, null!, null!, null!, time);
        plays.Playing(Sam, Episode, new[] { Group }, new OwnState(false, 0, 0, null));
        time.Advance(GroupPlays.Keep + TimeSpan.FromMinutes(1));
        Assert.Null(plays.For(Sam, Episode));
        Assert.False(plays.Restore(Sam, Episode));
    }
}
