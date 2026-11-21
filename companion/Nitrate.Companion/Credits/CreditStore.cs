using System.Text.Json;

namespace Nitrate.Companion.Credits;

/// <summary>What was found for one title, and which version of it was checked.</summary>
/// <param name="Version">The title's file and change date when checked (a new file is checked again).</param>
/// <param name="Skips">Scenes found (none: checked, nothing there).</param>
/// <param name="Checked">When.</param>
/// <param name="OutroStartMs">Where the final stretch of credits starts (to the end, after any scene), if it ends on credits.</param>
/// <param name="OutroEndMs">The title's end, when [OutroStartMs] is known.</param>
/// <param name="Format">How it was read: older results (without the final credits) are read again.</param>
public record CreditResult(string Version, IReadOnlyList<CreditSkip> Skips, DateTimeOffset Checked, long? OutroStartMs = null, long? OutroEndMs = null, int Format = 0)
{
    /// <summary>What results are read as now.</summary>
    public const int CurrentFormat = 2;
}

/// <summary>Results kept in the server's data folder (credits.json), one per title.</summary>
public class CreditStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly string? _path;
    private readonly object _lock = new();
    private Dictionary<Guid, CreditResult> _results;

    /// <summary>Initializes a new instance of the <see cref="CreditStore"/> class ([path] null: in memory only).</summary>
    public CreditStore(string? path)
    {
        _path = path;
        _results = Load(path);
    }

    /// <summary>The result for [item], if it was checked.</summary>
    public CreditResult? Get(Guid item)
    {
        lock (_lock)
        {
            return _results.TryGetValue(item, out var r) ? r : null;
        }
    }

    /// <summary>Whether [item] needs checking (never checked, or checked as another version).</summary>
    public bool Needs(Guid item, string version) => Get(item) is not { } r || r.Version != version || r.Format < CreditResult.CurrentFormat;

    /// <summary>Keeps [item]'s result.</summary>
    public void Put(Guid item, CreditResult result)
    {
        lock (_lock)
        {
            _results[item] = result;
            Save();
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

    private static Dictionary<Guid, CreditResult> Load(string? path)
    {
        if (path is null || !File.Exists(path))
        {
            return new();
        }

        try
        {
            return JsonSerializer.Deserialize<Dictionary<Guid, CreditResult>>(File.ReadAllText(path), Json) ?? new();
        }
        catch (JsonException)
        {
            return new();
        }
    }

    private void Save()
    {
        if (_path is null)
        {
            return;
        }

        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(_results, Json));
        File.Move(temp, _path, true);
    }
}
