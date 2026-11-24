using System.Net;
using System.Net.Http.Json;
using System.Runtime.CompilerServices;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Updates;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>Loading, swapping, handing over, rolling back and unloading logic, with the fake logic package.</summary>
public sealed class LogicHostTests : IDisposable
{
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public async Task StartsTheBuiltInLogicInItsOwnContext()
    {
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"));
        await host.StartAsync(CancellationToken.None);
        using (var lease = await host.AcquireAsync(CancellationToken.None))
        {
            Assert.NotNull(lease);
            var type = lease!.Logic.GetType();
            Assert.Equal("Nitrate.Companion.Tests.FakeLogic.FakeLogic", type.FullName);
            Assert.True(type.Assembly.IsCollectible);
            Assert.IsType<LogicLoadContext>(System.Runtime.Loader.AssemblyLoadContext.GetLoadContext(type.Assembly));
        }

        Assert.Equal(new LogicStatus("1.0.0.1", LogicStore.BuiltIn, host.Status.LoadedAt, null, null), host.Status);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ASwapServesTheNewLogicKeepsStateAndUnloadsTheOld()
    {
        using var key = HostingKit.NewKey();
        var (host, store) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        await using var server = await TestServer.StartAsync(host);
        var client = server.Client;
        Assert.Equal(1, (await Fake(client, HttpMethod.Post, "Count")).Count);
        Assert.Equal(2, (await Fake(client, HttpMethod.Post, "Count")).Count);
        // Exercise the JSON, MVC and DI paths of the old logic before it goes.
        Assert.Equal(HttpStatusCode.OK, (await client.SendAsync(Requests.As(HttpMethod.Get, "/Nitrate/Probe/List"))).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.SendAsync(Requests.As(HttpMethod.Post, "/Nitrate/Probe/Body", json: "{\"Count\":1}"))).StatusCode);

        Assert.Equal(SwapOutcome.Swapped, await host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.2"), CancellationToken.None));

        var after = await Fake(client, HttpMethod.Get, "Version");
        Assert.Equal("1.0.0.2", after.Version);
        Assert.Equal(2, after.Count);
        Assert.Equal("1.0.0.2", host.Status.Version);
        Assert.Equal(LogicStore.Downloaded, host.Status.Source);
        Assert.Contains("without a restart", host.Status.LastSwap);
        Assert.True(await host.WaitForUnloadAsync(TimeSpan.FromSeconds(30)), "the old logic's context is still in memory");
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ALongPollAnswersAtTheSwapAndTheNextOneWaitsInTheNewLogic()
    {
        using var key = HostingKit.NewKey();
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        await using var server = await TestServer.StartAsync(host);
        var polling = Fake(server.Client, HttpMethod.Get, "Wait?waitMs=25000");
        await Task.Delay(300);
        var started = DateTime.UtcNow;
        Assert.Equal(SwapOutcome.Swapped, await host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.2"), CancellationToken.None));
        var answer = await polling;
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(4), "the poll should end at the swap, not at its timeout");
        Assert.Equal("1.0.0.1", answer.Version);
        Assert.True(answer.Woken);

        // The client asks again: the new logic waits, and answers when something happens.
        var again = Fake(server.Client, HttpMethod.Get, "Wait?waitMs=25000");
        await Task.Delay(200);
        await Fake(server.Client, HttpMethod.Post, "Count");
        var next = await again;
        Assert.Equal("1.0.0.2", next.Version);
        Assert.True(next.Woken);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task RequestsDuringASwapWaitAndAreAnsweredByTheNewLogic()
    {
        using var key = HostingKit.NewKey();
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        await using var server = await TestServer.StartAsync(host);
        // Warm up first (a cold first request can take longer than the wait below on a slow machine), then give
        // the slow request plenty of time to be inside the old logic before the swap starts.
        Assert.Equal("1.0.0.1", (await Fake(server.Client, HttpMethod.Get, "Version")).Version);
        var slow = Fake(server.Client, HttpMethod.Get, "Slow?ms=3000");
        await Task.Delay(1000);
        var swap = host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.2"), CancellationToken.None);
        await Task.Delay(100);
        var during = await Task.WhenAll(Enumerable.Range(0, 5).Select(_ => Fake(server.Client, HttpMethod.Get, "Version")));
        Assert.Equal("1.0.0.1", (await slow).Version);
        Assert.Equal(SwapOutcome.Swapped, await swap);
        Assert.All(during, d => Assert.Equal("1.0.0.2", d.Version));
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ALogicThatFailsToStartIsRolledBackAndRemembered()
    {
        using var key = HostingKit.NewKey();
        var (host, store) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        await using var server = await TestServer.StartAsync(host);
        await Fake(server.Client, HttpMethod.Post, "Count");

        Assert.Equal(SwapOutcome.RolledBack, await host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.13"), CancellationToken.None));

        var now = await Fake(server.Client, HttpMethod.Get, "Version");
        Assert.Equal("1.0.0.1", now.Version);
        Assert.Equal(1, now.Count);
        Assert.True(store.IsBad("1.0.0.13"));
        Assert.Contains("went back to 1.0.0.1", host.Status.LastSwap);
        Assert.True(await host.WaitForUnloadAsync(TimeSpan.FromSeconds(30)), "the failed logic's context is still in memory");
        await host.DisposeAsync();
    }

    [Fact]
    public async Task APackageForANewerContractIsRefusedAndTheOldLogicNeverStops()
    {
        using var key = HostingKit.NewKey();
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        var zip = HostingKit.Package("1.0.0.2", contract: ShellContract.Version + 1);
        var newer = LogicPackage.Read(zip, LogicStore.Downloaded, HostingKit.Sign(key, zip));
        Assert.Equal(SwapOutcome.Refused, await host.SwapAsync(newer, CancellationToken.None));
        Assert.Equal("1.0.0.1", host.Status.Version);
        Assert.Contains("contract", host.Status.LastSwap);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task ARestartStartsTheLastGoodDownloadedLogicAndFallsBackToTheBuiltIn()
    {
        using var key = HostingKit.NewKey();
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        Assert.Equal(SwapOutcome.Swapped, await host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.5"), CancellationToken.None));
        await host.StopAsync(CancellationToken.None);

        var (restarted, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await restarted.StartAsync(CancellationToken.None);
        Assert.Equal("1.0.0.5", restarted.Status.Version);
        Assert.Equal(LogicStore.Downloaded, restarted.Status.Source);
        await restarted.StopAsync(CancellationToken.None);

        // A newer plugin's built-in logic wins over an older download.
        var (updated, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.7"), key);
        await updated.StartAsync(CancellationToken.None);
        Assert.Equal("1.0.0.7", updated.Status.Version);
        await updated.StopAsync(CancellationToken.None);

        // A kept package that's been tampered with is passed over.
        File.WriteAllBytes(Path.Combine(_dir.Path, "data", "logic", "1.0.0.5.zip"), HostingKit.Package("1.0.0.5", dll: HostingKit.FakeLogicDll.Concat(new byte[] { 0 }).ToArray()));
        var (tampered, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await tampered.StartAsync(CancellationToken.None);
        Assert.Equal("1.0.0.1", tampered.Status.Version);
        Assert.Equal(LogicStore.BuiltIn, tampered.Status.Source);
        await tampered.DisposeAsync();
    }

    [Fact]
    public async Task AScheduledTaskCutShortByASwapCarriesOnInTheNewLogic()
    {
        using var key = HostingKit.NewKey();
        var (host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"), key);
        await host.StartAsync(CancellationToken.None);
        var task = new Nitrate.Companion.Credits.CreditScanner(host);
        Assert.Equal("Fake 1.0.0.1", task.Name);
        var progress = new List<double>();
        var running = task.ExecuteAsync(new Progress<double>(p => { lock (progress) { progress.Add(p); } }), CancellationToken.None);
        await Task.Delay(300);
        Assert.False(running.IsCompleted);
        Assert.Equal(SwapOutcome.Swapped, await host.SwapAsync(HostingKit.Downloaded(key, "1.0.0.2"), CancellationToken.None));
        await running.WaitAsync(TimeSpan.FromSeconds(10));
        await Task.Delay(100);
        lock (progress)
        {
            Assert.Contains(2.0, progress);
        }

        Assert.Equal("Fake 1.0.0.2", task.Name);
        await host.DisposeAsync();
    }

    [Fact]
    public async Task WithoutALogicTheTaskAdaptersStillDescribeThemselves()
    {
        var (host, _) = HostingKit.Host(_dir, null);
        await host.StartAsync(CancellationToken.None);
        var task = new Nitrate.Companion.Intros.IntroScanner(host);
        Assert.Equal("Find intros", task.Name);
        Assert.Equal("NitrateFindIntros", task.Key);
        Assert.Equal(TimeSpan.FromHours(1.5).Ticks, Assert.Single(task.GetDefaultTriggers()).TimeOfDayTicks);
        await task.ExecuteAsync(new Progress<double>(), CancellationToken.None);
        Assert.Null(host.Status.Version);
    }

    [Fact]
    public void TheTaskAdaptersKeepTheirOldTypeNames()
    {
        // Jellyfin keeps triggers and history by the task type's full name.
        Assert.Equal("Nitrate.Companion.Credits.CreditScanner", typeof(Nitrate.Companion.Credits.CreditScanner).FullName);
        Assert.Equal("Nitrate.Companion.Intros.IntroScanner", typeof(Nitrate.Companion.Intros.IntroScanner).FullName);
        Assert.Equal("Nitrate.Companion.Recommendations.BecauseBuilder", typeof(Nitrate.Companion.Recommendations.BecauseBuilder).FullName);
        Assert.Equal("Nitrate.Companion.Lines.LineIndexer", typeof(Nitrate.Companion.Lines.LineIndexer).FullName);
        Assert.Equal("Nitrate.Companion.Timing.TimingScanner", typeof(Nitrate.Companion.Timing.TimingScanner).FullName);
        Assert.Equal("Nitrate.Companion.SmartCollections.SmartCollectionUpdater", typeof(Nitrate.Companion.SmartCollections.SmartCollectionUpdater).FullName);
    }

    [Fact]
    public void APackageCantBringTheServersOwnAssemblies()
    {
        var zip = HostingKit.Package("1.0.0.2", extra: [("Jellyfin.Controller.dll", [1, 2, 3])]);
        Assert.Throws<LogicPackageException>(() => LogicPackage.Read(zip, LogicStore.Downloaded));
        var shell = HostingKit.Package("1.0.0.2", extra: [("Nitrate.Companion.dll", [1, 2, 3])]);
        Assert.Throws<LogicPackageException>(() => LogicPackage.Read(shell, LogicStore.Downloaded));
        Assert.Throws<LogicPackageException>(() => LogicPackage.Read([1, 2, 3], LogicStore.Downloaded));
    }

    private static async Task<FakeOut> Fake(HttpClient client, HttpMethod method, string path)
    {
        using var response = await client.SendAsync(Requests.As(method, "/Nitrate/Fake/" + path));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return (await response.Content.ReadFromJsonAsync<FakeOut>())!;
    }

    private sealed record FakeOut(string Version, int Count, bool Woken);
}
