using System.Reflection;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Channels;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Partners;
using Nitrate.Companion.Scripts;
using Nitrate.Companion.Sso;

namespace Nitrate.Companion.Logic;

/// <summary>
/// The logic's own services, in its own container (never the server's, so nothing of the logic is
/// kept by the server after a swap). The server's services it needs are passed through from the
/// server's container; logging comes from the server's logger factory.
/// </summary>
public static class LogicServices
{
    /// <summary>The logic's API controllers.</summary>
    public static IReadOnlyList<Type> Controllers { get; } = typeof(LogicServices).Assembly.GetTypes()
        .Where(t => typeof(ControllerBase).IsAssignableFrom(t) && !t.IsAbstract && t.GetCustomAttribute<RouteAttribute>() is not null)
        .ToList();

    /// <summary>Registers everything the plugin does.</summary>
    public static void Register(IServiceCollection serviceCollection, IServiceProvider host, ILoggerFactory logs)
    {
        serviceCollection.AddSingleton(logs);
        serviceCollection.AddSingleton(typeof(ILogger<>), typeof(Logger<>));
        serviceCollection.AddSingleton(TimeProvider.System);
        serviceCollection.AddSingleton<ChatHub>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.QueryTrendStore(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddHostedService<Nitrate.Companion.Insights.QueryTrendRecorder>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.SegmentVotes(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.PeopleRecords(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>()));
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.TimelineNoteBook(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), TimeProvider.System));
        serviceCollection.AddSingleton<Nitrate.Companion.Insights.SittingRecorder>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Insights.SittingRecorder>());
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.BecauseStore>();
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.BecauseRowsBuilder>();
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.BecauseWarmup>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Recommendations.BecauseWarmup>());
        // "Anything changed since?" for the apps' kept rows (GET /Nitrate/Changes).
        serviceCollection.AddSingleton<Nitrate.Companion.Changes.ChangeCounters>();
        serviceCollection.AddSingleton<Nitrate.Companion.Changes.ChangeStamps>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Changes.ChangeStamps>());
        serviceCollection.AddSingleton<Nitrate.Companion.Recommendations.PlaylistFacts>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Recommendations.PlaylistFacts>());
        serviceCollection.AddSingleton<Nitrate.Companion.Formats.FormatIndex>();
        serviceCollection.AddSingleton<Nitrate.Companion.Images.ImageFiles>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<Nitrate.Companion.Formats.FormatIndex>());
        // The server's own time for each request Nitrate tags (the apps' query analyzer); the shell's middleware times them.
        serviceCollection.AddSingleton<Nitrate.Companion.QueryTimes.QueryTimeStore>();
        // Shared scripts live in the server's data folder (not the plugin's own, which each version replaces).
        serviceCollection.AddSingleton<IScriptStorage>(sp =>
            new DbScriptStorage(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "scripts")));
        serviceCollection.AddSingleton<ScriptHub>();
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Diary.DiaryHub(
            new Nitrate.Companion.Storage.DbDiaryStorage(sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), Path.Combine(sp.GetRequiredService<IApplicationPaths>().DataPath, "nitrate-companion", "diary")),
            sp.GetRequiredService<TimeProvider>()));
        // A group watch kept out of the person's own history (their state put back when it stops).
        serviceCollection.AddSingleton<GroupPlays>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<GroupPlays>());
        serviceCollection.AddHostedService<Nitrate.Companion.Diary.DiaryRecorder>();
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
            new JellyfinWatchState(sp.GetRequiredService<ILibraryManager>(), sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<IUserDataManager>(), sp.GetService<MediaBrowser.Controller.Playlists.IPlaylistManager>()),
            sp.GetRequiredService<TimeProvider>()));
        // Group Watch Sessions: a group watching one part at the same moment, each on their own player.
        serviceCollection.AddSingleton(sp => new WatchSessions(sp.GetRequiredService<PartnerHub>(), sp.GetRequiredService<TimeProvider>()));
        // Asks to watch together: a member asks the group to start a session, someone else hosts it.
        serviceCollection.AddSingleton(sp => new WatchAsks(sp.GetRequiredService<PartnerHub>(), sp.GetRequiredService<TimeProvider>()));
        // Groups' own watched records, from the server's playback reports of live sessions.
        serviceCollection.AddSingleton<TogetherRecorder>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<TogetherRecorder>());
        // Watch party reminders and news, sent to the members' open Nitrate apps over Jellyfin's own connection.
        serviceCollection.AddHostedService<PartyPush>();
        // Group chat pinned to the timeline: one note per message, live sessions getting each at once.
        serviceCollection.AddSingleton<Nitrate.Companion.Insights.ILiveSessions>(sp => new JellyfinLiveSessions(
            sp.GetRequiredService<MediaBrowser.Controller.SyncPlay.ISyncPlayManager>(), sp.GetRequiredService<ISessionManager>(), sp.GetRequiredService<IUserManager>(), sp.GetRequiredService<WatchSessions>()));
        // Each watch group's conversation per title (threads); the chat histories kept before them were test data, deleted once.
        serviceCollection.AddSingleton(sp =>
        {
            var threads = new Nitrate.Companion.Insights.ThreadBook(
                sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), sp.GetRequiredService<Nitrate.Companion.Insights.TimelineNoteBook>(), sp.GetRequiredService<TimeProvider>());
            threads.ForgetChatHistoriesOnce();
            return threads;
        });
        // Chats' time rules: the admin's "Delete chats after" (ten years unless set), favourites and kept people (daily).
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Insights.ChatKeeping(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), sp.GetRequiredService<Nitrate.Companion.Insights.TimelineNoteBook>(), sp.GetRequiredService<Nitrate.Companion.Insights.ThreadBook>()));
        serviceCollection.AddHostedService<Nitrate.Companion.Insights.ChatPruner>();
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
                sp.GetRequiredService<TimeProvider>(),
                sp.GetRequiredService<Nitrate.Companion.Insights.ThreadBook>());
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

        // Metadata editors: who may edit where, the log of every change, and per-editor limits (memory only).
        serviceCollection.AddSingleton(sp => new Nitrate.Companion.Metadata.MetadataEditorStore(
            sp.GetRequiredService<Nitrate.Companion.Storage.CompanionDb>(), sp.GetRequiredService<TimeProvider>()));
        serviceCollection.AddSingleton<Nitrate.Companion.Metadata.MetadataLimiter>();
        serviceCollection.AddSingleton<Nitrate.Companion.Metadata.IMetadataServer, Nitrate.Companion.Metadata.JellyfinMetadataServer>();

        // The nightly tasks (run through the shell's adapters, which keep their old names) and the segment provider.
        serviceCollection.AddSingleton<Nitrate.Companion.Credits.CreditScanJob>();
        serviceCollection.AddSingleton<Nitrate.Companion.Intros.IntroScanJob>();
        serviceCollection.AddSingleton<Nitrate.Companion.Lines.LineIndexJob>();
        serviceCollection.AddSingleton<Nitrate.Companion.Timing.TimingScanJob>();
        serviceCollection.AddSingleton<Nitrate.Companion.SmartCollections.SmartCollectionJob>();
        serviceCollection.AddSingleton<Nitrate.Companion.Segments.CompanionSegmentProvider>();

        ForwardServerServices(serviceCollection, host, Controllers);
    }

    /// <summary>
    /// Passes through every server service the logic's classes take that the logic doesn't make
    /// itself (Jellyfin's managers, paths, the encoder...), looked up in the server's container.
    /// Each goes in as the server's own instance, never through a factory: a container disposes
    /// what its factories made when it's disposed, so the logic's container (disposed on every
    /// swap) used to dispose Jellyfin's SessionManager, SyncPlay and the rest, breaking sign-in,
    /// live connections and every request needing a session until the server restarted.
    /// </summary>
    public static void ForwardServerServices(IServiceCollection services, IServiceProvider host, IEnumerable<Type> alsoBuilt)
    {
        var registered = services.Select(d => d.ServiceType).ToHashSet();
        var built = services.Select(d => d.ImplementationType).OfType<Type>().Concat(alsoBuilt).ToList();
        var wanted = new HashSet<Type>
        {
            typeof(IApplicationPaths), typeof(ILibraryManager), typeof(IUserManager), typeof(IUserDataManager),
            typeof(ISessionManager), typeof(IDeviceManager), typeof(MediaBrowser.Controller.SyncPlay.ISyncPlayManager),
        };
        foreach (var type in built)
        {
            var constructor = type.GetConstructors().OrderByDescending(c => c.GetParameters().Length).FirstOrDefault();
            foreach (var p in constructor?.GetParameters() ?? [])
            {
                wanted.Add(p.ParameterType);
            }
        }

        foreach (var type in wanted)
        {
            if (registered.Contains(type) || (type.IsGenericType && type.GetGenericTypeDefinition() == typeof(ILogger<>))
                || type == typeof(IServiceProvider) || type.Assembly == typeof(LogicServices).Assembly || type.IsValueType || type == typeof(string))
            {
                continue;
            }

            // The server's instance as it is (not owned here, so never disposed with the logic);
            // one the server doesn't have stays unregistered and fails only where it's asked for.
            if (host.GetService(type) is { } instance)
            {
                services.AddSingleton(type, instance);
            }
        }
    }

    /// <summary>The hosted services, in the order they're started.</summary>
    public static IReadOnlyList<IHostedService> Hosted(IServiceProvider services) => services.GetServices<IHostedService>().ToList();
}
