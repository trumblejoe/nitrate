using Microsoft.Extensions.DependencyInjection;
using Nitrate.Companion.Logic;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>
/// The server's services the logic borrows are never the logic's to dispose: disposing the
/// logic's container on a swap once disposed Jellyfin's SessionManager (sign-in, live connections
/// and every request needing a session failed until the server restarted).
/// </summary>
public class ForwardedServicesTests
{
    public interface IServerThing
    {
        bool Disposed { get; }
    }

    private sealed class ServerThing : IServerThing, IDisposable, IAsyncDisposable
    {
        public bool Disposed { get; private set; }

        public void Dispose() => Disposed = true;

        public ValueTask DisposeAsync()
        {
            Disposed = true;
            return ValueTask.CompletedTask;
        }
    }

    private sealed class NeedsThing
    {
        public NeedsThing(IServerThing thing) => Thing = thing;

        public IServerThing Thing { get; }
    }

    [Fact]
    public async Task DisposingTheLogicLeavesTheServersServicesAlone()
    {
        var thing = new ServerThing();
        using var server = new ServiceCollection().AddSingleton<IServerThing>(thing).BuildServiceProvider();

        var logic = new ServiceCollection();
        logic.AddSingleton<NeedsThing>();
        LogicServices.ForwardServerServices(logic, server, Array.Empty<Type>());
        var provider = logic.BuildServiceProvider();
        Assert.Same(thing, provider.GetRequiredService<NeedsThing>().Thing);

        // A swap disposes the old logic's container, both ways it can be disposed.
        await provider.DisposeAsync();
        provider.Dispose();
        Assert.False(thing.Disposed);
    }

    [Fact]
    public void AServiceTheServerLacksIsLeftOut()
    {
        using var server = new ServiceCollection().BuildServiceProvider();
        var logic = new ServiceCollection();
        logic.AddSingleton<NeedsThing>();
        LogicServices.ForwardServerServices(logic, server, Array.Empty<Type>());
        Assert.DoesNotContain(logic, d => d.ServiceType == typeof(IServerThing));
    }
}
