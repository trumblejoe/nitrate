using System.Reflection;
using System.Runtime.CompilerServices;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Hosting;

/// <summary>Where the running logic stands, for the status page.</summary>
/// <param name="Version">The running logic's version, or null when none runs.</param>
/// <param name="Source">"built-in" or "downloaded".</param>
/// <param name="LoadedAt">When it started.</param>
/// <param name="LastSwapAt">When a swap was last tried.</param>
/// <param name="LastSwap">What that swap did, in words.</param>
public sealed record LogicStatus(string? Version, string? Source, DateTimeOffset? LoadedAt, DateTimeOffset? LastSwapAt, string? LastSwap);

/// <summary>What a swap did.</summary>
public enum SwapOutcome
{
    /// <summary>The new logic runs.</summary>
    Swapped,

    /// <summary>The new logic couldn't be loaded; the old one never stopped.</summary>
    Refused,

    /// <summary>The new logic failed to start; the old one was started again.</summary>
    RolledBack,

    /// <summary>The new logic failed to start, and so did the old one: none runs.</summary>
    Failed,
}

/// <summary>
/// The logic in use for one request or call: it isn't stopped or swapped out until every lease on
/// it is given back (or the drain time runs out).
/// </summary>
public sealed class LogicLease : IDisposable
{
    private Loaded? _loaded;

    internal LogicLease(Loaded loaded) => _loaded = loaded;

    /// <summary>Gets the logic.</summary>
    public ICompanionLogic Logic => (_loaded ?? throw new ObjectDisposedException(nameof(LogicLease))).Logic;

    /// <summary>Gets a token cancelled when this logic starts being swapped out.</summary>
    public CancellationToken Stopping => (_loaded ?? throw new ObjectDisposedException(nameof(LogicLease))).Stopping.Token;

    /// <inheritdoc />
    public void Dispose() => Interlocked.Exchange(ref _loaded, null)?.Leave();
}

/// <summary>A started logic and its context.</summary>
internal sealed class Loaded
{
    private readonly object _gate = new();
    private readonly TaskCompletionSource _drained = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int _inFlight;
    private bool _closing;

    public Loaded(Prepared prepared, ICompanionLogic logic, CancellationTokenSource stopping)
    {
        Prepared = prepared;
        Logic = logic;
        Stopping = stopping;
        LoadedAt = DateTimeOffset.UtcNow;
    }

    public Prepared Prepared { get; }

    public ICompanionLogic Logic { get; }

    public CancellationTokenSource Stopping { get; }

    public DateTimeOffset LoadedAt { get; }

    public Task Drained => _drained.Task;

    public bool TryEnter()
    {
        lock (_gate)
        {
            if (_closing)
            {
                return false;
            }

            _inFlight++;
            return true;
        }
    }

    public void Leave()
    {
        lock (_gate)
        {
            _inFlight--;
            if (_closing && _inFlight == 0)
            {
                _drained.TrySetResult();
            }
        }
    }

    public void Close()
    {
        lock (_gate)
        {
            _closing = true;
            if (_inFlight == 0)
            {
                _drained.TrySetResult();
            }
        }

        try
        {
            Stopping.Cancel();
        }
        catch (AggregateException)
        {
            // A callback of the logic's threw; it's stopping all the same.
        }
    }
}

/// <summary>A logic package loaded into its own context, its factory found; nothing started yet.</summary>
internal sealed class Prepared
{
    public Prepared(LogicPackage package, LogicLoadContext context, ICompanionLogicFactory factory)
    {
        Package = package;
        Context = context;
        Factory = factory;
    }

    public LogicPackage Package { get; }

    public LogicLoadContext Context { get; }

    public ICompanionLogicFactory Factory { get; }
}

/// <summary>
/// Loads the logic, starts it, swaps in newer ones without a restart (draining the old one and
/// handing its state over), rolls back when a new one fails, and unloads the old one. Every shell
/// adapter (the API controller, scheduled tasks, segment provider, request timing) reaches the
/// logic through a <see cref="LogicLease"/> from here. See docs/features/companion-hotswap.md.
/// </summary>
public sealed class LogicHost : IHostedService, IAsyncDisposable
{
    private static readonly IReadOnlyDictionary<string, byte[]> NoState = new Dictionary<string, byte[]>();

