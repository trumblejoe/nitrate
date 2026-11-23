using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Nitrate.Companion.Scripts;

/// <summary>
/// Shared scripts in the plugin's database: every accepted change is one small row (the full
/// history), and the script as a whole (text, story data, recent changes) is written at most every
/// <see cref="SnapshotEvery"/> while people type, at once for anything else (members, a new
/// script). Started again, a script is its last whole copy with the changes after it replayed, so
/// a stop mid-typing loses nothing. The older scripts/ folder is moved in once.
/// </summary>
public sealed class DbScriptStorage : IScriptStorage, IDisposable
{
    /// <summary>How often a script being typed in is written whole.</summary>
    public static readonly TimeSpan SnapshotEvery = TimeSpan.FromSeconds(30);

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly Nitrate.Companion.Storage.CompanionDb _db;
    private readonly TimeSpan _every;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, (long Version, DateTimeOffset At)> _written = new();
    private readonly Dictionary<Guid, (long Version, string Json)> _pending = new();
    private readonly Timer _flush;

    /// <summary>Initializes a new instance of the <see cref="DbScriptStorage"/> class ([legacyDir]: the older folder, moved in once).</summary>
    public DbScriptStorage(Nitrate.Companion.Storage.CompanionDb db, string? legacyDir = null, TimeSpan? every = null)
    {
        _db = db;
        _every = every ?? SnapshotEvery;
        db.Execute("""
            CREATE TABLE IF NOT EXISTS scripts(id TEXT PRIMARY KEY, version INTEGER NOT NULL, json TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS script_edits(id TEXT NOT NULL, version INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(id, version));
            """);
        Import(legacyDir);
        _flush = new Timer(_ => Flush(), null, _every, _every);
    }

    /// <inheritdoc />
    public IEnumerable<StoredScript> LoadAll()
    {
        var all = new List<StoredScript>();
        foreach (var (_, json) in _db.Rows("SELECT id, json FROM scripts"))
        {
            StoredScript? script = null;
            try
            {
                script = JsonSerializer.Deserialize<StoredScript>(json, Json);
            }
            catch (JsonException)
            {
                // A damaged copy is skipped rather than lost.
            }

            if (script is null)
            {
                continue;
            }

            CatchUp(script);
            lock (_lock)
            {
                _written[script.Id] = (script.Version, DateTimeOffset.UtcNow);
            }

            all.Add(script);
        }

        return all;
    }

    /// <inheritdoc />
    public void Save(StoredScript script)
    {
        var json = JsonSerializer.Serialize(script, Json);
        var now = DateTimeOffset.UtcNow;
        lock (_lock)
        {
            // Only typing waits: a change that isn't an edit (members, a new script) is written now.
            var typing = _written.TryGetValue(script.Id, out var last) && script.Version != last.Version;
            if (typing && now - last.At < _every)
            {
                _pending[script.Id] = (script.Version, json);
                return;
            }

            _pending.Remove(script.Id);
            _written[script.Id] = (script.Version, now);
        }

        Write(script.Id, script.Version, json);
    }

    /// <inheritdoc />
    public void Delete(Guid id)
    {
        lock (_lock)
        {
            _pending.Remove(id);
            _written.Remove(id);
        }

        _db.Execute("DELETE FROM scripts WHERE id = $id", ("$id", Key(id)));
    }

    /// <inheritdoc />
    public void AppendLog(Guid id, StoredEdit edit) =>
        _db.Execute("INSERT OR REPLACE INTO script_edits(id, version, json) VALUES ($id, $v, $j)", ("$id", Key(id)), ("$v", edit.Version), ("$j", JsonSerializer.Serialize(edit, Json)));

    /// <inheritdoc />
    public IReadOnlyList<StoredEdit> ReadLog(Guid id, long fromVersion, int limit) =>
        _db.Rows("SELECT version, json FROM script_edits WHERE id = $id AND version >= $v ORDER BY version LIMIT $n", ("$id", Key(id)), ("$v", fromVersion), ("$n", limit))
            .Select(r => Read(r.Value)).OfType<StoredEdit>().ToList();

    /// <inheritdoc />
    public void DeleteLog(Guid id) => _db.Execute("DELETE FROM script_edits WHERE id = $id", ("$id", Key(id)));

    /// <summary>Writes every script waiting to be written whole.</summary>
    public void Flush()
    {
        List<(Guid Id, long Version, string Json)> due;
        lock (_lock)
        {
            due = _pending.Select(kv => (kv.Key, kv.Value.Version, kv.Value.Json)).ToList();
            _pending.Clear();
            foreach (var (id, version, _) in due)
            {
                _written[id] = (version, DateTimeOffset.UtcNow);
            }
        }

        foreach (var (id, version, json) in due)
        {
            Write(id, version, json);
        }
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _flush.Dispose();
        Flush();
    }

    /// <summary>Replays the changes saved after the script's last whole copy.</summary>
    private void CatchUp(StoredScript script)
    {
        while (true)
        {
            var after = ReadLog(script.Id, script.Version + 1, 2_000);
            if (after.Count == 0)
            {
                return;
            }

            foreach (var e in after)
            {
                if (e.Version != script.Version + 1)
                {
                    return;
                }

                if (e.IsText)
                {
                    script.Text = TextOps.Apply(script.Text, e.Ops!.Value);
                }

                if (e.Records is { Count: > 0 })
                {
                    var records = script.Records.ToDictionary(r => r.Key, r => r.Value);
                    ScriptHub.ApplyRecords(records, e.Records, e.UserId, e.At);
                    script.Records = records;
                }

                script.Version = e.Version;
                script.UpdatedAt = e.At;
                script.Edits.Add(e);
            }

            if (script.Edits.Count > ScriptHub.KeptEdits)
            {
                script.Edits.RemoveRange(0, script.Edits.Count - ScriptHub.KeptEdits);
            }
        }
    }

    private void Write(Guid id, long version, string json) =>
        _db.Execute("INSERT OR REPLACE INTO scripts(id, version, json) VALUES ($id, $v, $j)", ("$id", Key(id)), ("$v", version), ("$j", json));

    private void Import(string? dir)
    {
        if (dir is null || !Directory.Exists(dir))
        {
            return;
        }

        foreach (var file in Directory.EnumerateFiles(dir, "*.json").ToList())
        {
            try
            {
                var script = JsonSerializer.Deserialize<StoredScript>(File.ReadAllText(file), Json);
                if (script != null)
                {
                    Write(script.Id, script.Version, JsonSerializer.Serialize(script, Json));
                }
            }
            catch (JsonException)
            {
                // A damaged file is kept for a look rather than lost.
                continue;
            }

            File.Delete(file);
        }

        foreach (var file in Directory.EnumerateFiles(dir, "*.log").ToList())
        {
            if (!Guid.TryParseExact(Path.GetFileNameWithoutExtension(file), "N", out var id))
            {
                continue;
            }

            foreach (var line in File.ReadLines(file))
            {
                if (line.Length > 0 && Read(line) is { } edit)
                {
                    AppendLog(id, edit);
                }
            }

            File.Delete(file);
        }

        if (!Directory.EnumerateFileSystemEntries(dir).Any())
        {
            Directory.Delete(dir);
        }
    }

    private static StoredEdit? Read(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<StoredEdit>(json, Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static string Key(Guid id) => id.ToString("N");
}
