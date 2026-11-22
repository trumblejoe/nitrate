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
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, TimingResult> _results;

    /// <summary>Initializes a new instance of the <see cref="TimingStore"/> class ([db] null: in memory only; [legacyJson]: results from before the database, moved in once).</summary>
    public TimingStore(Nitrate.Companion.Storage.CompanionDb? db, string? legacyJson = null)
    {
        _db = db;
        db?.ImportResults<TimingResult>("timing", legacyJson);
        _results = db?.Results<TimingResult>("timing") ?? new();
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
            _db?.PutResult("timing", item, result);
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
            _db?.DeleteResults("timing", gone);
        }
    }

}
