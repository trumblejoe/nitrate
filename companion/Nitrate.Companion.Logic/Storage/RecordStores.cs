using System.Text.Json;
using Nitrate.Companion.Devices;
using Nitrate.Companion.Diary;

namespace Nitrate.Companion.Storage;

/// <summary>
/// Each person's watch diary in the plugin's database, one row per watch: saving a person's diary
/// writes only the entries that changed (and removes the ones gone), never the whole of it. The
/// older one-file-per-person diary folder is moved in once.
/// </summary>
public sealed class DbDiaryStorage : IDiaryStorage
{
    private const string Kind = "diary";
    private readonly CompanionDb _db;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, Dictionary<string, string>> _written = new();

    /// <summary>Initializes a new instance of the <see cref="DbDiaryStorage"/> class ([legacyDir]: the older folder, moved in once).</summary>
    public DbDiaryStorage(CompanionDb db, string? legacyDir = null)
    {
        _db = db;
        if (legacyDir == null || !Directory.Exists(legacyDir))
        {
            return;
        }

        var old = new FileDiaryStorage(legacyDir);
        foreach (var file in Directory.EnumerateFiles(legacyDir, "*.json").ToList())
        {
            if (Guid.TryParseExact(Path.GetFileNameWithoutExtension(file), "N", out var user) && _db.Records(Kind, user.ToString("N")).Count == 0)
            {
                Save(user, old.Load(user));
            }

            File.Delete(file);
        }

        if (!Directory.EnumerateFileSystemEntries(legacyDir).Any())
        {
            Directory.Delete(legacyDir);
        }
    }

    /// <inheritdoc />
    public List<DiaryEntry> Load(Guid userId)
    {
        var rows = _db.Records(Kind, userId.ToString("N"));
        lock (_lock)
        {
            _written[userId] = rows.ToDictionary(r => r.Key, r => r.Value);
        }

        return rows.Select(r => Read<DiaryEntry>(r.Value)).OfType<DiaryEntry>().ToList();
    }

    /// <inheritdoc />
    public void Save(Guid userId, List<DiaryEntry> entries)
    {
        var owner = userId.ToString("N");
        var now = entries.ToDictionary(e => e.Id.ToString("N"), e => (Json: JsonSerializer.Serialize(e), e.WatchedAt));
        lock (_lock)
        {
            if (!_written.TryGetValue(userId, out var before))
            {
                before = _db.Records(Kind, owner).ToDictionary(r => r.Key, r => r.Value);
            }

            foreach (var (key, (json, at)) in now)
            {
                if (!before.TryGetValue(key, out var old) || old != json)
                {
                    _db.PutRecord(Kind, owner, key, json, at);
                }
            }

            foreach (var gone in before.Keys.Where(k => !now.ContainsKey(k)))
            {
                _db.DeleteRecord(Kind, owner, gone);
            }

            _written[userId] = now.ToDictionary(kv => kv.Key, kv => kv.Value.Json);
        }
    }

    internal static T? Read<T>(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<T>(json);
        }
        catch (JsonException)
        {
            return default;
        }
    }
}

/// <summary>
/// The household's device reports in the plugin's database, one row per person and device: a
/// device sending its summary replaces its own row only. The older devices.json is moved in once.
/// </summary>
public sealed class DbDeviceReportStorage : IDeviceReportStorage
{
    private const string Kind = "device-report";
    private const string Owner = "all";
    private readonly CompanionDb _db;
    private readonly object _lock = new();
    private Dictionary<string, string>? _written;

    /// <summary>Initializes a new instance of the <see cref="DbDeviceReportStorage"/> class ([legacyJson]: the older file, moved in once).</summary>
    public DbDeviceReportStorage(CompanionDb db, string? legacyJson = null)
    {
        _db = db;
        if (legacyJson != null && File.Exists(legacyJson))
        {
            if (_db.Records(Kind, Owner).Count == 0)
            {
                try
                {
                    Save(new FileDeviceReportStorage(legacyJson).Load());
                }
                catch (JsonException)
                {
                    // Unreadable: devices send their summaries again.
                }
            }

            File.Delete(legacyJson);
        }
    }

    /// <inheritdoc />
    public List<DeviceReport> Load()
    {
        lock (_lock)
        {
            _written ??= _db.Records(Kind, Owner).ToDictionary(r => r.Key, r => r.Value);
            return _written.Values.Select(DbDiaryStorage.Read<DeviceReport>).OfType<DeviceReport>().ToList();
        }
    }

    /// <inheritdoc />
    public void Save(List<DeviceReport> reports)
    {
        lock (_lock)
        {
            _written ??= _db.Records(Kind, Owner).ToDictionary(r => r.Key, r => r.Value);
            var now = reports.ToDictionary(r => r.UserId.ToString("N") + "|" + r.DeviceId, r => (Json: JsonSerializer.Serialize(r), r.At));
            foreach (var (key, (json, at)) in now)
            {
                if (!_written.TryGetValue(key, out var old) || old != json)
                {
                    _db.PutRecord(Kind, Owner, key, json, at);
                }
            }

            foreach (var gone in _written.Keys.Where(k => !now.ContainsKey(k)).ToList())
            {
                _db.DeleteRecord(Kind, Owner, gone);
            }

            _written = now.ToDictionary(kv => kv.Key, kv => kv.Value.Json);
        }
    }
}
