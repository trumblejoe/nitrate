using System.Text.Json;

namespace Nitrate.Companion.Intros;

/// <summary>What was found for one episode, and which version of its file was read.</summary>
/// <param name="Version">The episode's file and change date when read (a new file is read again).</param>
/// <param name="Intro">Its intro (null: read, none found).</param>
/// <param name="Checked">When.</param>
/// <param name="Recap">Its "Previously on…" recap (null: none found).</param>
/// <param name="IntroRejected">People said the intro found was wrong: none is given for this version of the file.</param>
/// <param name="RecapRejected">People said the recap found was wrong.</param>
public record IntroResult(string Version, Intro? Intro, DateTimeOffset Checked, Intro? Recap = null, bool IntroRejected = false, bool RecapRejected = false)
{
    /// <summary>What's handed out: rejected parts left out.</summary>
    public IntroResult Effective() => this with { Intro = IntroRejected ? null : Intro, Recap = RecapRejected ? null : Recap };
}

/// <summary>
/// Results and each episode's sound fingerprint, in the plugin's database (<see cref="Nitrate.Companion.Storage.CompanionDb"/>),
/// so a new episode only needs its own file read to be compared with the rest.
/// </summary>
public class IntroStore
{
    private const string Kind = "intros";
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, IntroResult> _results;
    private readonly Dictionary<(Guid, bool), (string Version, uint[] Print)> _memory = new();

    /// <summary>
    /// Initializes a new instance of the <see cref="IntroStore"/> class ([db] null: in memory only;
    /// [legacyFolder]: where intros.json and intro-prints/ were kept before the database, moved in once).
    /// </summary>
    public IntroStore(Nitrate.Companion.Storage.CompanionDb? db, string? legacyFolder = null)
    {
        _db = db;
        if (db != null && legacyFolder != null)
        {
            db.ImportResults<IntroResult>(Kind, Path.Combine(legacyFolder, "intros.json"));
            db.ImportPrints(Path.Combine(legacyFolder, "intro-prints"));
        }

        _results = db?.Results<IntroResult>(Kind) ?? new();
    }

    /// <summary>The result for [item], if it was read.</summary>
    public IntroResult? Get(Guid item)
    {
        lock (_lock)
        {
            return _results.TryGetValue(item, out var r) ? r : null;
        }
    }

    /// <summary>Whether [item] needs reading (never read, or read as another version).</summary>
    public bool Needs(Guid item, string version) => Get(item)?.Version != version;

    /// <summary>Keeps [item]'s result.</summary>
    public void Put(Guid item, IntroResult result)
    {
        lock (_lock)
        {
            _results[item] = result;
            _db?.PutResult(Kind, item, result);
        }
    }

    /// <summary>[item]'s fingerprint when it was taken of [version] of its file ([whole]: all its sound, for recaps; else its opening).</summary>
    public uint[]? Print(Guid item, string version, bool whole = false)
    {
        if (_db != null)
        {
            return _db.Print(item, whole, version);
        }

        lock (_lock)
        {
            return _memory.TryGetValue((item, whole), out var m) && m.Version == version ? m.Print : null;
        }
    }

    /// <summary>Keeps [item]'s fingerprint of [version] ([whole]: all its sound).</summary>
    public void SavePrint(Guid item, string version, uint[] print, bool whole = false)
    {
        if (_db != null)
        {
            _db.PutPrint(item, whole, version, print);
            return;
        }

        lock (_lock)
        {
            _memory[(item, whole)] = (version, print);
        }
    }

    /// <summary>Forgets episodes no longer in the library (results and fingerprints).</summary>
    public void KeepOnly(ISet<Guid> present)
    {
        lock (_lock)
        {
            var gone = _results.Keys.Where(k => !present.Contains(k)).ToList();
            gone.ForEach(k => _results.Remove(k));
            _db?.DeleteResults(Kind, gone);
            foreach (var k in _memory.Keys.Where(k => !present.Contains(k.Item1)).ToList())
            {
                _memory.Remove(k);
            }
        }

        if (_db != null)
        {
            _db.DeletePrints(false, _db.PrintItems(false).Where(id => !present.Contains(id)));
            _db.DeletePrints(true, _db.PrintItems(true).Where(id => !present.Contains(id)));
        }
    }

    /// <summary>
    /// Lets go of whole-sound fingerprints except [wanted]'s: only the last few episodes of each
    /// show are kept, the ones a new episode's recap is compared with, so each is read once.
    /// </summary>
    public void KeepWholeOnly(ISet<Guid> wanted)
    {
        lock (_lock)
        {
            foreach (var k in _memory.Keys.Where(k => k.Item2 && !wanted.Contains(k.Item1)).ToList())
            {
                _memory.Remove(k);
            }
        }

        _db?.DeletePrints(true, _db.PrintItems(true).Where(id => !wanted.Contains(id)));
    }
}