    private readonly IServiceProvider _services;
    private readonly ILoggerFactory _logs;
    private readonly ILogger _log;
    private readonly LogicStore _store;
    private readonly SemaphoreSlim _swapping = new(1, 1);
    private readonly TaskCompletionSource _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly List<WeakReference> _unloading = new();
    private Loaded? _current;
    private TaskCompletionSource? _held;
    private DateTimeOffset? _lastSwapAt;
    private string? _lastSwap;

    /// <summary>Initializes a new instance of the <see cref="LogicHost"/> class.</summary>
    public LogicHost(IServiceProvider services, ILoggerFactory logs, LogicStore store)
    {
        _services = services;
        _logs = logs;
        _log = logs.CreateLogger("Nitrate.Companion.Hosting");
        _store = store;
    }

    /// <summary>Gets or sets how long a swap waits for the old logic's requests to finish.</summary>
    public TimeSpan DrainTime { get; set; } = TimeSpan.FromSeconds(20);

    /// <summary>Gets or sets how long the old logic may take to stop.</summary>
    public TimeSpan StopTime { get; set; } = TimeSpan.FromSeconds(20);

    /// <summary>Gets the store the logic packages are kept in.</summary>
    public LogicStore Store => _store;

    /// <summary>Gets where the logic stands.</summary>
    public LogicStatus Status
    {
        get
        {
            var current = Volatile.Read(ref _current);
            return new LogicStatus(current?.Prepared.Package.Version, current?.Prepared.Package.Source, current?.LoadedAt, _lastSwapAt, _lastSwap);
        }
    }

    /// <summary>Gets the running logic's version, or null.</summary>
    public Version? RunningVersion => Volatile.Read(ref _current)?.Prepared.Package.ParsedVersion;

    /// <summary>Gets how many old logic contexts are still waiting to be unloaded.</summary>
    public int Unloading
    {
        get
        {
            lock (_unloading)
            {
                _unloading.RemoveAll(w => !w.IsAlive);
                return _unloading.Count;
            }
        }
    }

    /// <summary>
    /// The logic to use, waiting while a swap is under way (and for the first start); null when no
    /// logic could be started.
    /// </summary>
    public async ValueTask<LogicLease?> AcquireAsync(CancellationToken cancellationToken)
    {
        await _ready.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        while (true)
        {
            if (Volatile.Read(ref _held) is { } held)
            {
                await held.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
                continue;
            }

            var current = Volatile.Read(ref _current);
            if (current is null)
            {
                return null;
            }

            if (current.TryEnter())
            {
                return new LogicLease(current);
            }

            // Being swapped out: the hold is about to show.
            await Task.Delay(5, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <summary>The logic to use right now, or null while none runs or a swap is under way (never waits).</summary>
    public LogicLease? TryAcquire()
    {
        if (Volatile.Read(ref _held) is not null || Volatile.Read(ref _current) is not { } current || !current.TryEnter())
        {
            return null;
        }

        return new LogicLease(current);
    }

    /// <summary>Starts the best logic there is: the last good downloaded one or the built-in one, newest first.</summary>
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        try
        {
            foreach (var package in _store.Candidates(_log))
            {
                Prepared? prepared = null;
                try
                {
                    prepared = Prepare(package);
                    Volatile.Write(ref _current, await StartInstanceAsync(prepared, NoState, cancellationToken).ConfigureAwait(false));
                    _log.LogInformation("Nitrate Companion: logic {Version} ({Source}) started", package.Version, package.Source);
                    return;
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    _log.LogError(e, "Nitrate Companion: logic {Version} ({Source}) didn't start", package.Version, package.Source);
                    if (package.Source == LogicStore.Downloaded)
                    {
                        _store.MarkBad(package.Version);
                    }

                    if (prepared is not null)
                    {
                        Unload(prepared);
                    }
                }
            }

            _log.LogError("Nitrate Companion: no logic could be started; its API answers 503 until an update arrives");
        }
        finally
        {
            _ready.TrySetResult();
        }
    }

    /// <summary>Stops the logic as the server shuts down.</summary>
    public async Task StopAsync(CancellationToken cancellationToken)
    {
        await _swapping.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (Interlocked.Exchange(ref _current, null) is { } current)
            {
                await RetireAsync(current, export: false).ConfigureAwait(false);
            }
        }
        finally
        {
            _swapping.Release();
        }
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref _current, null) is { } current)
        {
            await RetireAsync(current, export: false).ConfigureAwait(false);
        }

