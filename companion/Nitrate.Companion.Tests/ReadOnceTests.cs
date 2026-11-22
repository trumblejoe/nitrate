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
        var failures = new ReadFailures(null, _time);
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
        var path = Path.Combine(Directory.CreateTempSubdirectory().FullName, "f.json");
        var failures = new ReadFailures(path, _time);
        failures.Failed(_film, "v1", "whole");
        Assert.True(failures.MayRead(_film, "v1"));
        Assert.False(new ReadFailures(path, _time).MayRead(_film, "v1", "whole"));
        failures.KeepOnly(new HashSet<Guid>());
        Assert.Equal(0, failures.Count);
    }

    [Fact]
    public void WholeSoundPrintsAreKeptOnlyForTheEpisodesAsked()
    {
        var folder = Directory.CreateTempSubdirectory().FullName;
        var store = new IntroStore(folder);
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
}
