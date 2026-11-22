using System.Text.Json;

namespace Nitrate.Companion.Intros;

/// <summary>What was found for one episode, and which version of its file was read.</summary>
/// <param name="Version">The episode's file and change date when read (a new file is read again).</param>
/// <param name="Intro">Its intro (null: read, none found).</param>
/// <param name="Checked">When.</param>
/// <param name="Recap">Its "Previously on…" recap (null: none found).</param>
public record IntroResult(string Version, Intro? Intro, DateTimeOffset Checked, Intro? Recap = null);

/// <summary>
/// Results in the server's data folder (intros.json), and each episode's fingerprint beside them
/// (intro-prints/), so a new episode only needs its own file read to be compared with the rest.
/// </summary>
public class IntroStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly string? _path;
    private readonly string? _prints;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, IntroResult> _results;
    private readonly Dictionary<(Guid, bool), (string Version, uint[] Print)> _memory = new();

    /// <summary>Initializes a new instance of the <see cref="IntroStore"/> class ([folder] null: in memory only).</summary>
    public IntroStore(string? folder)
    {
        _path = folder is null ? null : Path.Combine(folder, "intros.json");
        _prints = folder is null ? null : Path.Combine(folder, "intro-prints");
        _results = Load(_path);
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
            Save();
        }
    }

    /// <summary>[item]'s fingerprint when it was taken of [version] of its file ([whole]: all its sound, for recaps; else its opening).</summary>
    public uint[]? Print(Guid item, string version, bool whole = false)
    {
        lock (_lock)
        {
            if (_memory.TryGetValue((item, whole), out var m))
            {
                return m.Version == version ? m.Print : null;
            }
        }

        if (_prints is null)
        {
            return null;
        }

        var file = FileOf(item, whole);
        if (!File.Exists(file))
        {
            return null;
        }

        try
        {
            using var reader = new BinaryReader(File.OpenRead(file));
            if (reader.ReadString() != version)
            {
                return null;
            }

            var print = new uint[reader.ReadInt32()];
            for (var i = 0; i < print.Length; i++)
            {
                print[i] = reader.ReadUInt32();
            }

            return print;
        }
        catch (IOException)
        {
            return null;
        }
    }

    /// <summary>Keeps [item]'s fingerprint of [version] ([whole]: all its sound).</summary>
    public void SavePrint(Guid item, string version, uint[] print, bool whole = false)
    {
        if (_prints is null)
        {
            lock (_lock)
            {
                _memory[(item, whole)] = (version, print);
            }

            return;
        }

        Directory.CreateDirectory(_prints);
        var file = FileOf(item, whole);
        using (var writer = new BinaryWriter(File.Create(file + ".tmp")))
        {
            writer.Write(version);
            writer.Write(print.Length);
            foreach (var bits in print)
            {
                writer.Write(bits);
            }
        }

        File.Move(file + ".tmp", file, true);
    }

    /// <summary>Forgets episodes no longer in the library (results and fingerprints).</summary>
    public void KeepOnly(ISet<Guid> present)
    {
        lock (_lock)
        {
            var gone = _results.Keys.Where(k => !present.Contains(k)).ToList();
            gone.ForEach(k => _results.Remove(k));
            foreach (var k in _memory.Keys.Where(k => !present.Contains(k.Item1)).ToList())
            {
                _memory.Remove(k);
            }

            if (gone.Count > 0)
            {
                Save();
            }
        }

        if (_prints is not null && Directory.Exists(_prints))
        {
            foreach (var file in Directory.EnumerateFiles(_prints, "*.bin"))
            {
                if (Guid.TryParseExact(Path.GetFileName(file)[..32], "N", out var id) && !present.Contains(id))
                {
                    File.Delete(file);
                }
            }
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

        if (_prints is not null && Directory.Exists(_prints))
        {
            foreach (var file in Directory.EnumerateFiles(_prints, "*.whole.bin"))
            {
                if (Guid.TryParseExact(Path.GetFileName(file)[..32], "N", out var id) && !wanted.Contains(id))
                {
                    File.Delete(file);
                }
            }
        }
    }

    private string FileOf(Guid item, bool whole) => Path.Combine(_prints!, item.ToString("N") + (whole ? ".whole.bin" : ".bin"));

    private static Dictionary<Guid, IntroResult> Load(string? path)
    {
        if (path is null || !File.Exists(path))
        {
            return new();
        }

        try
        {
            return JsonSerializer.Deserialize<Dictionary<Guid, IntroResult>>(File.ReadAllText(path), Json) ?? new();
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
