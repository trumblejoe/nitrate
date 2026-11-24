using System.Text.Json;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Hosting;

[assembly: CompanionLogic(typeof(Nitrate.Companion.Logic.LogicFactory), ShellContract.Version)]

namespace Nitrate.Companion.Logic;

/// <summary>Makes the logic for the shell.</summary>
public sealed class LogicFactory : ICompanionLogicFactory
{
    /// <inheritdoc />
    public ICompanionLogic Create(LogicContext context) => new CompanionLogic(context);
}

/// <summary>
/// The plugin's features as the shell runs them: its own service container, the API (the
/// controllers, run by <see cref="ApiRouter"/>), the background services, the nightly tasks and
/// the segment provider, and what it hands to the next logic. See docs/features/companion-hotswap.md.
/// </summary>
public sealed class CompanionLogic : ICompanionLogic
{
    // Its own resolver, so its cache is never shared with the server's options (which would keep this logic in memory).
    private static readonly JsonSerializerOptions StateJson = new() { TypeInfoResolver = new System.Text.Json.Serialization.Metadata.DefaultJsonTypeInfoResolver() };

    private readonly ServiceProvider _services;
    private readonly ApiRouter _router;
    private readonly ILogger _log;
    private readonly bool _handedRows;
    private readonly CancellationTokenRegistration _wake;
    private List<IHostedService> _started = new();

    /// <summary>Initializes a new instance of the <see cref="CompanionLogic"/> class.</summary>
    public CompanionLogic(LogicContext context)
    {
        _log = context.LoggerFactory.CreateLogger("Nitrate.Companion.Logic");
        var services = new ServiceCollection();
        LogicServices.Register(services, context.HostServices, context.LoggerFactory);
        _services = services.BuildServiceProvider();
        _router = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly);
        Tasks = new Dictionary<string, IScheduledTask>
        {
            [typeof(Credits.CreditScanner).FullName!] = _services.GetRequiredService<Credits.CreditScanJob>(),
            [typeof(Intros.IntroScanner).FullName!] = _services.GetRequiredService<Intros.IntroScanJob>(),
            [typeof(Recommendations.BecauseBuilder).FullName!] = _services.GetRequiredService<Recommendations.BecauseRowsBuilder>(),
            [typeof(Lines.LineIndexer).FullName!] = _services.GetRequiredService<Lines.LineIndexJob>(),
            [typeof(Timing.TimingScanner).FullName!] = _services.GetRequiredService<Timing.TimingScanJob>(),
            [typeof(SmartCollections.SmartCollectionUpdater).FullName!] = _services.GetRequiredService<SmartCollections.SmartCollectionJob>(),
        };
        Segments = _services.GetRequiredService<Segments.CompanionSegmentProvider>();
        _handedRows = context.State.ContainsKey(HandOver.Because);
        ImportState(context.State);

