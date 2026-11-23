using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model.Tasks;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Logic;
using Nitrate.Companion.Probe;

[assembly: CompanionLogic(typeof(Nitrate.Companion.Tests.FakeLogic.FakeFactory), ShellContract.Version)]

namespace Nitrate.Companion.Tests.FakeLogic;

/// <summary>Makes the fake logic.</summary>
public sealed class FakeFactory : ICompanionLogicFactory
{
    /// <inheritdoc />
    public ICompanionLogic Create(LogicContext context) => new FakeLogic(context);
}

/// <summary>In-memory state, handed from one logic to the next, and a long poll.</summary>
public sealed class FakeHub
{
    private readonly object _lock = new();
    private TaskCompletionSource _changed = new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>Gets or sets the logic's version.</summary>
    public string Version { get; set; } = string.Empty;

    /// <summary>Gets or sets a counter that must survive swaps.</summary>
    public int Count { get; set; }

    /// <summary>Gets or sets requests seen by this logic.</summary>
    public int Requests { get; set; }

    /// <summary>Counts one up and wakes waiters.</summary>
    public int Bump()
    {
        TaskCompletionSource waiting;
        lock (_lock)
        {
            Count++;
            waiting = _changed;
            _changed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }

        waiting.TrySetResult();
        return Count;
    }

    /// <summary>Waits for a change, at most <paramref name="wait"/>; true when woken.</summary>
    public async Task<bool> WaitAsync(TimeSpan wait, CancellationToken cancellationToken)
    {
        Task changed;
        lock (_lock)
        {
            changed = _changed.Task;
        }

        try
        {
            await changed.WaitAsync(wait, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (TimeoutException)
        {
            return false;
        }
    }

    /// <summary>Wakes everyone waiting.</summary>
    public void WakeAll()
    {
        TaskCompletionSource waiting;
        lock (_lock)
        {
            waiting = _changed;
            _changed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }

        waiting.TrySetResult();
    }
}

/// <summary>The version and count.</summary>
public record FakeOut(string Version, int Count, bool Woken = false);

/// <summary>The fake logic's own routes.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Fake")]
[Produces("application/json")]
public sealed class FakeController : ControllerBase
{
    private readonly FakeHub _hub;
    private readonly ILogger<FakeController> _log;

    /// <summary>Initializes a new instance of the <see cref="FakeController"/> class.</summary>
    public FakeController(FakeHub hub, ILogger<FakeController> log)
    {
        _hub = hub;
        _log = log;
    }

    /// <summary>Which logic answers.</summary>
    [HttpGet("Version")]
    public ActionResult<FakeOut> Version()
    {
        _log.LogDebug("Version asked");
        return new FakeOut(_hub.Version, _hub.Count);
    }

    /// <summary>Counts.</summary>
    [HttpPost("Count")]
    public ActionResult<FakeOut> Count() => new FakeOut(_hub.Version, _hub.Bump());

    /// <summary>A long poll.</summary>
    [HttpGet("Wait")]
    public async Task<ActionResult<FakeOut>> Wait([FromQuery] int waitMs = 0)
    {
        var woken = await _hub.WaitAsync(TimeSpan.FromMilliseconds(waitMs), HttpContext.RequestAborted).ConfigureAwait(false);
        return new FakeOut(_hub.Version, _hub.Count, woken);
    }

    /// <summary>Holds a request for a while, regardless of swaps.</summary>
    [HttpGet("Slow")]
    public async Task<ActionResult<FakeOut>> Slow([FromQuery] int ms)
    {
        await Task.Delay(ms).ConfigureAwait(false);
        return new FakeOut(_hub.Version, _hub.Count);
    }
}

/// <summary>The probe, run by the shell.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Probe")]
[Produces("application/json")]
public sealed class ProbeController : ProbeActions
{
}

/// <summary>The bare probe, run by the shell.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Bare")]
public sealed class BareController : BareActions
{
}

/// <summary>A scheduled task that runs until cancelled, counting its runs.</summary>
public sealed class FakeTask : IScheduledTask
{
    private readonly FakeHub _hub;

    /// <summary>Initializes a new instance of the <see cref="FakeTask"/> class.</summary>
    public FakeTask(FakeHub hub) => _hub = hub;

    /// <inheritdoc />
    public string Name => "Fake " + _hub.Version;

    /// <inheritdoc />
    public string Key => "Fake";

    /// <inheritdoc />
    public string Description => "Fake";

    /// <inheritdoc />
    public string Category => "Fake";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        progress.Report(_hub.Version.EndsWith(".1", StringComparison.Ordinal) ? 1 : 2);
        if (_hub.Version.EndsWith(".1", StringComparison.Ordinal))
        {
            // The first logic's run is cut short by a swap; the next one finishes it.
            await Task.Delay(Timeout.Infinite, cancellationToken).ConfigureAwait(false);
        }
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => [];
}

/// <summary>The fake logic: a container, the router, a hub, a task. A version ending ".13" fails to start.</summary>
public sealed class FakeLogic : ICompanionLogic
{
    private static readonly JsonSerializerOptions Json = new() { TypeInfoResolver = new DefaultJsonTypeInfoResolver() };
    private readonly ServiceProvider _services;
    private readonly ApiRouter _router;
    private readonly FakeHub _hub;
    private readonly string _version;

    /// <summary>Initializes a new instance of the <see cref="FakeLogic"/> class.</summary>
    public FakeLogic(LogicContext context)
    {
        _version = context.Version;
        var services = new ServiceCollection();
        services.AddSingleton(context.LoggerFactory);
        services.AddSingleton(typeof(ILogger<>), typeof(Logger<>));
        services.AddSingleton(new FakeHub { Version = context.Version });
        services.AddSingleton<FakeTask>();
        _services = services.BuildServiceProvider();
        _hub = _services.GetRequiredService<FakeHub>();
        if (context.State.TryGetValue("fake.v1", out var state))
        {
            _hub.Count = JsonSerializer.Deserialize<int>(state, Json);
        }

        _router = new ApiRouter([typeof(FakeController), typeof(ProbeController), typeof(BareController)], typeof(FakeLogic).Assembly);
        Tasks = new Dictionary<string, IScheduledTask> { ["Nitrate.Companion.Credits.CreditScanner"] = _services.GetRequiredService<FakeTask>() };
        context.Stopping.Register(_hub.WakeAll);
    }

    /// <inheritdoc />
    public IReadOnlyDictionary<string, IScheduledTask> Tasks { get; }

    /// <inheritdoc />
    public IMediaSegmentProvider? Segments => null;

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken) =>
        _version.EndsWith(".13", StringComparison.Ordinal) ? throw new InvalidOperationException("Unlucky.") : Task.CompletedTask;

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <inheritdoc />
    public IReadOnlyDictionary<string, byte[]> ExportState() =>
        new Dictionary<string, byte[]> { ["fake.v1"] = JsonSerializer.SerializeToUtf8Bytes(_hub.Count, Json) };

    /// <inheritdoc />
    public RouteMatch Match(string method, string path) => _router.Match(method, path);

    /// <inheritdoc />
    public Task<IActionResult> InvokeAsync(RouteMatch match, ControllerContext context) => _router.InvokeAsync(match, context, _services);

    /// <inheritdoc />
    public void RequestStarting(HttpContext context) => _hub.Requests++;

    /// <inheritdoc />
    public void RequestFinished(HttpContext context, TimeSpan elapsed)
    {
    }

    /// <inheritdoc />
    public ValueTask DisposeAsync() => _services.DisposeAsync();
}
