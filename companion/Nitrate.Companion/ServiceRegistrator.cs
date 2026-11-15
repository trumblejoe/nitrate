using MediaBrowser.Controller;
using MediaBrowser.Controller.Plugins;
using Microsoft.Extensions.DependencyInjection;
using MediaBrowser.Common.Configuration;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Scripts;

namespace Nitrate.Companion;

/// <summary>Registers the plugin's services with the server.</summary>
public class ServiceRegistrator : IPluginServiceRegistrator
{
    /// <inheritdoc />
    public void RegisterServices(IServiceCollection serviceCollection, IServerApplicationHost applicationHost)
    {
        serviceCollection.AddSingleton(TimeProvider.System);
        serviceCollection.AddSingleton<ChatHub>();
        // Shared scripts live in the server's data folder (not the plugin's own, which each version replaces).
        serviceCollection.AddSingleton<IScriptStorage>(sp =>
            new FileScriptStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "scripts")));
        serviceCollection.AddSingleton<ScriptHub>();
    }
}
