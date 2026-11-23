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
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.QueryTrendStore(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddHostedService<Nitrate.Companion.Insights.QueryTrendRecorder>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.SegmentVotes(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.PeopleRecords(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.TimelineNoteBook(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), TimeProvider.System));
        serviceCollection.AddHostedService<Nitrate.Companion.Insights.SittingRecorder>();
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.BecauseStore>();
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.BecauseBuilder>();
        serviceCollection.AddHostedService<Nitrate.Companion.Recommendations.BecauseWarmup>();
        serviceCollection.AddSingleton<Nitrate.Companion.Formats.FormatIndex>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Formats.FormatIndex>());
        // The server's own time for each request Nitrate tags (the apps' query analyzer).
        serviceCollection.AddSingleton<Nitrate.Companion.QueryTimes.QueryTimeStore>();
        serviceCollection.AddTransient<Microsoft.AspNetCore.Hosting.IStartupFilter, Nitrate.Companion.QueryTimes.QueryTimingStartupFilter>();
        // Shared scripts live in the server's data folder (not the plugin's own, which each version replaces).
        serviceCollection.AddSingleton<IScriptStorage>(sp =>
            new DbScriptStorage(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "scripts")));
        serviceCollection.AddSingleton<ScriptHub>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Diary.DiaryHub(
            new Nitrate.Companion.Storage.DbDiaryStorage(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "diary")),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddHostedService<Nitrate.Companion.Diary.DiaryRecorder>();
        // Installs newer versions of itself, and restarts at night to finish them.
        serviceCollection.AddHostedService<Nitrate.Companion.Updates.SelfUpdater>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Lines.LineIndex(
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "lines.db")));
        serviceCollection.AddSingleton(sp => new ChannelHub(
            new FileChannelStorage(Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "channels")),
            new JellyfinChannelLibrary(sp.GetRequiredService<ILibraryManager>(), sp.GetRequiredService<IUserManager>()),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new PartnerHub(
            new DbPartnerStorage(
                sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
                Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "partners.json")),
            new JellyfinPartnerDirectory(sp.GetRequiredService<IUserManager>()),
            new JellyfinWatchState(sp.GetRequiredService<ILibraryManager>(), sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<IUserDataManager>()),
            sp.GetRequiredService<TimeProvider>()));
        // Group chat pinned to the timeline: one note per message, live sessions getting each at once.
        serviceCollection.AddSingleton<Nitrate.Companion.Insights.ILiveSessions>(sp => new JellyfinLiveSessions(
            sp.GetRequiredService<MediaBrowser.Controller.SyncPlay.ISyncPlayManager>(), sp.GetRequiredService<ISessionManager>(), sp.GetRequiredService<IUserManager>()));
        serviceCollection.AddSingleton(sp =>
        {
            var partners = sp.GetRequiredService<PartnerHub>();
            return new Nitrate.Companion.Insights.GroupNotes(
                sp.GetRequiredService<Nitrate.Companion.Insights.TimelineNoteBook>(),
                sp.GetRequiredService<ChatHub>(),
                sp.GetRequiredService<Nitrate.Companion.Insights.ILiveSessions>(),
                partners.GroupsHolding,
                partners.IsPrivate,
                () => Plugin.Instance?.Configuration.KeepChatHistory ?? true,
                sp.GetRequiredService<TimeProvider>());
        });
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Devices.DeviceHub(
            new Nitrate.Companion.Storage.DbDeviceReportStorage(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "devices.json")),
            sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Together.InviteHub(sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.SmartCollections.SmartCollectionStore(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "smart-collections.json")));
        serviceCollection.AddHostedService<Nitrate.Companion.SmartCollections.SmartCollectionWatcher>();
        // The nightly tasks' results, fingerprints and unreadable files: one database, one row per
        // title (results from the older JSON files and fingerprint folder are moved in once).
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Storage.CompanionDb(
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "companion.db")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Timing.TimingStore(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "timing.json")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Credits.CreditStore(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "credits.json")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Intros.IntroStore(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Comics.ComicStore(
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().CachePath, "nitrate-companion", "comics")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Catalogs.CatalogFetcher(
            Nitrate.Companion.Catalogs.CatalogFetcher.NewClient(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().CachePath, "nitrate-companion", "catalog-books")));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Catalogs.CatalogPositions(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(),
            Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "catalog-positions")));
        serviceCollection.AddSingleton(sp => new SsoService(
            () => SsoSettings.From(Plugin.Instance?.Configuration),
            new JellyfinSsoAccounts(sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<IDeviceManager>(), sp.GetRequiredService<ISessionManager>()),
            new HttpClient { Timeout = TimeSpan.FromSeconds(20) },
            sp.GetRequiredService<TimeProvider>()));
    }
}
