using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Storage;
using Nitrate.Companion.Watched;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Watched/Unwatched answered at once, saved to Jellyfin a moment later, in order, with nothing lost.</summary>
public class WatchedQueueTests
{
    private static readonly Guid Sam = Guid.Parse("00000000-0000-0000-0000-0000000000a1");
    private static readonly Guid Alex = Guid.Parse("00000000-0000-0000-0000-0000000000a2");
    private static readonly Guid One = Guid.Parse("00000000-0000-0000-0000-0000000000e1");
    private static readonly Guid Two = Guid.Parse("00000000-0000-0000-0000-0000000000e2");
    private static readonly Guid Hidden = Guid.Parse("00000000-0000-0000-0000-0000000000e9");

    /// <summary>Stands in for Jellyfin's mark: what was saved, in order, and saves that fail on demand.</summary>
    private sealed class Marker : IWatchMarker
    {
        public List<(Guid User, Guid Item, bool Played)> Saved { get; } = new();

        public int FailNext { get; set; }

        public Action? DuringSave { get; set; }

        public bool Visible(Guid user, Guid itemId) => itemId != Hidden;

        public bool Mark(Guid user, Guid itemId, bool played)
        {
            if (FailNext > 0)
            {
                FailNext--;
                throw new InvalidOperationException("database is locked");
            }

            DuringSave?.Invoke();
            Saved.Add((user, itemId, played));
            return true;
        }
    }

    private static (WatchedQueue Queue, Marker Marker, FakeTimeProvider Time) Make(CompanionDb db)
    {
        var marker = new Marker();
        var time = new FakeTimeProvider(DateTimeOffset.FromUnixTimeMilliseconds(1_000_000));
        return (new WatchedQueue(db, marker, null, time, NullLogger.Instance), marker, time);
    }

    [Fact]
    public void SavesInTheOrderPressedAndANewerPressReplacesOneWaiting()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        Assert.Equal([Hidden], queue.Press(Sam, [One, Hidden], played: true));
        time.Advance(TimeSpan.FromSeconds(1));
        queue.Press(Alex, [Two], played: true);
        time.Advance(TimeSpan.FromSeconds(1));
        // Changed their mind before it was saved: only the last press counts.
        queue.Press(Sam, [One], played: false);
        Assert.False(queue.Waiting(Sam)[One].Played);
        Assert.DoesNotContain(Hidden, queue.Waiting(Sam).Keys);

        Assert.Equal(2, queue.SaveDue());
        Assert.Equal([(Alex, Two, true), (Sam, One, false)], marker.Saved);
        Assert.Empty(queue.Waiting(Sam));
    }

    [Fact]
    public void AFailedSaveIsTriedAgainLaterAndNothingIsLostAcrossARestart()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        marker.FailNext = 1;
        queue.Press(Sam, [One], played: true);
        Assert.Equal(0, queue.SaveDue());
        Assert.Equal(1, queue.Waiting(Sam)[One].Tries);
        // Not again straight away.
        Assert.Equal(0, queue.SaveDue());

        // A new logic (or a restart) finds it in the database.
        var (next, nextMarker, nextTime) = Make(db);
        nextTime.Advance(TimeSpan.FromMinutes(1));
        Assert.Equal(1, next.SaveDue());
        Assert.Equal([(Sam, One, true)], nextMarker.Saved);
        Assert.Empty(next.Waiting(Sam));
    }

    [Fact]
    public void GivesUpAfterManyFailures()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        marker.FailNext = WatchedQueue.MaxTries;
        queue.Press(Sam, [One], played: true);
        for (var i = 0; i < WatchedQueue.MaxTries; i++)
        {
            queue.SaveDue();
            time.Advance(TimeSpan.FromMinutes(10));
        }

        Assert.Empty(queue.Waiting(Sam));
        Assert.Empty(marker.Saved);
    }

    [Fact]
    public void AChangeMadeElsewhereMeanwhileWins()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        queue.Press(Sam, [One, Two], played: true);
        time.Advance(TimeSpan.FromSeconds(1));
        // Jellyfin's web app marked One unwatched after the press: that's the newer change.
        queue.SavedElsewhere(Sam, One, time.GetUtcNow().ToUnixTimeMilliseconds(), MediaBrowser.Model.Entities.UserDataSaveReason.TogglePlayed);
        // Its own saves (heard while saving) don't count as elsewhere.
        marker.DuringSave = () => queue.SavedElsewhere(Sam, Two, time.GetUtcNow().ToUnixTimeMilliseconds(), MediaBrowser.Model.Entities.UserDataSaveReason.TogglePlayed);
        Assert.Equal(1, queue.SaveDue());
        Assert.Equal([(Sam, Two, true)], marker.Saved);
    }

    /// <summary>Pressed Watched, then played it straight away: the playback starting doesn't undo the press.</summary>
    [Fact]
    public void APlaybackStartingDoesntCancelAPress()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        queue.Press(Sam, [One], played: true);
        time.Advance(TimeSpan.FromSeconds(1));
        queue.SavedElsewhere(Sam, One, time.GetUtcNow().ToUnixTimeMilliseconds(), MediaBrowser.Model.Entities.UserDataSaveReason.PlaybackStart);
        queue.SavedElsewhere(Sam, One, time.GetUtcNow().ToUnixTimeMilliseconds(), MediaBrowser.Model.Entities.UserDataSaveReason.PlaybackProgress);
        queue.SavedElsewhere(Sam, One, time.GetUtcNow().ToUnixTimeMilliseconds(), MediaBrowser.Model.Entities.UserDataSaveReason.UpdateUserRating);
        Assert.Equal(1, queue.SaveDue());
        Assert.Equal([(Sam, One, true)], marker.Saved);
    }

    /// <summary>
    /// Pressed Watched, then a playlist sitting or a group play of it starts before the press was saved:
    /// the press is saved first, and the state put back when that playback stops is the pressed one.
    /// </summary>
    [Fact]
    public void APressWaitingIsSavedBeforeAPlaybackCountedApart()
    {
        using var db = new CompanionDb(":memory:");
        var (queue, marker, time) = Make(db);
        queue.Press(Sam, [One], played: true);
        var pressed = queue.SaveNow(Sam, One);
        Assert.True(pressed!.Played);
        Assert.Equal([(Sam, One, true)], marker.Saved);
        Assert.Empty(queue.Waiting(Sam));
        Assert.Null(queue.SaveNow(Sam, One));
        // The app's copy of the state from before (read before the press reached the server).
        var before = new Nitrate.Companion.Partners.OwnState(false, 600_000, 0, null).Marked(pressed.Played, 5);
        Assert.Equal(new Nitrate.Companion.Partners.OwnState(true, 0, 1, 5), before);
        Assert.Equal(new Nitrate.Companion.Partners.OwnState(false, 0, 0, null), before.Marked(false, 9));
    }
}
