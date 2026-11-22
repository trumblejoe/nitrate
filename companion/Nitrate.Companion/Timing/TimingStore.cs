using System.Text.Json;

namespace Nitrate.Companion.Timing;

/// <summary>What the timing check found for one title, and which version of its file.</summary>
/// <param name="Version">The file and its change date when checked (a new file is checked again).</param>
/// <param name="Issues">What's wrong (empty: checked, fine).</param>
/// <param name="Checked">When.</param>
public record TimingResult(string Version, IReadOnlyList<TimingIssue> Issues, DateTimeOffset Checked);

/// <summary>Results kept in the server's data folder (timing.json), one per title.</summary>
public class TimingStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly string? _path;
    private readonly object _lock = new();
    private Dictionary<Guid, TimingResult> _results;

    /// <summary>Initializes a new instance of the <see cref="TimingStore"/> class ([path] null: in memory only).</summary>
    public TimingStore(string? path)
    {
        _path = path;
        _results = Load(path);
    }

    /// <summary>The result for [item], if it was checked.</summary>
    public TimingResult? Get(Guid item)
    {
        lock (_lock)
        {
            return _results.TryGetValue(item, out var r) ? r : null;
        }
    }

    /// <summary>Whether [item] needs checking (never checked, or checked as another version).</summary>
    public bool Needs(Guid item, string version) => Get(item) is not { } r || r.Version != version;

    /// <summary>Keeps [item]'s result.</summary>
    public void Put(Guid item, TimingResult result)
    {
        lock (_lock)
        {
            _results[item] = result;
            Save();
        }
    }

    /// <summary>Every title with something wrong.</summary>
    public IReadOnlyDictionary<Guid, TimingResult> WithIssues()
    {
        lock (_lock)
        {
            return _results.Where(kv => kv.Value.Issues.Count > 0).ToDictionary(kv => kv.Key, kv => kv.Value);
        }
    }

    /// <summary>Forgets titles no longer in the library.</summary>
    public void KeepOnly(ISet<Guid> present)
    {
        lock (_lock)
        {
            var gone = _results.Keys.Where(k => !present.Contains(k)).ToList();
            if (gone.Count == 0)
            {
                return;
            }

            gone.ForEach(k => _results.Remove(k));
            Save();
        }
    }

    private void Save()
    {
        if (_path == null)
        {
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(_results, Json));
        File.Move(temp, _path, true);
    }

    private static Dictionary<Guid, TimingResult> Load(string? path)
    {
        try
        {
            return path != null && File.Exists(path)
                ? JsonSerializer.Deserialize<Dictionary<Guid, TimingResult>>(File.ReadAllText(path), Json) ?? new()
                : new();
        }
        catch (JsonException)
        {
            return new();
        }
    }
}
