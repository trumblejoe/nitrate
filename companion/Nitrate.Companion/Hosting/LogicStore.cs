using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Hosting;

/// <summary>
/// The logic packages on disk: the built-in one beside the plugin (trusted like the plugin DLL next
/// to it) and the downloaded ones in the server's data folder (<c>nitrate-companion/logic</c>: each
/// version's zip and signature, checked again whenever they're loaded), with which one last ran well
/// and which failed.
/// </summary>
public sealed class LogicStore
{
    /// <summary>A package from the plugin's own folder.</summary>
    public const string BuiltIn = "built-in";

    /// <summary>A package downloaded and verified.</summary>
    public const string Downloaded = "downloaded";

    private readonly string _folder;
    private readonly string? _builtInPath;
    private readonly byte[]? _publicKey;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="LogicStore"/> class.</summary>
    /// <param name="folder">Where downloaded packages are kept.</param>
    /// <param name="builtInPath">The built-in package's file, if any.</param>
    /// <param name="publicKey">The key downloaded packages must be signed with (null: none is trusted).</param>
    public LogicStore(string folder, string? builtInPath, byte[]? publicKey)
    {
        _folder = folder;
        _builtInPath = builtInPath;
        _publicKey = publicKey;
    }

    /// <summary>Gets the key downloaded packages must be signed with.</summary>
    public byte[]? PublicKey => _publicKey;

    /// <summary>The built-in package's path for the running plugin.</summary>
    public static string? BuiltInPathFor(System.Reflection.Assembly shell)
    {
        var dir = string.IsNullOrEmpty(shell.Location) ? null : Path.GetDirectoryName(shell.Location);
        return dir is null ? null : Path.Combine(dir, LogicPackage.BuiltInFile);
    }

    /// <summary>Whether a version failed before (it isn't tried again; a newer one is).</summary>
    public bool IsBad(string version)
    {
        lock (_lock)
        {
            return ReadState().Bad.Contains(version);
        }
    }

    /// <summary>Remembers that a downloaded version failed to load or start.</summary>
    public void MarkBad(string version)
    {
        lock (_lock)
        {
            var state = ReadState();
            if (!state.Bad.Contains(version))
            {
                state.Bad.Add(version);
                if (state.Good == version)
                {
                    state.Good = state.Previous;
                    state.Previous = null;
                }

                WriteState(state);
            }
        }
    }

    /// <summary>Keeps a downloaded package that now runs well (and the one before it); older ones are removed.</summary>
    public void Keep(LogicPackage package)
    {
        if (package.Signature is null)
        {
            return;
        }

        lock (_lock)
        {
            Directory.CreateDirectory(_folder);
            File.WriteAllBytes(ZipPath(package.Version), package.Zip);
            File.WriteAllBytes(SigPath(package.Version), package.Signature);
            var state = ReadState();
            if (state.Good != package.Version)
            {
                state.Previous = state.Good;
                state.Good = package.Version;
            }

            WriteState(state);
            foreach (var file in Directory.EnumerateFiles(_folder, "*.zip").Concat(Directory.EnumerateFiles(_folder, "*.sig")))
            {
                var version = Path.GetFileNameWithoutExtension(file);
                if (version != state.Good && version != state.Previous)
                {
                    TryDelete(file);
                }
            }
        }
    }

    /// <summary>
    /// The packages to start from, best first: the last good downloaded one and the built-in one,
    /// the newer first, each only if it reads, its contract fits and (downloaded) its signature holds.
    /// </summary>
    public IReadOnlyList<LogicPackage> Candidates(ILogger log)
    {
        var found = new List<LogicPackage>();
        State state;
        lock (_lock)
        {
            state = ReadState();
        }

        foreach (var version in new[] { state.Good, state.Previous }.OfType<string>().Where(v => !state.Bad.Contains(v)))
        {
            try
            {
                if (!File.Exists(ZipPath(version)) || !File.Exists(SigPath(version)))
                {
                    continue;
                }

                var zip = File.ReadAllBytes(ZipPath(version));
                var sig = File.ReadAllBytes(SigPath(version));
                if (!LogicSignature.Verify(zip, sig, _publicKey))
                {
                    log.LogWarning("Nitrate Companion: the kept logic {Version} isn't signed with this plugin's key; not using it", version);
                    continue;
                }

                var package = LogicPackage.Read(zip, Downloaded, sig);
                if (package.Version == version && ShellContract.Supports(package.Manifest.Contract))
                {
                    found.Add(package);
                    break;
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or LogicPackageException)
            {
                log.LogWarning(e, "Nitrate Companion: the kept logic {Version} can't be read", version);
            }
        }

        if (_builtInPath is not null && File.Exists(_builtInPath))
        {
            try
            {
                var package = LogicPackage.Read(File.ReadAllBytes(_builtInPath), BuiltIn);
                if (ShellContract.Supports(package.Manifest.Contract))
                {
                    found.Add(package);
                }
                else
                {
                    log.LogError("Nitrate Companion: the built-in logic is built for contract {Contract}", package.Manifest.Contract);
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or LogicPackageException)
            {
                log.LogError(e, "Nitrate Companion: the built-in logic can't be read");
            }
        }
        else
        {
            log.LogWarning("Nitrate Companion: no built-in logic at {Path}", _builtInPath);
        }

        // Newer first; at the same version, the built-in one (it came with this plugin).
        return found.OrderByDescending(p => p.ParsedVersion).ThenBy(p => p.Source == BuiltIn ? 0 : 1).ToList();
    }

    private string ZipPath(string version) => Path.Combine(_folder, version + ".zip");

    private string SigPath(string version) => Path.Combine(_folder, version + ".sig");

    private string StatePath => Path.Combine(_folder, "state.json");

    private State ReadState()
    {
        try
        {
            return File.Exists(StatePath) ? JsonSerializer.Deserialize<State>(File.ReadAllText(StatePath)) ?? new State() : new State();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            return new State();
        }
    }

    private void WriteState(State state)
    {
        Directory.CreateDirectory(_folder);
        var temp = StatePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(state));
        File.Move(temp, StatePath, overwrite: true);
    }

    private static void TryDelete(string file)
    {
        try
        {
            File.Delete(file);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Left for next time.
        }
    }

    private sealed class State
    {
        public string? Good { get; set; }

        public string? Previous { get; set; }

        public List<string> Bad { get; set; } = new();
    }
}