        _swapping.Dispose();
    }

    /// <summary>
    /// Swaps <paramref name="package"/> in for the running logic: loads it, holds new requests,
    /// drains and stops the old one, hands its state over, starts the new one (rolling back to the
    /// old one if it fails), lets requests through and unloads the old context.
    /// </summary>
    public async Task<SwapOutcome> SwapAsync(LogicPackage package, CancellationToken cancellationToken)
    {
        await _ready.Task.WaitAsync(cancellationToken).ConfigureAwait(false);
        await _swapping.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var from = Volatile.Read(ref _current)?.Prepared.Package.Version;
            _lastSwapAt = DateTimeOffset.UtcNow;
            Prepared next;
            try
            {
                next = Prepare(package);
            }
            catch (Exception e)
            {
                _log.LogError(e, "Nitrate Companion: logic {Version} couldn't be loaded; {Running} keeps running", package.Version, from ?? "nothing");
                _store.MarkBad(package.Version);
                _lastSwap = $"Logic {package.Version} couldn't be loaded ({e.Message}); {(from is null ? "no logic runs" : from + " keeps running")}.";
                return SwapOutcome.Refused;
            }

            var held = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            Volatile.Write(ref _held, held);
            var old = Volatile.Read(ref _current);
            SwapOutcome outcome;
            try
            {
                var state = old is null ? NoState : await RetireAsync(old, export: true).ConfigureAwait(false);
                try
                {
                    Volatile.Write(ref _current, await StartInstanceAsync(next, state, CancellationToken.None).ConfigureAwait(false));
                    outcome = SwapOutcome.Swapped;
                    _lastSwap = from is null ? $"Started logic {package.Version}." : $"Swapped logic {from} for {package.Version} without a restart.";
                    _log.LogInformation("Nitrate Companion: logic {Old} swapped for {New} without a restart", from, package.Version);
                }
                catch (Exception e)
                {
                    _log.LogError(e, "Nitrate Companion: logic {Version} failed to start; going back to {Old}", package.Version, from);
                    _store.MarkBad(package.Version);
                    Unload(next);
                    Volatile.Write(ref _current, null);
                    outcome = SwapOutcome.Failed;
                    if (old is not null)
                    {
                        try
                        {
                            Volatile.Write(ref _current, await StartInstanceAsync(old.Prepared, state, CancellationToken.None).ConfigureAwait(false));
                            outcome = SwapOutcome.RolledBack;
                        }
                        catch (Exception again)
                        {
                            _log.LogError(again, "Nitrate Companion: logic {Old} didn't start again either", from);
                            Unload(old.Prepared);
                        }
                    }

                    _lastSwap = outcome == SwapOutcome.RolledBack
                        ? $"Logic {package.Version} failed to start ({e.Message}); went back to {from}."
                        : $"Logic {package.Version} failed to start ({e.Message}); no logic runs.";
                }
            }
            finally
            {
                Volatile.Write(ref _held, null);
                held.TrySetResult();
            }

            if (outcome == SwapOutcome.Swapped)
            {
                if (old is not null)
                {
                    Unload(old.Prepared);
                }

                if (package.Source == LogicStore.Downloaded)
                {
                    _store.Keep(package);
                }
            }

            return outcome;
        }
        finally
        {
            _swapping.Release();
        }
    }

    /// <summary>Collects until the old logic contexts are gone, or the time runs out; true when none is left.</summary>
    public async Task<bool> WaitForUnloadAsync(TimeSpan within) => await WaitForUnloadAsync(within, TimeSpan.FromMilliseconds(50)).ConfigureAwait(false);

    private async Task<bool> WaitForUnloadAsync(TimeSpan within, TimeSpan every)
    {
        var until = DateTime.UtcNow + within;
        while (true)
        {
            JsonCaches.Nudge();
            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            if (Unloading == 0)
            {
                return true;
            }

            if (DateTime.UtcNow > until)
            {
                return false;
            }

            await Task.Delay(every).ConfigureAwait(false);
        }
    }

    private Prepared Prepare(LogicPackage package)
    {
        if (!ShellContract.Supports(package.Manifest.Contract))
        {
            throw new LogicPackageException($"Logic {package.Version} is built for contract {package.Manifest.Contract}; this plugin takes {ShellContract.Oldest}–{ShellContract.Version}.");
        }

        var context = new LogicLoadContext("Nitrate.Companion.Logic " + package.Version, package.Assemblies);
        try
        {
            var assembly = context.LoadOwn(package.EntryName);
            var entry = assembly.GetCustomAttribute<CompanionLogicAttribute>()
                ?? throw new LogicPackageException($"{package.Manifest.Entry} has no [CompanionLogic].");
            if (!ShellContract.Supports(entry.Contract) || entry.Contract != package.Manifest.Contract)
            {
                throw new LogicPackageException($"{package.Manifest.Entry} is built for contract {entry.Contract}.");
            }

            var factory = Activator.CreateInstance(entry.Factory) as ICompanionLogicFactory
                ?? throw new LogicPackageException($"{entry.Factory} isn't a logic factory.");
            return new Prepared(package, context, factory);
        }
        catch
        {
            context.Unload();
            throw;
        }
    }

    private async Task<Loaded> StartInstanceAsync(Prepared prepared, IReadOnlyDictionary<string, byte[]> state, CancellationToken cancellationToken)
    {
        var stopping = new CancellationTokenSource();
        ICompanionLogic? logic = null;
        try
        {
            logic = prepared.Factory.Create(new LogicContext
            {
                HostServices = _services,
                LoggerFactory = _logs,
                Version = prepared.Package.Version,
                State = state,
                Stopping = stopping.Token,
            });
            await logic.StartAsync(cancellationToken).ConfigureAwait(false);
            return new Loaded(prepared, logic, stopping);
        }
        catch
        {
            await stopping.CancelAsync().ConfigureAwait(false);
            if (logic is not null)
            {
                try
                {
                    await logic.StopAsync(CancellationToken.None).ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    _log.LogWarning(e, "Nitrate Companion: a logic that failed to start also failed to stop");
                }

                try
                {
                    await logic.DisposeAsync().ConfigureAwait(false);
                }
                catch (Exception e)
                {
                    _log.LogWarning(e, "Nitrate Companion: a logic that failed to start also failed to close");
                }
            }

            stopping.Dispose();
            throw;
        }
    }

    // Closes the door, waits for what's running, stops, hands the state over and closes the logic.
    private async Task<IReadOnlyDictionary<string, byte[]>> RetireAsync(Loaded old, bool export)
    {
        old.Close();
        if (await Task.WhenAny(old.Drained, Task.Delay(DrainTime)).ConfigureAwait(false) != old.Drained)
        {
            _log.LogWarning("Nitrate Companion: logic {Version} still had requests after {Seconds} s; stopping it anyway", old.Prepared.Package.Version, DrainTime.TotalSeconds);
        }

        try
        {
            using var stopIn = new CancellationTokenSource(StopTime);
            await old.Logic.StopAsync(stopIn.Token).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            _log.LogWarning(e, "Nitrate Companion: logic {Version} didn't stop cleanly", old.Prepared.Package.Version);
        }

        var state = NoState;
        if (export)
        {
            try
            {
                state = old.Logic.ExportState();
            }
            catch (Exception e)
            {
                _log.LogWarning(e, "Nitrate Companion: logic {Version} couldn't hand its state over", old.Prepared.Package.Version);
            }
        }

        try
        {
            await old.Logic.DisposeAsync().ConfigureAwait(false);
        }
        catch (Exception e)
        {
            _log.LogWarning(e, "Nitrate Companion: logic {Version} didn't close cleanly", old.Prepared.Package.Version);
        }

        old.Stopping.Dispose();
        return state;
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private void Unload(Prepared prepared)
    {
        var context = prepared.Context;
        lock (_unloading)
        {
            _unloading.Add(new WeakReference(context, trackResurrection: false));
        }

        context.Unload();
        var version = prepared.Package.Version;
        _ = Task.Run(async () =>
        {
            if (!await WaitForUnloadAsync(TimeSpan.FromMinutes(2), TimeSpan.FromSeconds(5)).ConfigureAwait(false))
            {
                _log.LogWarning("Nitrate Companion: an older logic ({Version}) is still in memory two minutes after its swap", version);
            }
        });
    }
}

/// <summary>
/// System.Text.Json keeps the accessors it makes for each type's members in a process-wide cache,
/// whose stale entries (unused for a second) are dropped only when it makes new ones. An old logic's
/// types sit there after a swap, and keep its context from unloading, until then: this makes it
/// make some (for a type of the shell's, uncached), so they go.
/// </summary>
internal static class JsonCaches
{
    public static void Nudge()
    {
        try
        {
            new System.Text.Json.Serialization.Metadata.DefaultJsonTypeInfoResolver().GetTypeInfo(typeof(NudgeShape), new System.Text.Json.JsonSerializerOptions());
        }
        catch (Exception)
        {
            // Only ever a nudge.
        }
    }

    private sealed record NudgeShape(int Value);
}
