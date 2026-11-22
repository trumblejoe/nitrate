using System.Text.Json;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Diary;
using Nitrate.Companion.Scripts;
using Nitrate.Companion.Storage;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Screenplays, the diary, device reports and catalog places in companion.db: rows, not whole files.</summary>
public class DatabaseStoresTests
{
    private static readonly Guid Owner = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));

    private static JsonElement Ops(string json) => JsonDocument.Parse(json).RootElement.Clone();

    private static string Folder() => Directory.CreateTempSubdirectory().FullName;

    [Fact]
    public void TypingIsKeptEvenWhenTheWholeScriptWasntWrittenYet()
    {
        var folder = Folder();
        var db = new CompanionDb(Path.Combine(folder, "companion.db"));
        // The whole script is written only every hour here: every edit lives in the history rows.
        var storage = new DbScriptStorage(db, every: TimeSpan.FromHours(1));
        var hub = new ScriptHub(storage, _time);
        var s = hub.Create(Owner, "Sam");
        hub.Edit(s.Id, Owner, "e1", 0, Ops("""[3, " walks"]"""), null, null);
        hub.Edit(s.Id, Owner, "e2", 1, Ops("""[9, " in."]"""), null, null);

        // The server stops without writing it (no flush): started again, the edits are replayed.
        var restarted = new ScriptHub(new DbScriptStorage(db, every: TimeSpan.FromHours(1)), _time);
        var back = restarted.Get(s.Id, Owner);
        Assert.Equal(("Sam walks in.", 2L), (back.Text, back.Version));
        Assert.Equal(new[] { "e1", "e2" }, back.Edits.Select(e => e.EditId));
        Assert.Equal(2, storage.ReadLog(s.Id, 1, 10).Count);
    }

    [Fact]
    public void OlderScriptFilesAreMovedIn()
    {
        var folder = Folder();
        var old = new FileScriptStorage(Path.Combine(folder, "scripts"));
        var hub = new ScriptHub(old, _time);
        var s = hub.Create(Owner, "Old");
        hub.Edit(s.Id, Owner, "e1", 0, Ops("""[3, "!"]"""), null, null);
        using var db = new CompanionDb(Path.Combine(folder, "companion.db"));
        var storage = new DbScriptStorage(db, Path.Combine(folder, "scripts"));
        Assert.Equal("Old!", new ScriptHub(storage, _time).Get(s.Id, Owner).Text);
        Assert.Single(storage.ReadLog(s.Id, 1, 10));
        Assert.False(Directory.Exists(Path.Combine(folder, "scripts")));
    }

    [Fact]
    public void TheDiaryWritesOnlyWhatChanged()
    {
        using var db = new CompanionDb(":memory:");
        var storage = new DbDiaryStorage(db);
        var hub = new DiaryHub(storage, _time);
        var title = new DiaryTitle(Guid.NewGuid(), "Movie", "Dune", null, null, null, null, 2021, 9_000_000, Array.Empty<string>());
        var first = hub.Log(Owner, title, null, 8, "Big");
        hub.Log(Owner, title with { Name = "Heat" }, null, null, null);
        Assert.Equal(2, db.Records("diary", Owner.ToString("N")).Count);
        hub.Delete(Owner, first.Id);
        Assert.Single(new DbDiaryStorage(db).Load(Owner));
    }

    [Fact]
    public void ADeviceReplacesOnlyItsOwnReport()
    {
        using var db = new CompanionDb(":memory:");
        var storage = new DbDeviceReportStorage(db);
        var summary = JsonDocument.Parse("{}").RootElement.Clone();
        var (sam, kim) = (Guid.NewGuid(), Guid.NewGuid());
        storage.Save(new() { new(sam, "sam", "tv", "TV", 1, summary), new(kim, "kim", "phone", "Phone", 2, summary) });
        storage.Save(new() { new(sam, "sam", "tv", "TV", 3, summary), new(kim, "kim", "phone", "Phone", 2, summary) });
        var again = new DbDeviceReportStorage(db).Load();
        Assert.Equal(2, again.Count);
        Assert.Equal(3, again.Single(r => r.UserId == sam).At);
    }

    [Fact]
    public void CatalogPlacesFromBeforeAreMovedIn()
    {
        var folder = Folder();
        var dir = Path.Combine(folder, "catalog-positions");
        Directory.CreateDirectory(dir);
        var sam = Guid.NewGuid();
        File.WriteAllText(Path.Combine(dir, sam.ToString("N") + ".json"), """[["c1|book","42"]]""");
        using var db = new CompanionDb(Path.Combine(folder, "companion.db"));
        var places = new Nitrate.Companion.Catalogs.CatalogPositions(db, dir);
        Assert.Equal(42, places.Get(sam, "c1", "book"));
        Assert.False(Directory.Exists(dir));
    }
}
