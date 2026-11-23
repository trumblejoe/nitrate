using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Plugins;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Hosting;

namespace Nitrate.Companion;

/// <summary>
/// Registers the shell with the server: the logic host and what forwards to it. Everything the
/// plugin does is registered by the logic in its own container (<c>Nitrate.Companion.Logic</c>),
/// never here, so it can be swapped without a restart. The API controllers (<see cref="DispatchController"/>,
/// <see cref="Updates.UpdatesController"/>), scheduled tasks (<see cref="LogicTask"/>) and segment
/// provider (<see cref="LogicSegments"/>) are found by the server itself.
/// </summary>
public class ServiceRegistrator : IPluginServiceRegistrator
{
    /// <inheritdoc />
    public void RegisterServices(IServiceCollection serviceCollection, IServerApplicationHost applicationHost)
    {
        serviceCollection.AddSingleton(sp => new LogicStore(
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "logic"),
            LogicStore.BuiltInPathFor(typeof(Plugin).Assembly),
            LogicSignature.BuiltInPublicKey));
        serviceCollection.AddSingleton(sp => new LogicHost(sp, sp.GetRequiredService<ILoggerFactory>(), sp.GetRequiredService<LogicStore>()));
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<LogicHost>());
        serviceCollection.AddTransient<Microsoft.AspNetCore.Hosting.IStartupFilter, LogicTimingStartupFilter>();
        // Installs newer versions of the plugin (restart) and swaps in newer logic (no restart).
        // One updater: the hosted loop, and the one the dashboard and apps ask (Updates/UpdatesController).
        serviceCollection.AddSingleton(sp => new Updates.LogicUpdater(
            sp.GetRequiredService<LogicHost>(),
            new Updates.HttpLogicSource(LogicSignature.ManifestUrl),
            sp.GetRequiredService<ILoggerFactory>().CreateLogger("Nitrate.Companion.Updates")));
        serviceCollection.AddSingleton<Updates.SelfUpdater>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Updates.SelfUpdater>());
    }
}
