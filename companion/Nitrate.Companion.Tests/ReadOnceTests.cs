using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Intros;
using Nitrate.Companion.Scanning;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>The nightly tasks read each file once: failures wait, and recap fingerprints are kept for the episodes that need them.</summary>
public class ReadOnceTests
{
    private readonly FakeTimeProvider _time = new(DateTimeOffset.FromUnixTimeMilliseconds(1_000));
    private readonly Guid _film = Guid.NewGuid();

    [Fact]
    public void AFileThatFailedWaitsLongerEachTime()
    {
        var failures = new ReadFailures(null, "test", _time);
        Assert.True(failures.MayRead(_film, "v1"));
        failures.Failed(_film, "v1");
        Assert.False(failures.MayRead(_film, "v1"));
        // Changed since: tried at once.
        Assert.True(failures.MayRead(_film, "v2"));
        _time.Advance(TimeSpan.FromDays(1));
        Assert.True(failures.MayRead(_film, "v1"));
        failures.Failed(_film, "v1");
        _time.Advance(TimeSpan.FromDays(6));
        Assert.False(failures.MayRead(_film, "v1"));
        _time.Advance(TimeSpan.FromDays(1));
        Assert.True(failures.MayRead(_film, "v1"));
        failures.Failed(_film, "v1");
        _time.Advance(TimeSpan.FromDays(29));
        Assert.False(failures.MayRead(_film, "v1"));
        failures.Succeeded(_film);
        Assert.True(failures.MayRead(_film, "v1"));
    }

    [Fact]
    public void PartsFailOnTheirOwnAndAreKeptOnDisk()
    {
        using var db = new Nitrate.Companion.Storage.CompanionDb(Path.Combine(Directory.CreateTempSubdirectory().FullName, "companion.db"));
        var failures = new ReadFailures(db, "credits", _time);
        failures.Failed(_film, "v1", "whole");
        Assert.True(failures.MayRead(_film, "v1"));
        Assert.False(new ReadFailures(db, "credits", _time).MayRead(_film, "v1", "whole"));
        // Another task's failures are its own.
        Assert.True(new ReadFailures(db, "timing", _time).MayRead(_film, "v1", "whole"));
        failures.KeepOnly(new HashSet<Guid>());
        Assert.Equal(0, failures.Count);
    }

    [Fact]
    public void WholeSoundPrintsAreKeptOnlyForTheEpisodesAsked()
    {
        using var db = new Nitrate.Companion.Storage.CompanionDb(":memory:");
        var store = new IntroStore(db);
        var (a, b) = (Guid.NewGuid(), Guid.NewGuid());
        store.SavePrint(a, "v", new uint[] { 1, 2, 3 });
        store.SavePrint(a, "v", new uint[] { 7, 8 }, whole: true);
        store.SavePrint(b, "v", new uint[] { 9 }, whole: true);
        Assert.Equal(new uint[] { 7, 8 }, store.Print(a, "v", whole: true));
        Assert.Equal(new uint[] { 1, 2, 3 }, store.Print(a, "v"));
        store.KeepWholeOnly(new HashSet<Guid> { a });
        Assert.Null(store.Print(b, "v", whole: true));
        Assert.NotNull(store.Print(a, "v", whole: true));
        // The opening prints are untouched; leaving the library removes both.
        store.KeepOnly(new HashSet<Guid>());
        Assert.Null(store.Print(a, "v"));
        Assert.Null(store.Print(a, "v", whole: true));
    }

    [Fact]
    public void ResultsFromBeforeTheDatabaseAreMovedInOnce()
    {
        var folder = Directory.CreateTempSubdirectory().FullName;
        var (film, episode) = (Guid.NewGuid(), Guid.NewGuid());
        File.WriteAllText(Path.Combine(folder, "credits.json"), System.Text.Json.JsonSerializer.Serialize(new Dictionary<Guid, Nitrate.Companion.Credits.CreditResult>
        {
            [film] = new("v1", Array.Empty<Nitrate.Companion.Credits.CreditSkip>(), DateTimeOffset.UnixEpoch, Format: Nitrate.Companion.Credits.CreditResult.CurrentFormat),
        }));
        File.WriteAllText(Path.Combine(folder, "intros.json"), System.Text.Json.JsonSerializer.Serialize(new Dictionary<Guid, IntroResult>
        {
            [episode] = new("e1", new Intro(1_000, 30_000), DateTimeOffset.UnixEpoch),
        }));
        Directory.CreateDirectory(Path.Combine(folder, "intro-prints"));
        using (var writer = new BinaryWriter(File.Create(Path.Combine(folder, "intro-prints", episode.ToString("N") + ".bin"))))
        {
            writer.Write("e1");
            writer.Write(2);
            writer.Write(5u);
            writer.Write(6u);
        }

        using var db = new Nitrate.Companion.Storage.CompanionDb(Path.Combine(folder, "companion.db"));
        var credits = new Nitrate.Companion.Credits.CreditStore(db, Path.Combine(folder, "credits.json"));
        var intros = new IntroStore(db, folder);
        Assert.False(credits.Needs(film, "v1"));
        Assert.Equal(new Intro(1_000, 30_000), intros.Get(episode)!.Intro);
        Assert.Equal(new uint[] { 5, 6 }, intros.Print(episode, "e1"));
        // The old files are gone: nothing to read twice, nothing left behind.
        Assert.False(File.Exists(Path.Combine(folder, "credits.json")));
        Assert.False(File.Exists(Path.Combine(folder, "intros.json")));
        Assert.False(Directory.Exists(Path.Combine(folder, "intro-prints")));
    }
}
