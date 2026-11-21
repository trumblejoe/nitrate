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

    /// <summary>Whether a newer copy of this plugin than the one running is installed (waiting for a restart).</summary>
    public static bool UpdateWaiting(IEnumerable<(Version Version, bool Running)> copies)
    {
        var list = copies.ToList();
        var running = list.Where(c => c.Running).Select(c => c.Version).DefaultIfEmpty(new Version(0, 0)).Max()!;
        return list.Any(c => !c.Running && c.Version > running);
    }
}

/// <summary>
/// Keeps the plugin up to date without anyone visiting the plugin page: every few hours it asks the
/// server's plugin repositories for a newer version of itself and installs it (as Jellyfin's own
/// "Update Plugins" task would, only sooner), unless auto-update is switched off for it. A new
/// version only runs after a restart, so (with <c>RestartForUpdates</c> switched on; off by default) the server is restarted
/// at night once nothing is playing.
/// </summary>
public sealed class SelfUpdater : IHostedService, IDisposable
{
    private readonly IInstallationManager _installs;
    private readonly IPluginManager _plugins;
    private readonly IApplicationHost _host;
    private readonly ISystemManager _system;
    private readonly ISessionManager _sessions;
    private readonly ILogger<SelfUpdater> _log;
    private readonly CancellationTokenSource _stop = new();

    /// <summary>Initializes a new instance of the <see cref="SelfUpdater"/> class.</summary>
    public SelfUpdater(IInstallationManager installs, IPluginManager plugins, IApplicationHost host, ISystemManager system, ISessionManager sessions, ILogger<SelfUpdater> log)
    {
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
    public void Dispose() => _stop.Dispose();

    private IEnumerable<LocalPlugin> Copies() => _plugins.Plugins.Where(p => p.Id == Plugin.PluginId);

    private async Task CheckLoop(CancellationToken stop)
    {
        try
        {
            await Task.Delay(UpdatePolicy.FirstCheckAfter, stop).ConfigureAwait(false);
            while (!stop.IsCancellationRequested)
            {
                await UpdateOnce(stop).ConfigureAwait(false);
                await Task.Delay(UpdatePolicy.CheckEvery, stop).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task UpdateOnce(CancellationToken stop)
    {
        try
        {
            // The admin can switch updates off for this plugin on its page, as for any plugin.
            if (Copies().FirstOrDefault(p => p.Instance is not null)?.Manifest.AutoUpdate == false)
            {
                return;
            }

            var updates = await _installs.GetAvailablePluginUpdates(stop).ConfigureAwait(false);
            foreach (var update in updates.Where(u => u.Id == Plugin.PluginId))
            {
                _log.LogInformation("Nitrate Companion: installing version {Version}; it runs after the server restarts", update.Version);
                await _installs.InstallPackage(update, stop).ConfigureAwait(false);
            }
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            _log.LogWarning(e, "Nitrate Companion: couldn't check for a newer version");
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
                var waiting = _host.HasPendingRestart && UpdatePolicy.UpdateWaiting(Copies().Select(p => (p.Version, p.Instance is not null)));
                var playing = _sessions.Sessions.Count(s => s.NowPlayingItem is not null);
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
