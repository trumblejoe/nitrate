using System.Reflection;
using System.Runtime.Loader;

namespace Nitrate.Companion.Hosting;

/// <summary>
/// The collectible context a logic package runs in. Its own assemblies come from memory; everything
/// else (this shell, Jellyfin's, ASP.NET's, the server's SQLite) is shared with the server, as the
/// shell sees it, so types match and nothing of the server's is loaded twice.
/// </summary>
public sealed class LogicLoadContext : AssemblyLoadContext
{
    private static readonly string[] HostPrefixes = ["System.", "Microsoft.", "Jellyfin.", "MediaBrowser.", "Emby.", "SQLitePCLRaw.", "netstandard", "mscorlib"];

    private readonly IReadOnlyDictionary<string, byte[]> _own;
    private readonly Assembly _shell;
    private readonly Dictionary<string, Assembly> _loaded = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="LogicLoadContext"/> class.</summary>
    public LogicLoadContext(string name, IReadOnlyDictionary<string, byte[]> assemblies, Assembly? shell = null)
        : base(name, isCollectible: true)
    {
        _own = assemblies;
        _shell = shell ?? typeof(LogicLoadContext).Assembly;
    }

    /// <summary>Whether an assembly must always be the server's (never one a package brings).</summary>
    public static bool IsHostOwned(string? name) =>
        name is null
        || string.Equals(name, typeof(LogicLoadContext).Assembly.GetName().Name, StringComparison.OrdinalIgnoreCase)
        || name.Equals("System", StringComparison.OrdinalIgnoreCase)
        || HostPrefixes.Any(p => name.StartsWith(p, StringComparison.OrdinalIgnoreCase));

    /// <summary>Loads one of the package's own assemblies by simple name.</summary>
    public Assembly LoadOwn(string name) => Load(new AssemblyName(name)) ?? throw new LogicPackageException($"The package has no '{name}'.");

    /// <inheritdoc />
    protected override Assembly? Load(AssemblyName assemblyName)
    {
        var name = assemblyName.Name;
        if (name is null)
        {
            return null;
        }

        if (string.Equals(name, _shell.GetName().Name, StringComparison.OrdinalIgnoreCase))
        {
            return _shell;
        }

        if (!IsHostOwned(name) && _own.TryGetValue(name, out var bytes))
        {
            lock (_lock)
            {
                if (!_loaded.TryGetValue(name, out var loaded))
                {
                    loaded = LoadFromStream(new MemoryStream(bytes, writable: false));
                    _loaded[name] = loaded;
                }

                return loaded;
            }
        }

        // As the shell sees it: Jellyfin loads plugins into their own context, which falls back to the server's.
        var shellContext = GetLoadContext(_shell) ?? Default;
        try
        {
            return shellContext.LoadFromAssemblyName(assemblyName);
        }
        catch (FileNotFoundException)
        {
            return null;
        }
    }
}