        // Being swapped out: long polls answer at once, as if their wait ran out, and clients ask again.
        _wake = context.Stopping.Register(() =>
        {
            _services.GetRequiredService<Chat.ChatHub>().WakeAll();
            _services.GetRequiredService<Scripts.ScriptHub>().WakeAll();
        });
    }

    /// <inheritdoc />
    public IReadOnlyDictionary<string, IScheduledTask> Tasks { get; }

    /// <inheritdoc />
    public IMediaSegmentProvider? Segments { get; }

    /// <summary>Gets the logic's own services.</summary>
    public IServiceProvider Services => _services;

    /// <summary>Gets the routes served, as "METHOD template".</summary>
    public IEnumerable<string> Routes => _router.Routes;

    /// <inheritdoc />
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        foreach (var hosted in LogicServices.Hosted(_services))
        {
            // Rows handed over from the previous logic needn't be made again.
            if (_handedRows && hosted is Recommendations.BecauseWarmup)
            {
                continue;
            }

            await hosted.StartAsync(cancellationToken).ConfigureAwait(false);
            _started.Add(hosted);
        }
    }

    /// <inheritdoc />
    public async Task StopAsync(CancellationToken cancellationToken)
    {
        var started = Interlocked.Exchange(ref _started, new List<IHostedService>());
        foreach (var hosted in Enumerable.Reverse(started))
        {
            try
            {
                await hosted.StopAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                _log.LogWarning(e, "Nitrate Companion: {Service} didn't stop cleanly", hosted.GetType().Name);
            }
        }
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync()
    {
        await _wake.DisposeAsync().ConfigureAwait(false);
        await _services.DisposeAsync().ConfigureAwait(false);
    }

    /// <inheritdoc />
    public RouteMatch Match(string method, string path) => _router.Match(method, path);

    /// <inheritdoc />
    public Task<IActionResult> InvokeAsync(RouteMatch match, ControllerContext context) => _router.InvokeAsync(match, context, _services);

    /// <inheritdoc />
    public void RequestStarting(HttpContext context) => QueryTimes.QueryTiming.Starting(_services.GetRequiredService<QueryTimes.QueryTimeStore>());

    /// <inheritdoc />
    public void RequestFinished(HttpContext context, TimeSpan elapsed) =>
        QueryTimes.QueryTiming.Finished(_services.GetRequiredService<QueryTimes.QueryTimeStore>(), context, elapsed);

    /// <inheritdoc />
    public IReadOnlyDictionary<string, byte[]> ExportState()
    {
        var state = new Dictionary<string, byte[]>();
        void Put<T>(string key, Func<T> export)
        {
            try
            {
                state[key] = JsonSerializer.SerializeToUtf8Bytes(export(), StateJson);
            }
            catch (Exception e)
            {
                _log.LogWarning(e, "Nitrate Companion: couldn't hand over {Key}", key);
            }
        }

        Put(HandOver.Chat, () => _services.GetRequiredService<Chat.ChatHub>().ExportState());
        Put(HandOver.Invites, () => _services.GetRequiredService<Together.InviteHub>().ExportState());
        Put(HandOver.Brought, () => _services.GetRequiredService<Insights.GroupNotes>().ExportState());
        Put(HandOver.Sso, () => _services.GetRequiredService<Sso.SsoService>().ExportState());
        Put(HandOver.QueryTimes, () => _services.GetRequiredService<QueryTimes.QueryTimeStore>().ExportState());
        Put(HandOver.Because, () => _services.GetRequiredService<Recommendations.BecauseStore>().ExportState());
        Put(HandOver.Sittings, () => _services.GetRequiredService<Insights.SittingRecorder>().ExportState());
        Put(HandOver.Together, () => _services.GetRequiredService<Partners.TogetherRecorder>().ExportState());
        Put(HandOver.GroupSessions, () => _services.GetRequiredService<Partners.PartnerHub>().ExportSessions());
        Put(HandOver.WatchSessions, () => _services.GetRequiredService<Partners.WatchSessions>().ExportState());
        return state;
    }

    private void ImportState(IReadOnlyDictionary<string, byte[]> state)
    {
        void Take<T>(string key, Action<T> import)
        {
            if (!state.TryGetValue(key, out var bytes))
            {
                return;
            }

            try
            {
                if (JsonSerializer.Deserialize<T>(bytes, StateJson) is { } value)
                {
                    import(value);
                }
            }
            catch (Exception e)
            {
                _log.LogWarning(e, "Nitrate Companion: couldn't take over {Key}", key);
            }
        }

        Take<Chat.ChatState>(HandOver.Chat, s => _services.GetRequiredService<Chat.ChatHub>().ImportState(s));
        Take<List<Together.WatchInvite>>(HandOver.Invites, s => _services.GetRequiredService<Together.InviteHub>().ImportState(s));
        Take<List<Insights.BroughtState>>(HandOver.Brought, s => _services.GetRequiredService<Insights.GroupNotes>().ImportState(s));
        Take<List<Sso.PendingSignInState>>(HandOver.Sso, s => _services.GetRequiredService<Sso.SsoService>().ImportState(s));
        Take<QueryTimes.QueryTimesState>(HandOver.QueryTimes, s => _services.GetRequiredService<QueryTimes.QueryTimeStore>().ImportState(s));
        Take<Dictionary<Guid, Recommendations.BecauseSet>>(HandOver.Because, s => _services.GetRequiredService<Recommendations.BecauseStore>().ImportState(s));
        Take<List<Insights.SittingState>>(HandOver.Sittings, s => _services.GetRequiredService<Insights.SittingRecorder>().ImportState(s));
        Take<List<Partners.TogetherSeenState>>(HandOver.Together, s => _services.GetRequiredService<Partners.TogetherRecorder>().ImportState(s));
        Take<List<Partners.GroupSessionState>>(HandOver.GroupSessions, s => _services.GetRequiredService<Partners.PartnerHub>().ImportSessions(s));
        Take<List<Partners.WatchSessionState>>(HandOver.WatchSessions, s => _services.GetRequiredService<Partners.WatchSessions>().ImportState(s));
    }
}
