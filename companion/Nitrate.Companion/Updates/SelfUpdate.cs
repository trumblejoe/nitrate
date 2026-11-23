using MediaBrowser.Common;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Common.Updates;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Session;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Updates;

/// <summary>When the plugin updates itself and when a pending update is finished by a restart.</summary>
public static class UpdatePolicy
{
    /// <summary>How often to look for a newer version in the plugin repository.</summary>
    public static readonly TimeSpan CheckEvery = TimeSpan.FromHours(6);

    /// <summary>How long after the server starts before the first look.</summary>
    public static readonly TimeSpan FirstCheckAfter = TimeSpan.FromMinutes(10);

    /// <summary>How often to see whether now is a good time to restart.</summary>
    public static readonly TimeSpan RestartCheckEvery = TimeSpan.FromMinutes(10);

    /// <summary>The window (from the chosen hour, server time) in which a restart may happen.</summary>
    public static readonly TimeSpan Window = TimeSpan.FromHours(2);

    /// <summary>
    /// Whether to restart now: switched on, a newer version of this plugin is installed and waiting
    /// for a restart, it's within <see cref="Window"/> of <paramref name="hour"/> in the server's
    /// time, and nobody is playing anything.
    /// </summary>
    public static bool ShouldRestart(bool enabled, bool updateWaiting, DateTime localNow, int hour, int playingNow)
    {
        if (!enabled || !updateWaiting || playingNow > 0)
        {
            return false;
        }

        var start = localNow.Date.AddHours(Math.Clamp(hour, 0, 23));
        if (localNow < start)
        {
            start = start.AddDays(-1);
        }

        return localNow - start < Window;
    }

    /// <summary>The newest installed copy newer than the one running (waiting for a restart), if any.</summary>
    public static Version? WaitingVersion(IEnumerable<(Version Version, bool Running)> copies)
    {
        var list = copies.ToList();
        var running = list.Where(c => c.Running).Select(c => c.Version).DefaultIfEmpty(new Version(0, 0)).Max()!;
        return list.Where(c => !c.Running && c.Version > running).Select(c => c.Version).DefaultIfEmpty().Max();
    }

    /// <summary>
    /// Whether an admin's "Restart now" may go ahead: only to finish a waiting update, and never while
    /// something is playing (it would stop it for everyone).
    /// </summary>
    public static RestartAnswer CanRestartNow(bool updateWaiting, int playingNow) =>
        !updateWaiting ? RestartAnswer.NothingWaiting : playingNow > 0 ? RestartAnswer.SomethingPlaying : RestartAnswer.Restarting;

    /// <summary>Whether a newer copy of this plugin than the one running is installed (waiting for a restart).</summary>
    public static bool UpdateWaiting(IEnumerable<(Version Version, bool Running)> copies)
    {
        var list = copies.ToList();
        var running = list.Where(c => c.Running).Select(c => c.Version).DefaultIfEmpty(new Version(0, 0)).Max()!;
        return list.Any(c => !c.Running && c.Version > running);
    }
}

/// <summary>What an admin's "Restart now" did.</summary>
public enum RestartAnswer
{
    /// <summary>The server is restarting to finish the update.</summary>
    Restarting,

    /// <summary>No newer version is waiting: nothing to finish.</summary>
    NothingWaiting,

    /// <summary>Someone is watching or listening; restarting would stop them.</summary>
    SomethingPlaying,
}

/// <summary>Where the plugin's updates stand, for its dashboard page and admins in the apps.</summary>
/// <param name="Running">The version running now.</param>
/// <param name="Waiting">A newer version installed and waiting for a restart, if any.</param>
/// <param name="RestartNeeded">Whether the server needs a restart to run <paramref name="Waiting"/>.</param>
/// <param name="Playing">How many sessions are playing something now (a restart would stop them).</param>
/// <param name="LastCheckedAt">When the repositories were last asked, if they have been since the start.</param>
/// <param name="LastCheck">What that look found, in words.</param>
/// <param name="AutoUpdate">Whether the plugin installs its new versions by itself.</param>
public record UpdateStatus(string Running, string? Waiting, bool RestartNeeded, int Playing, DateTimeOffset? LastCheckedAt, string? LastCheck, bool AutoUpdate)
{
    /// <summary>Gets the shell's version (the plugin Jellyfin runs; same as <see cref="Running"/>).</summary>
    public string? Shell { get; init; }

