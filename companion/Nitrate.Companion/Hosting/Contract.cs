using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Hosting;

/// <summary>
/// The contract between the shell (this assembly, loaded by Jellyfin once) and the logic it loads
/// and swaps (Nitrate.Companion.Logic). See docs/features/companion-hotswap.md.
/// </summary>
public static class ShellContract
{
    /// <summary>
    /// The contract this shell offers. Bump it whenever anything a logic uses from the shell changes
    /// (these interfaces, <see cref="LogicContext"/>, the configuration, <see cref="Plugin"/>): a
    /// logic built for a newer number waits for a plugin update instead.
    /// </summary>
    public const int Version = 1;

    /// <summary>The oldest contract a logic may be built for and still run on this shell.</summary>
    public const int Oldest = 1;

    /// <summary>Whether a logic built for contract <paramref name="contract"/> can run on this shell.</summary>
    public static bool Supports(int contract) => contract >= Oldest && contract <= Version;
}

/// <summary>Names the logic assembly's factory and the contract it was built for.</summary>
[AttributeUsage(AttributeTargets.Assembly)]
public sealed class CompanionLogicAttribute : Attribute
{
    /// <summary>Initializes a new instance of the <see cref="CompanionLogicAttribute"/> class.</summary>
    public CompanionLogicAttribute(Type factory, int contract)
    {
        Factory = factory;
        Contract = contract;
    }

    /// <summary>Gets the type implementing <see cref="ICompanionLogicFactory"/> (with a parameterless constructor).</summary>
    public Type Factory { get; }

    /// <summary>Gets the contract version the logic was built for.</summary>
    public int Contract { get; }
}

/// <summary>Makes the logic; one factory per loaded logic assembly, asked again for a rollback.</summary>
public interface ICompanionLogicFactory
{
    /// <summary>Makes a logic instance (not started yet).</summary>
    ICompanionLogic Create(LogicContext context);
}

/// <summary>What the shell gives a new logic.</summary>
public sealed class LogicContext
{
    /// <summary>Gets the server's own services (Jellyfin's managers, paths, logging...).</summary>
    public required IServiceProvider HostServices { get; init; }

    /// <summary>Gets the server's logger factory.</summary>
    public required ILoggerFactory LoggerFactory { get; init; }

    /// <summary>Gets the logic package's version.</summary>
    public required string Version { get; init; }

    /// <summary>Gets what the previous logic handed over (versioned key → JSON); empty at a cold start.</summary>
    public required IReadOnlyDictionary<string, byte[]> State { get; init; }

    /// <summary>Gets a token cancelled as soon as the shell starts taking this logic out: waits end, runs stop.</summary>
    public required CancellationToken Stopping { get; init; }
}

/// <summary>A loaded logic: the plugin's features behind the shell's fixed registrations.</summary>
public interface ICompanionLogic : IAsyncDisposable
{
    /// <summary>Gets the scheduled task with this key (the shell adapter's type name), if the logic has it.</summary>
    IReadOnlyDictionary<string, IScheduledTask> Tasks { get; }

    /// <summary>Gets the media segment provider, if any.</summary>
    IMediaSegmentProvider? Segments { get; }

    /// <summary>Starts background work (what were hosted services).</summary>
    Task StartAsync(CancellationToken cancellationToken);

    /// <summary>Stops background work and removes event handlers; requests have drained.</summary>
    Task StopAsync(CancellationToken cancellationToken);

    /// <summary>What the next logic should keep from memory: versioned key → JSON.</summary>
    IReadOnlyDictionary<string, byte[]> ExportState();

    /// <summary>Which route a request is for, and its auth.</summary>
    RouteMatch Match(string method, string path);

    /// <summary>Runs a matched route's action (auth already checked by the shell).</summary>
    Task<IActionResult> InvokeAsync(RouteMatch match, ControllerContext context);

    /// <summary>Called as any request to the server arrives (quick; never throws).</summary>
    void RequestStarting(HttpContext context);

    /// <summary>Called once any request to the server is answered, with how long it took (quick; never throws).</summary>
    void RequestFinished(HttpContext context, TimeSpan elapsed);
}

/// <summary>What <see cref="ICompanionLogic.Match"/> found.</summary>
public enum RouteMatchKind
{
    /// <summary>A route and method match.</summary>
    Found,

    /// <summary>No route has this path.</summary>
    NotFound,

    /// <summary>A route has this path, but not for this method.</summary>
    MethodNotAllowed,
}

/// <summary>A matched route: whether and how it needs signing in, and the logic's own handle on it.</summary>
public sealed class RouteMatch
{
    /// <summary>No route has this path.</summary>
    public static readonly RouteMatch NotFound = new(RouteMatchKind.NotFound, true, [], null);

    /// <summary>The path is known, the method isn't.</summary>
    public static readonly RouteMatch MethodNotAllowed = new(RouteMatchKind.MethodNotAllowed, true, [], null);

    /// <summary>Initializes a new instance of the <see cref="RouteMatch"/> class.</summary>
    public RouteMatch(RouteMatchKind kind, bool allowAnonymous, IReadOnlyList<string?> policies, object? handle)
    {
        Kind = kind;
        AllowAnonymous = allowAnonymous;
        Policies = policies;
        Handle = handle;
    }

    /// <summary>Gets what was found.</summary>
    public RouteMatchKind Kind { get; }

    /// <summary>Gets a value indicating whether the route is open (<c>[AllowAnonymous]</c>).</summary>
    public bool AllowAnonymous { get; }

    /// <summary>Gets the route's <c>[Authorize]</c> attributes' policies (null: the default policy); empty and not anonymous: open too.</summary>
    public IReadOnlyList<string?> Policies { get; }

    /// <summary>Gets the logic's own data for <see cref="ICompanionLogic.InvokeAsync"/>.</summary>
    public object? Handle { get; }
}
