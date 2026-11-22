using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Nitrate.Companion.Storage;

/// <summary>
/// The plugin's own database (companion.db in the server's data folder, through the server's own
/// SQLite): what the nightly tasks found per title (intros, credit scenes, files' timing), the intro
/// task's sound fingerprints and the files they couldn't read. One row per title, written when
/// that title is done, instead of a whole JSON file rewritten each time, and one file instead of
/// thousands of small ones. Results from before it are moved in once ([ImportResults], [ImportPrints]).
/// </summary>
public sealed class CompanionDb : IDisposable
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly SqliteConnection _db;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="CompanionDb"/> class at [path] (":memory:" for tests).</summary>
    public CompanionDb(string path)
    {
        if (path != ":memory:")
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        }

        _db = new SqliteConnection("Data Source=" + path);
        _db.Open();
        Exec("PRAGMA journal_mode=WAL");
        Exec("""
            CREATE TABLE IF NOT EXISTS results(kind TEXT NOT NULL, item TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(kind, item));
            CREATE TABLE IF NOT EXISTS prints(item TEXT NOT NULL, whole INTEGER NOT NULL, version TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(item, whole));
            CREATE TABLE IF NOT EXISTS failures(task TEXT NOT NULL, key TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(task, key));
            """);
    }

    /// <summary>Every result of one kind ("intros", "credits", "timing").</summary>
    public Dictionary<Guid, T> Results<T>(string kind)
    {
        var all = new Dictionary<Guid, T>();
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT item, json FROM results WHERE kind = $k";
            cmd.Parameters.AddWithValue("$k", kind);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                if (Guid.TryParseExact(reader.GetString(0), "N", out var id) && Read<T>(reader.GetString(1)) is { } value)
                {
                    all[id] = value;
                }
            }
        }

        return all;
    }

    /// <summary>Keeps one title's result.</summary>
    public void PutResult<T>(string kind, Guid item, T value)
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "INSERT OR REPLACE INTO results(kind, item, json) VALUES ($k, $i, $j)";
            cmd.Parameters.AddWithValue("$k", kind);
            cmd.Parameters.AddWithValue("$i", item.ToString("N"));
            cmd.Parameters.AddWithValue("$j", JsonSerializer.Serialize(value, Json));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>Forgets results of one kind for [items].</summary>
    public void DeleteResults(string kind, IEnumerable<Guid> items) => DeleteKeys("results", "kind", kind, "item", items.Select(i => i.ToString("N")));

    /// <summary>Moves a JSON file of results (item → result) in, once, and deletes the file.</summary>
    public void ImportResults<T>(string kind, string? jsonPath)
    {
        if (jsonPath is null || !File.Exists(jsonPath))
        {
            return;
        }

        try
        {
            var old = JsonSerializer.Deserialize<Dictionary<Guid, T>>(File.ReadAllText(jsonPath), Json) ?? new();
            lock (_lock)
            {
                using var tx = _db.BeginTransaction();
                foreach (var (item, value) in old)
                {
                    using var cmd = _db.CreateCommand();
                    cmd.Transaction = tx;
                    cmd.CommandText = "INSERT OR IGNORE INTO results(kind, item, json) VALUES ($k, $i, $j)";
                    cmd.Parameters.AddWithValue("$k", kind);
                    cmd.Parameters.AddWithValue("$i", item.ToString("N"));
                    cmd.Parameters.AddWithValue("$j", JsonSerializer.Serialize(value, Json));
                    cmd.ExecuteNonQuery();
                }

                tx.Commit();
            }
        }
        catch (JsonException)
        {
            // Unreadable: its titles are simply read again.
        }

        File.Delete(jsonPath);
    }

    /// <summary>A sound fingerprint taken of [version] of [item]'s file ([whole]: all its sound), if kept.</summary>
    public uint[]? Print(Guid item, bool whole, string version)
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT data FROM prints WHERE item = $i AND whole = $w AND version = $v";
            cmd.Parameters.AddWithValue("$i", item.ToString("N"));
            cmd.Parameters.AddWithValue("$w", whole ? 1 : 0);
            cmd.Parameters.AddWithValue("$v", version);
            return cmd.ExecuteScalar() is byte[] bytes ? FromBytes(bytes) : null;
        }
    }

    /// <summary>Keeps a fingerprint (replacing an older version's).</summary>
    public void PutPrint(Guid item, bool whole, string version, uint[] print)
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "INSERT OR REPLACE INTO prints(item, whole, version, data) VALUES ($i, $w, $v, $d)";
            cmd.Parameters.AddWithValue("$i", item.ToString("N"));
            cmd.Parameters.AddWithValue("$w", whole ? 1 : 0);
            cmd.Parameters.AddWithValue("$v", version);
            cmd.Parameters.AddWithValue("$d", ToBytes(print));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>The titles with a fingerprint of the given sort.</summary>
    public List<Guid> PrintItems(bool whole)
    {
        var ids = new List<Guid>();
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT item FROM prints WHERE whole = $w";
            cmd.Parameters.AddWithValue("$w", whole ? 1 : 0);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                if (Guid.TryParseExact(reader.GetString(0), "N", out var id))
                {
                    ids.Add(id);
                }
            }
        }

        return ids;
    }

    /// <summary>Forgets fingerprints of the given sort for [items].</summary>
    public void DeletePrints(bool whole, IEnumerable<Guid> items) => DeleteKeys("prints", "whole", whole ? 1 : 0, "item", items.Select(i => i.ToString("N")));

    /// <summary>
    /// Moves a folder of fingerprint files in (each "&lt;id&gt;.bin" or "&lt;id&gt;.whole.bin": the
    /// version string, a count, then the numbers), once, and deletes them.
    /// </summary>
    public void ImportPrints(string? folder)
    {
        if (folder is null || !Directory.Exists(folder))
        {
            return;
        }

        foreach (var file in Directory.EnumerateFiles(folder, "*.bin").ToList())
        {
            var name = Path.GetFileName(file);
            if (name.Length >= 32 && Guid.TryParseExact(name[..32], "N", out var id))
            {
                try
                {
                    using (var reader = new BinaryReader(File.OpenRead(file)))
                    {
                        var version = reader.ReadString();
                        var print = new uint[reader.ReadInt32()];
                        for (var i = 0; i < print.Length; i++)
                        {
                            print[i] = reader.ReadUInt32();
                        }

                        PutPrint(id, name.EndsWith(".whole.bin", StringComparison.Ordinal), version, print);
                    }
                }
                catch (Exception e) when (e is IOException or EndOfStreamException)
                {
                    // A damaged one is simply taken again.
                }
            }

            File.Delete(file);
        }

        try
        {
            Directory.Delete(folder, false);
        }
        catch (IOException)
        {
            // Something else left in it: leave the folder.
        }
    }

    /// <summary>One task's unreadable files (key → failure as JSON).</summary>
    public Dictionary<string, T> Failures<T>(string task)
    {
        var all = new Dictionary<string, T>();
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "SELECT key, json FROM failures WHERE task = $t";
            cmd.Parameters.AddWithValue("$t", task);
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                if (Read<T>(reader.GetString(1)) is { } value)
                {
                    all[reader.GetString(0)] = value;
                }
            }
        }

        return all;
    }

    /// <summary>Keeps one failure.</summary>
    public void PutFailure<T>(string task, string key, T value)
    {
        lock (_lock)
        {
            using var cmd = _db.CreateCommand();
            cmd.CommandText = "INSERT OR REPLACE INTO failures(task, key, json) VALUES ($t, $k, $j)";
            cmd.Parameters.AddWithValue("$t", task);
            cmd.Parameters.AddWithValue("$k", key);
            cmd.Parameters.AddWithValue("$j", JsonSerializer.Serialize(value, Json));
            cmd.ExecuteNonQuery();
        }
    }

    /// <summary>Forgets failures of one task.</summary>
    public void DeleteFailures(string task, IEnumerable<string> keys) => DeleteKeys("failures", "task", task, "key", keys);

    /// <inheritdoc />
    public void Dispose() => _db.Dispose();

    private void DeleteKeys(string table, string groupColumn, object group, string keyColumn, IEnumerable<string> keys)
    {
        var list = keys.ToList();
        if (list.Count == 0)
        {
            return;
        }

        lock (_lock)
        {
            using var tx = _db.BeginTransaction();
            using var cmd = _db.CreateCommand();
            cmd.Transaction = tx;
            cmd.CommandText = $"DELETE FROM {table} WHERE {groupColumn} = $g AND {keyColumn} = $k";
            cmd.Parameters.AddWithValue("$g", group);
            var key = cmd.Parameters.Add("$k", SqliteType.Text);
            foreach (var k in list)
            {
                key.Value = k;
                cmd.ExecuteNonQuery();
            }

            tx.Commit();
        }
    }

    private static T? Read<T>(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<T>(json, Json);
        }
        catch (JsonException)
        {
            return default;
        }
    }

    private static byte[] ToBytes(uint[] print)
    {
        var bytes = new byte[print.Length * 4];
        Buffer.BlockCopy(print, 0, bytes, 0, bytes.Length);
        return bytes;
    }

    private static uint[] FromBytes(byte[] bytes)
    {
        var print = new uint[bytes.Length / 4];
        Buffer.BlockCopy(bytes, 0, print, 0, print.Length * 4);
        return print;
    }

    private void Exec(string sql)
    {
        using var cmd = _db.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }
}