    /// <summary>Gets the running logic's version (swapped without a restart), or null when none runs.</summary>
    public string? Logic { get; init; }

    /// <summary>Gets where the logic came from: "built-in" (with the plugin) or "downloaded".</summary>
    public string? LogicSource { get; init; }

    /// <summary>Gets when the running logic started.</summary>
    public DateTimeOffset? LogicLoadedAt { get; init; }

    /// <summary>Gets when a logic swap was last tried.</summary>
    public DateTimeOffset? LastSwapAt { get; init; }

    /// <summary>Gets what that swap did, in words.</summary>
    public string? LastSwap { get; init; }

    /// <summary>Gets what kind of update waits: "restart" (a plugin update, finished by a restart), or null.</summary>
    public string? WaitingKind { get; init; }

    /// <summary>Gets a newer logic version that needs a newer plugin (so a restart), if any.</summary>
    public string? LogicNeedsPlugin { get; init; }

    /// <summary>Gets a value indicating whether this build takes logic updates (signed, from the release).</summary>
    public bool LogicUpdates { get; init; }
}

/// <summary>
/// Keeps the plugin up to date without anyone visiting the plugin page: every few hours it asks the
/// server's plugin repositories for a newer version of itself and installs it (as Jellyfin's own
/// "Update Plugins" task would, only sooner), unless auto-update is switched off for it. A new
/// version only runs after a restart, so (with <c>RestartForUpdates</c> switched on; off by default) the server is restarted
/// at night once nothing is playing. The same looks also fetch newer logic (<see cref="LogicUpdater"/>),
/// which runs at once, with no restart.
/// </summary>
public sealed class SelfUpdater : IHostedService, IDisposable
{
    private readonly IInstallationManager _installs;
    private readonly IPluginManager _plugins;
    private readonly IApplicationHost _host;
    private readonly ISystemManager _system;
    private readonly ISessionManager _sessions;
    private readonly ILogger<SelfUpdater> _log;
    private readonly Hosting.LogicHost _logicHost;
    private readonly LogicUpdater _logic;
    private readonly CancellationTokenSource _stop = new();

    /// <summary>Initializes a new instance of the <see cref="SelfUpdater"/> class.</summary>
    public SelfUpdater(IInstallationManager installs, IPluginManager plugins, IApplicationHost host, ISystemManager system, ISessionManager sessions, ILogger<SelfUpdater> log, Hosting.LogicHost logicHost, LogicUpdater logic)
    {
        _logicHost = logicHost;
        _logic = logic;
        _installs = installs;
        _plugins = plugins;
        _host = host;
        _system = system;
        _sessions = sessions;
        _log = log;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _ = Task.Run(() => CheckLoop(_stop.Token), CancellationToken.None);
        _ = Task.Run(() => RestartLoop(_stop.Token), CancellationToken.None);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _stop.Cancel();
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _stop.Dispose();
        _checking.Dispose();
    }

    private readonly SemaphoreSlim _checking = new(1, 1);
    private DateTimeOffset? _lastCheckedAt;
    private string? _lastCheck;

    private IEnumerable<LocalPlugin> Copies() => _plugins.Plugins.Where(p => p.Id == Plugin.PluginId);

    private Version? Waiting() => UpdatePolicy.WaitingVersion(Copies().Select(p => (p.Version, p.Instance is not null)));

    private int PlayingNow() => _sessions.Sessions.Count(s => s.NowPlayingItem is not null);

    /// <summary>Where updates stand now.</summary>
    public UpdateStatus Status()
    {
        var running = Copies().FirstOrDefault(p => p.Instance is not null);
        var waiting = Waiting();
        var restartNeeded = waiting is not null && _host.HasPendingRestart;
        var shell = running?.Version.ToString() ?? typeof(SelfUpdater).Assembly.GetName().Version?.ToString() ?? "?";
        var logic = _logicHost.Status;
        return new UpdateStatus(
            shell,
            waiting?.ToString(),
            restartNeeded,
            PlayingNow(),
            _lastCheckedAt,
            _lastCheck,
            running?.Manifest.AutoUpdate != false)
        {
            Shell = shell,
            Logic = logic.Version,
            LogicSource = logic.Source,
            LogicLoadedAt = logic.LoadedAt,
            LastSwapAt = logic.LastSwapAt,
            LastSwap = logic.LastSwap,
            WaitingKind = restartNeeded ? "restart" : null,
            LogicNeedsPlugin = _logic.NeedsPlugin,
            LogicUpdates = _logic.Enabled,
        };
    }

