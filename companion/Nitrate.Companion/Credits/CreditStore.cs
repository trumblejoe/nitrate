using System.Text.Json;

namespace Nitrate.Companion.Credits;

/// <summary>What was found for one title, and which version of it was checked.</summary>
/// <param name="Version">The title's file and change date when checked (a new file is checked again).</param>
/// <param name="Skips">Scenes found (none: checked, nothing there).</param>
/// <param name="Checked">When.</param>
/// <param name="OutroStartMs">Where the final stretch of credits starts (to the end, after any scene), if it ends on credits.</param>
/// <param name="OutroEndMs">The title's end, when [OutroStartMs] is known.</param>
/// <param name="Format">How it was read: older results (without the final credits) are read again.</param>
/// <param name="OutroRejected">People said the final credits found were wrong: none are given for this version of the file.</param>
public record CreditResult(string Version, IReadOnlyList<CreditSkip> Skips, DateTimeOffset Checked, long? OutroStartMs = null, long? OutroEndMs = null, int Format = 0, bool OutroRejected = false)
{
    /// <summary>What's handed out: rejected final credits left out.</summary>
    public CreditResult Effective() => OutroRejected ? this with { OutroStartMs = null, OutroEndMs = null } : this;

    /// <summary>What results are read as now.</summary>
    public const int CurrentFormat = 2;
}

/// <summary>Results kept in the server's data folder (credits.json), one per title.</summary>
public class CreditStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, CreditResult> _results;

    /// <summary>Initializes a new instance of the <see cref="CreditStore"/> class ([db] null: in memory only; [legacyJson]: results from before the database, moved in once).</summary>
    public CreditStore(Nitrate.Companion.Storage.CompanionDb? db, string? legacyJson = null)
    {
        _db = db;
        db?.ImportResults<CreditResult>("credits", legacyJson);
        _results = db?.Results<CreditResult>("credits") ?? new();
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
            _db?.PutResult("credits", item, result);
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
            _db?.DeleteResults("credits", gone);
        }
    }

}
