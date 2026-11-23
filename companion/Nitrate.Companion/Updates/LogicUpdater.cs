using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Hosting;

namespace Nitrate.Companion.Updates;

/// <summary>One logic package in the release's logic list (<c>logic.json</c> on the companion-repo release).</summary>
/// <param name="Version">The logic's version.</param>
/// <param name="Contract">The shell contract it needs.</param>
/// <param name="Url">Where the zip is.</param>
/// <param name="Sha256">The zip's SHA-256 (hex).</param>
/// <param name="Signature">The signature over the zip's SHA-256 (base64 DER).</param>
public sealed record LogicListing(
    [property: JsonPropertyName("version")] string Version,
    [property: JsonPropertyName("contract")] int Contract,
    [property: JsonPropertyName("url")] string Url,
    [property: JsonPropertyName("sha256")] string Sha256,
    [property: JsonPropertyName("signature")] string Signature);

/// <summary>The logic list file: newest first.</summary>
/// <param name="Packages">The packages.</param>
public sealed record LogicList([property: JsonPropertyName("packages")] IReadOnlyList<LogicListing> Packages);

/// <summary>Where logic packages come from.</summary>
public interface ILogicSource
{
    /// <summary>Gets a value indicating whether this build knows where to look.</summary>
    bool Configured { get; }

    /// <summary>The packages listed now.</summary>
    Task<IReadOnlyList<LogicListing>> ListAsync(CancellationToken cancellationToken);

    /// <summary>One package's zip.</summary>
    Task<byte[]> DownloadAsync(string url, CancellationToken cancellationToken);
}

/// <summary>The release's logic list, over HTTPS.</summary>
public sealed class HttpLogicSource : ILogicSource
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(2) };
    private readonly string? _listUrl;

    /// <summary>Initializes a new instance of the <see cref="HttpLogicSource"/> class.</summary>
    public HttpLogicSource(string? listUrl) => _listUrl = listUrl;

    /// <inheritdoc />
    public bool Configured => !string.IsNullOrEmpty(_listUrl);

    /// <inheritdoc />
    public async Task<IReadOnlyList<LogicListing>> ListAsync(CancellationToken cancellationToken)
    {
        var json = await Http.GetStringAsync(_listUrl, cancellationToken).ConfigureAwait(false);
        return JsonSerializer.Deserialize<LogicList>(json)?.Packages ?? [];
    }

    /// <inheritdoc />
    public Task<byte[]> DownloadAsync(string url, CancellationToken cancellationToken) => Http.GetByteArrayAsync(url, cancellationToken);
}

/// <summary>
/// Looks for a newer logic package and swaps it in without a restart: only a package listed with
/// a SHA-256 that matches, signed with the key compiled into this shell, saying the version it's
/// listed as, newer than the running one, not failed before, and built for a contract this shell
/// takes. A newer one that needs a newer contract waits for the plugin update (a restart).
/// </summary>
public sealed class LogicUpdater
{
    private readonly LogicHost _host;
    private readonly ILogicSource _source;
    private readonly ILogger _log;
    private readonly SemaphoreSlim _checking = new(1, 1);

    /// <summary>Initializes a new instance of the <see cref="LogicUpdater"/> class.</summary>
    public LogicUpdater(LogicHost host, ILogicSource source, ILogger log)
    {
        _host = host;
        _source = source;
        _log = log;
    }

    /// <summary>Gets a newer logic version that waits for a newer plugin (restart), if any.</summary>
    public string? NeedsPlugin { get; private set; }

    /// <summary>Gets a value indicating whether this build can take logic updates at all.</summary>
    public bool Enabled => _source.Configured && _host.Store.PublicKey is not null;

    /// <summary>Looks now and swaps in what it finds; says what happened, in words.</summary>
    public async Task<string> CheckAsync(CancellationToken cancellationToken)
    {
        if (!Enabled)
        {
            return "This build takes no logic updates.";
        }

        await _checking.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var running = _host.RunningVersion ?? new Version(0, 0);
            var newer = (await _source.ListAsync(cancellationToken).ConfigureAwait(false))
                .Where(l => LogicPackage.ParseVersion(l.Version) > running && !_host.Store.IsBad(l.Version))
                .OrderByDescending(l => LogicPackage.ParseVersion(l.Version))
                .ToList();
            var fits = newer.FirstOrDefault(l => ShellContract.Supports(l.Contract));
            NeedsPlugin = newer.FirstOrDefault(l => l.Contract > ShellContract.Version && (fits is null || LogicPackage.ParseVersion(l.Version) > LogicPackage.ParseVersion(fits.Version)))?.Version;
            if (fits is null)
            {
                return NeedsPlugin is { } needs ? $"Logic {needs} needs a newer plugin; it comes with the next plugin update." : "The logic is up to date.";
            }

            var package = await DownloadAsync(fits, cancellationToken).ConfigureAwait(false);
            var outcome = await _host.SwapAsync(package, cancellationToken).ConfigureAwait(false);
            return outcome switch
            {
                SwapOutcome.Swapped => $"Logic {package.Version} runs now (no restart needed).",
                SwapOutcome.RolledBack => $"Logic {package.Version} failed to start; the previous logic runs again.",
                SwapOutcome.Refused => $"Logic {package.Version} couldn't be loaded; the running logic carries on.",
                _ => $"Logic {package.Version} failed to start, and no logic runs now.",
            };
        }
        catch (LogicPackageException e)
        {
            _log.LogWarning("Nitrate Companion: a logic update was refused: {Reason}", e.Message);
            return "A logic update was refused: " + e.Message;
        }
        finally
        {
            _checking.Release();
        }
    }

    /// <summary>Downloads and verifies a listed package (throws <see cref="LogicPackageException"/> when it doesn't hold up).</summary>
    public async Task<LogicPackage> DownloadAsync(LogicListing listing, CancellationToken cancellationToken)
    {
        var zip = await _source.DownloadAsync(listing.Url, cancellationToken).ConfigureAwait(false);
        return Verify(zip, listing, _host.Store.PublicKey);
    }

    /// <summary>Checks a downloaded zip against its listing and the signing key, and reads it.</summary>
    public static LogicPackage Verify(byte[] zip, LogicListing listing, byte[]? publicKey)
    {
        if (!string.Equals(LogicSignature.Sha256(zip), listing.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new LogicPackageException($"Logic {listing.Version}'s download doesn't match its SHA-256.");
        }

        byte[] signature;
        try
        {
            signature = Convert.FromBase64String(listing.Signature);
        }
        catch (FormatException)
        {
            throw new LogicPackageException($"Logic {listing.Version}'s signature isn't readable.");
        }

        if (!LogicSignature.Verify(zip, signature, publicKey))
        {
            throw new LogicPackageException($"Logic {listing.Version} isn't signed with this plugin's key.");
        }

        var package = LogicPackage.Read(zip, LogicStore.Downloaded, signature);
        if (package.Version != listing.Version || package.Manifest.Contract != listing.Contract)
        {
            throw new LogicPackageException($"Logic {listing.Version}'s package says it is {package.Version} (contract {package.Manifest.Contract}).");
        }

        return package;
    }
}