    /// <summary>
    /// Asks the repositories now (the admin pressed "Check for updates now") and installs a newer
    /// version at once, even with auto-update off: the admin asked for it.
    /// </summary>
    public async Task<UpdateStatus> CheckNow(CancellationToken stop)
    {
        await UpdateOnce(stop, asked: true).ConfigureAwait(false);
        return Status();
    }

    /// <summary>Restarts the server to finish a waiting update, unless there's none or something is playing.</summary>
    public RestartAnswer RestartNow()
    {
        var answer = UpdatePolicy.CanRestartNow(Waiting() is not null && _host.HasPendingRestart, PlayingNow());
        if (answer == RestartAnswer.Restarting)
        {
            _log.LogInformation("Nitrate Companion: an admin restarted the server to finish the update");
            _system.Restart();
        }

        return answer;
    }

    private async Task CheckLoop(CancellationToken stop)
    {
        try
        {
            await Task.Delay(UpdatePolicy.FirstCheckAfter, stop).ConfigureAwait(false);
            while (!stop.IsCancellationRequested)
            {
                await UpdateOnce(stop, asked: false).ConfigureAwait(false);
                await Task.Delay(UpdatePolicy.CheckEvery, stop).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task UpdateOnce(CancellationToken stop, bool asked)
    {
        await _checking.WaitAsync(stop).ConfigureAwait(false);
        try
        {
            // The admin can switch updates off for this plugin on its page, as for any plugin; asking
            // for a check on the dashboard still installs.
            if (!asked && Copies().FirstOrDefault(p => p.Instance is not null)?.Manifest.AutoUpdate == false)
            {
                return;
            }

            // The logic first: it runs at once, with no restart.
            string? logicCheck = null;
            try
            {
                logicCheck = _logic.Enabled ? await _logic.CheckAsync(stop).ConfigureAwait(false) : null;
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                logicCheck = "Couldn't look for newer logic: " + e.Message;
                _log.LogWarning(e, "Nitrate Companion: couldn't check for newer logic");
            }

            var updates = (await _installs.GetAvailablePluginUpdates(stop).ConfigureAwait(false)).Where(u => u.Id == Plugin.PluginId).ToList();
            _lastCheckedAt = DateTimeOffset.UtcNow;
            _lastCheck = updates.Count == 0
                ? (Waiting() is { } w ? $"Version {w} is installed and starts after a restart." : "Up to date.")
                : null;
            foreach (var update in updates)
            {
                _log.LogInformation("Nitrate Companion: installing version {Version}; it runs after the server restarts", update.Version);
                await _installs.InstallPackage(update, stop).ConfigureAwait(false);
                _lastCheck = $"Installed version {update.Version}; it starts after a restart.";
            }

            if (logicCheck is not null)
            {
                _lastCheck = $"{_lastCheck} {logicCheck}".Trim();
            }
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            _lastCheckedAt = DateTimeOffset.UtcNow;
            _lastCheck = "Couldn't reach the plugin repository: " + e.Message;
            _log.LogWarning(e, "Nitrate Companion: couldn't check for a newer version");
        }
        finally
        {
            _checking.Release();
        }
    }

    private async Task RestartLoop(CancellationToken stop)
    {
        try
        {
            while (!stop.IsCancellationRequested)
            {
                await Task.Delay(UpdatePolicy.RestartCheckEvery, stop).ConfigureAwait(false);
                var config = Plugin.Instance?.Configuration;
                var waiting = _host.HasPendingRestart && Waiting() is not null;
                var playing = PlayingNow();
                if (UpdatePolicy.ShouldRestart(config?.RestartForUpdates ?? false, waiting, DateTime.Now, config?.RestartHour ?? 4, playing))
                {
                    _log.LogInformation("Nitrate Companion: restarting the server to finish its update (nothing is playing)");
                    _system.Restart();
                    return;
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }
}
