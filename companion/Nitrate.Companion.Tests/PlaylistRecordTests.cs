using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Partners;
using Nitrate.Companion.Playlists;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>A playlist's own record, apart from the person's own state, and sittings of it.</summary>
public class PlaylistRecordTests
{
    private static readonly Guid Sam = Guid.Parse("00000000-0000-0000-0000-0000000000a1");
    private static readonly Guid Alex = Guid.Parse("00000000-0000-0000-0000-0000000000a2");
    private static readonly Guid Playlist = Guid.Parse("00000000-0000-0000-0000-0000000000b1");
    private static readonly Guid One = Guid.Parse("00000000-0000-0000-0000-0000000000e1");
    private static readonly Guid Two = Guid.Parse("00000000-0000-0000-0000-0000000000e2");

    [Fact]
    public void ASittingMovesTheRecordOn()
    {
        var r = PlaylistRecord.Empty(Playlist);
        // Stopped part-way: a resume point, and where the playlist carries on from.
        r = PlaylistRules.Stopped(r, One, 600_000, 1_200_000, ended: false, atMs: 10);
        Assert.Equal(600_000, r.Resume[One]);
        Assert.Empty(r.Watched);
        Assert.Equal((One, 10L), (r.LastId!.Value, r.LastAtMs));
        // Past nine tenths: watched, its resume point gone.
        r = PlaylistRules.Stopped(r, One, 1_100_000, 1_200_000, ended: false, atMs: 20);
        Assert.Equal([One], r.Watched);
        Assert.Empty(r.Resume);
        // Barely started: no resume point; played to the end with no length known: watched.
        r = PlaylistRules.Stopped(r, Two, 30_000, null, ended: false, atMs: 30);
        Assert.Empty(r.Resume);
        Assert.DoesNotContain(Two, r.Watched);
        r = PlaylistRules.Stopped(r, Two, 30_000, null, ended: true, atMs: 40);
        Assert.Equal([One, Two], r.Watched);
    }

    [Fact]
    public void RecordsAreEachPersonsOwn()
    {
        using var db = new CompanionDb(":memory:");
        var book = new PlaylistRecordBook(db);
        var started = new PlaylistRecord(Playlist, [One], new Dictionary<Guid, long>(), One, 5);
        book.Put(Sam, started);
        Assert.Equal([One], book.Get(Sam, Playlist)!.Watched);
        Assert.Null(book.Get(Alex, Playlist));
        Assert.Empty(book.All(Alex));

        // One started from the person's own progress never replaces one kept.
        var kept = book.Put(Sam, PlaylistRecord.Empty(Playlist), onlyIfNew: true);
        Assert.Equal([One], kept.Watched);

        book.Stopped(Sam, Playlist, Two, 300_000, 1_200_000, ended: false, atMs: 50);
        var now = Assert.Single(book.All(Sam));
        Assert.Equal(300_000, now.Resume[Two]);
        Assert.Equal(Two, now.LastId);

        // An app's record of absurd size is refused.
        Assert.Throws<ArgumentException>(() => book.Put(Sam, new PlaylistRecord(Playlist, Enumerable.Range(0, PlaylistRecordBook.MaxEntries + 1).Select(_ => Guid.NewGuid()).ToList(), new Dictionary<Guid, long>(), null, 0)));
    }

    [Fact]
    public void ASittingKeepsTheStateFromBeforeItsFirstSaying()
    {
        var time = new FakeTimeProvider(DateTimeOffset.UnixEpoch);
        var sittings = new PlaylistSittings(null!, null!, null!, null!, null!, time);
        sittings.Playing(Sam, One, Playlist, new OwnState(false, 120_000, 1, 10));
        sittings.Playing(Sam, One, Playlist, new OwnState(true, 0, 2, 20));
        Assert.Equal(Playlist, sittings.For(Sam, One));
        Assert.Null(sittings.For(Alex, One));
        Assert.False(sittings.ExportState().Single().Before.Played);

        // Played on its own after all: nothing is put back or recorded.
        sittings.NotInPlaylist(Sam, One);
        Assert.Null(sittings.For(Sam, One));
        Assert.False(sittings.Stopped(Sam, One, 0, ended: true));
    }

    [Fact]
    public void ForgetsSittingsThatNeverStopped()
    {
        var time = new FakeTimeProvider(DateTimeOffset.UnixEpoch);
        var sittings = new PlaylistSittings(null!, null!, null!, null!, null!, time);
        sittings.Playing(Sam, One, Playlist, new OwnState(false, 0, 0, null));
        time.Advance(PlaylistSittings.Keep + TimeSpan.FromMinutes(1));
        Assert.Null(sittings.For(Sam, One));
        Assert.False(sittings.Stopped(Sam, One, 0, ended: true));

        // Handed to the next logic.
        var next = new PlaylistSittings(null!, null!, null!, null!, null!, time);
        sittings.Playing(Alex, Two, Playlist, new OwnState(false, 0, 0, null));
        next.ImportState(sittings.ExportState());
        Assert.Equal(Playlist, next.For(Alex, Two));
    }
}
