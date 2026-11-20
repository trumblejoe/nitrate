using MediaBrowser.Controller;
using MediaBrowser.Controller.Plugins;
using Microsoft.Extensions.DependencyInjection;
using MediaBrowser.Common.Configuration;
using Nitrate.Companion.Channels;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Partners;
using Nitrate.Companion.Scripts;
using Nitrate.Companion.Sso;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;

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
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Diary.DiaryHub(
            new Nitrate.Companion.Diary.FileDiaryStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "diary")),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddHostedService<Nitrate.Companion.Diary.DiaryRecorder>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Lines.LineIndex(
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "lines.db")));
        serviceCollection.AddSingleton(sp => new ChannelHub(
            new FileChannelStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "channels")),
            new JellyfinChannelLibrary(sp.GetRequiredService<ILibraryManager>(), sp.GetRequiredService<IUserManager>()),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new PartnerHub(
            new FilePartnerStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "partners.json")),
            new JellyfinPartnerDirectory(sp.GetRequiredService<IUserManager>()),
            new JellyfinWatchState(sp.GetRequiredService<ILibraryManager>(), sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<IUserDataManager>()),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Devices.DeviceHub(
            new Nitrate.Companion.Devices.FileDeviceReportStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "devices.json")),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Together.InviteHub(sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new SsoService(
            () => SsoSettings.From(Plugin.Instance?.Configuration),
            new JellyfinSsoAccounts(sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<IDeviceManager>(), sp.GetRequiredService<ISessionManager>()),
            new HttpClient { Timeout = TimeSpan.FromSeconds(20) },
            sp.GetRequiredService<TimeProvider>()));
    }
}
