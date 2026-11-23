using Microsoft.Extensions.Logging.Abstractions;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Updates;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>Only signed, listed, matching, newer packages for a contract this shell takes are swapped in.</summary>
public sealed class LogicUpdaterTests : IDisposable
{
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public void SignaturesAreCheckedAgainstTheKey()
    {
        using var key = HostingKit.NewKey();
        using var other = HostingKit.NewKey();
        var zip = HostingKit.Package("1.0.0.2");
        var signature = HostingKit.Sign(key, zip);
        Assert.True(LogicSignature.Verify(zip, signature, key.ExportSubjectPublicKeyInfo()));
        Assert.False(LogicSignature.Verify(zip, signature, other.ExportSubjectPublicKeyInfo()));
        Assert.False(LogicSignature.Verify(zip, HostingKit.Sign(other, zip), key.ExportSubjectPublicKeyInfo()));
        var tampered = (byte[])zip.Clone();
        tampered[^10] ^= 1;
        Assert.False(LogicSignature.Verify(tampered, signature, key.ExportSubjectPublicKeyInfo()));
        Assert.False(LogicSignature.Verify(zip, [1, 2, 3], key.ExportSubjectPublicKeyInfo()));
        Assert.False(LogicSignature.Verify(zip, signature, null));
        Assert.False(LogicSignature.Verify(zip, signature, [1, 2, 3]));
    }

    [Fact]
    public async Task ASignedNewerPackageIsSwappedIn()
    {
        using var key = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        source.Add(key, "1.0.0.2");
        Assert.Contains("1.0.0.2 runs now", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.2", host.Status.Version);
        Assert.Contains("up to date", await updater.CheckAsync(CancellationToken.None));
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ATamperedDownloadIsRefused()
    {
        using var key = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        var listing = source.Add(key, "1.0.0.2");
        source.Zips[listing.Url][^10] ^= 1;
        Assert.Contains("doesn't match its SHA-256", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.1", host.Status.Version);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task APackageSignedWithAnotherKeyIsRefused()
    {
        using var key = HostingKit.NewKey();
        using var other = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        source.Add(other, "1.0.0.2");
        Assert.Contains("isn't signed with this plugin's key", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.1", host.Status.Version);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task APackageThatIsntWhatItsListedAsIsRefused()
    {
        using var key = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        var listing = source.Add(key, "1.0.0.2");
        source.Listings[0] = listing with { Version = "1.0.0.3" };
        Assert.Contains("says it is 1.0.0.2", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.1", host.Status.Version);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ALogicForANewerContractWaitsForThePluginUpdate()
    {
        using var key = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        source.Add(key, "1.0.0.9", ShellContract.Version + 1);
        Assert.Contains("needs a newer plugin", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.9", updater.NeedsPlugin);
        Assert.Equal("1.0.0.1", host.Status.Version);

        // A newer one this shell takes still comes in meanwhile.
        source.Add(key, "1.0.0.4");
        Assert.Contains("1.0.0.4 runs now", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.9", updater.NeedsPlugin);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task AVersionThatFailedIsntTriedAgain()
    {
        using var key = HostingKit.NewKey();
        var (updater, host, source) = await Start(key);
        source.Add(key, "1.0.0.13");
        Assert.Contains("previous logic runs again", await updater.CheckAsync(CancellationToken.None));
        Assert.Contains("up to date", await updater.CheckAsync(CancellationToken.None));
        Assert.Equal("1.0.0.1", host.Status.Version);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task WithoutAKeyOrAListThereAreNoLogicUpdates()
    {
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"));
        await host.StartAsync(CancellationToken.None);
        var updater = new LogicUpdater(host, new FakeSource(), NullLogger.Instance);
        Assert.False(updater.Enabled);
        Assert.Contains("no logic updates", await updater.CheckAsync(CancellationToken.None));
        Assert.False(new HttpLogicSource(null).Configured);
        await host.DisposeAsync();
    }

    private async Task<(LogicUpdater Updater, LogicHost Host, FakeSource Source)> Start(System.Security.Cryptography.ECDsa key)
    {
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        var source = new FakeSource();
        return (new LogicUpdater(host, source, NullLogger.Instance), host, source);
    }

    private sealed class FakeSource : ILogicSource
    {
        public List<LogicListing> Listings { get; } = new();

        public Dictionary<string, byte[]> Zips { get; } = new();

        public bool Configured => true;

        public LogicListing Add(System.Security.Cryptography.ECDsa key, string version, int contract = ShellContract.Version)
        {
            var zip = HostingKit.Package(version, contract);
            var url = $"https://example.invalid/nitrate-companion-logic-{version}.zip";
            Zips[url] = zip;
            var listing = new LogicListing(version, contract, url, LogicSignature.Sha256(zip), Convert.ToBase64String(HostingKit.Sign(key, zip)));
            Listings.Insert(0, listing);
            return listing;
        }

        public Task<IReadOnlyList<LogicListing>> ListAsync(CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<LogicListing>>(Listings.ToList());

        public Task<byte[]> DownloadAsync(string url, CancellationToken cancellationToken) => Task.FromResult(Zips[url]);
    }
}
