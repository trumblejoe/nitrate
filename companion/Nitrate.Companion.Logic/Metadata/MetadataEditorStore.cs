using System.Text.Json;
using System.Text.Json.Nodes;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Metadata;

/// <summary>Who may edit metadata, and where.</summary>
/// <param name="UserId">The editor.</param>
/// <param name="AllLibraries">Every library, now and later.</param>
/// <param name="LibraryIds">Otherwise only titles in these libraries (collection folders).</param>
public record EditorScope(Guid UserId, bool AllLibraries, IReadOnlyList<Guid> LibraryIds);

/// <summary>One line of the edit log.</summary>
/// <param name="Id">Its number (newer lines have higher ones).</param>
/// <param name="AtMs">When (ms).</param>
/// <param name="UserId">Who made it.</param>
/// <param name="ItemId">The title.</param>
/// <param name="ItemName">The title's name then.</param>
/// <param name="Kind"><c>details</c>, <c>match</c>, <c>refresh</c> or <c>image</c>.</param>
/// <param name="Summary">A few words on what changed.</param>
/// <param name="Old">For details: the changed fields' old values (field → JSON), else null.</param>
/// <param name="Undone">A details edit already undone.</param>
public record MetadataEdit(long Id, long AtMs, Guid UserId, Guid ItemId, string ItemName, string Kind, string Summary, string? Old, bool Undone);

/// <summary>
/// Metadata editors (who, which libraries) and the log of every change made through
/// <c>/Nitrate/Metadata</c>, in companion.db (tables <c>metadata_editors</c> and
/// <c>metadata_edits</c>). Nothing is cached: every request reads the scope as it is now.
/// </summary>
public sealed class MetadataEditorStore
{
    /// <summary>How long log lines are kept.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromDays(365);

    private readonly CompanionDb _db;
    private readonly TimeProvider _time;

    /// <summary>Initializes a new instance of the <see cref="MetadataEditorStore"/> class.</summary>
    public MetadataEditorStore(CompanionDb db, TimeProvider time)
    {
        _db = db;
        _time = time;
        _db.Execute("""
            CREATE TABLE IF NOT EXISTS metadata_editors(user TEXT PRIMARY KEY, all_libraries INTEGER NOT NULL, libraries TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS metadata_edits(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, user TEXT NOT NULL, item TEXT NOT NULL, item_name TEXT NOT NULL,
                kind TEXT NOT NULL, summary TEXT NOT NULL, old TEXT, undone INTEGER NOT NULL DEFAULT 0);
            CREATE INDEX IF NOT EXISTS metadata_edits_at ON metadata_edits(at);
            """);
    }

    /// <summary>One user's scope, or null when they aren't an editor.</summary>
    public EditorScope? Editor(Guid userId) =>
        Read(_db.Rows("SELECT user, json_object('a', all_libraries, 'l', json(libraries)) FROM metadata_editors WHERE user = $u", ("$u", userId.ToString("N")))).FirstOrDefault();

    /// <summary>Every editor.</summary>
    public IReadOnlyList<EditorScope> Editors() =>
        Read(_db.Rows("SELECT user, json_object('a', all_libraries, 'l', json(libraries)) FROM metadata_editors ORDER BY user"));

    /// <summary>Makes [userId] an editor with this scope.</summary>
    public void Put(EditorScope scope) =>
        _db.Execute(
            "INSERT OR REPLACE INTO metadata_editors(user, all_libraries, libraries) VALUES ($u, $a, $l)",
            ("$u", scope.UserId.ToString("N")),
            ("$a", scope.AllLibraries ? 1 : 0),
            ("$l", JsonSerializer.Serialize(scope.LibraryIds.Distinct().Select(l => l.ToString("N")))));

    /// <summary>[userId] is no longer an editor.</summary>
    public void Remove(Guid userId) => _db.Execute("DELETE FROM metadata_editors WHERE user = $u", ("$u", userId.ToString("N")));

    /// <summary>Logs one change (and forgets lines older than <see cref="Keep"/>); its id.</summary>
    public long Log(Guid userId, Guid itemId, string itemName, string kind, string summary, string? old)
    {
        var now = _time.GetUtcNow();
        _db.Execute("DELETE FROM metadata_edits WHERE at < $at", ("$at", (now - Keep).ToUnixTimeMilliseconds()));
        var row = _db.Rows(
            "INSERT INTO metadata_edits(at, user, item, item_name, kind, summary, old) VALUES ($at, $u, $i, $n, $k, $s, $o) RETURNING id, ''",
            ("$at", now.ToUnixTimeMilliseconds()),
            ("$u", userId.ToString("N")),
            ("$i", itemId.ToString("N")),
            ("$n", itemName),
            ("$k", kind),
            ("$s", summary),
            ("$o", old));
        return long.Parse(row[0].Key, System.Globalization.CultureInfo.InvariantCulture);
    }

    /// <summary>Log lines, newest first, older than line [before] when given.</summary>
    public IReadOnlyList<MetadataEdit> Edits(int limit, long? before)
    {
        var rows = _db.Rows(
            "SELECT id, json_object('at', at, 'u', user, 'i', item, 'n', item_name, 'k', kind, 's', summary, 'o', old, 'd', undone) FROM metadata_edits WHERE id < $b ORDER BY id DESC LIMIT $n",
            ("$b", before ?? long.MaxValue),
            ("$n", Math.Clamp(limit, 1, 500)));
        return rows.Select(Edit).ToList();
    }

    /// <summary>One log line, or null.</summary>
    public MetadataEdit? Edit(long id) => _db.Rows(
            "SELECT id, json_object('at', at, 'u', user, 'i', item, 'n', item_name, 'k', kind, 's', summary, 'o', old, 'd', undone) FROM metadata_edits WHERE id = $id",
            ("$id", id))
        .Select(Edit).FirstOrDefault();

    /// <summary>Marks a details edit undone (it can't be undone twice).</summary>
    public void MarkUndone(long id) => _db.Execute("UPDATE metadata_edits SET undone = 1 WHERE id = $id", ("$id", id));

    private static MetadataEdit Edit(KeyValuePair<string, string> row)
    {
        var o = JsonNode.Parse(row.Value)!.AsObject();
        return new MetadataEdit(
            long.Parse(row.Key, System.Globalization.CultureInfo.InvariantCulture),
            o["at"]!.GetValue<long>(),
            Guid.ParseExact(o["u"]!.GetValue<string>(), "N"),
            Guid.ParseExact(o["i"]!.GetValue<string>(), "N"),
            o["n"]!.GetValue<string>(),
            o["k"]!.GetValue<string>(),
            o["s"]!.GetValue<string>(),
            o["o"]?.GetValue<string>(),
            o["d"]!.GetValue<long>() != 0);
    }

    private static List<EditorScope> Read(List<KeyValuePair<string, string>> rows)
    {
        var all = new List<EditorScope>();
        foreach (var row in rows)
        {
            if (!Guid.TryParseExact(row.Key, "N", out var user))
            {
                continue;
            }

            var o = JsonNode.Parse(row.Value)!.AsObject();
            var libraries = (o["l"] as JsonArray ?? new JsonArray())
                .Select(l => Guid.TryParse(l?.GetValue<string>(), out var g) ? g : Guid.Empty)
                .Where(g => g != Guid.Empty)
                .ToList();
            all.Add(new EditorScope(user, o["a"]!.GetValue<long>() != 0, libraries));
        }

        return all;
    }
}
