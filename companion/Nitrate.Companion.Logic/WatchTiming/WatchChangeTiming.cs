using System.Diagnostics;
using System.Reflection;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.WatchTiming;

/// <summary>One listener's time for one save.</summary>
/// <param name="Name">Who it belongs to: the plugin's name (or Jellyfin's own), and the listener's class.</param>
/// <param name="Ms">How long it took.</param>
public record ListenerTime(string Name, double Ms);

/// <summary>One of the admin's watch-state saves while timing ran.</summary>
/// <param name="At">When, epoch ms.</param>
/// <param name="Title">What (an episode by its show and number only).</param>
/// <param name="Reason">Why Jellyfin saved it (TogglePlayed for Watched/Unwatched, PlaybackFinished…).</param>
/// <param name="ListenersMs">All listeners together.</param>
/// <param name="Listeners">Each listener, slowest first.</param>
public record TimedSave(long At, string Title, string Reason, double ListenersMs, IReadOnlyList<ListenerTime> Listeners);

/// <summary>One of the admin's Watched/Unwatched requests while timing ran, as the server answered it.</summary>
/// <param name="At">When, epoch ms.</param>
/// <param name="Watched">Watched (true) or Unwatched.</param>
/// <param name="Ms">The whole request on the server: looking it up, the database save, every listener, the answer.</param>
public record TimedPress(long At, bool Watched, double Ms);

/// <summary>A listener over the whole window.</summary>
/// <param name="Name">Whose.</param>
/// <param name="Saves">How many saves it ran for.</param>
/// <param name="MedianMs">Its middle time.</param>
/// <param name="MaxMs">Its slowest.</param>
public record ListenerSummary(string Name, int Saves, double MedianMs, double MaxMs);

/// <summary>Where a timing window stands.</summary>
/// <param name="Supported">Whether this Jellyfin's listeners can be timed (requests are timed regardless).</param>
/// <param name="Running">Timing now.</param>
/// <param name="EndsAt">When it stops, epoch ms (0: not running).</param>
/// <param name="Presses">The admin's Watched/Unwatched requests.</param>
/// <param name="Saves">The admin's saves.</param>
/// <param name="Listeners">Each listener over the window, slowest first.</param>
public record WatchTimingReport(bool Supported, bool Running, long EndsAt, IReadOnlyList<TimedPress> Presses, IReadOnlyList<TimedSave> Saves, IReadOnlyList<ListenerSummary> Listeners);

/// <summary>
/// Finds what makes Watched/Unwatched slow on a server. Jellyfin runs every plugin's watch-state
/// listener (<see cref="IUserDataManager.UserDataSaved"/>) inside the save, before it answers; for a
/// few minutes, started by an admin, each listener is timed on its own (by standing in for the event's
/// listeners and calling each in turn, as Jellyfin would), and the admin's Watched/Unwatched requests
/// are timed whole. Only saves and requests of the admin who started it are kept: nothing is done that
/// the server wasn't doing anyway, and the listeners are put back as they were when it ends (or when the
/// logic stops, which runs this first).
/// </summary>
public class WatchChangeTiming : IHostedService
{
    /// <summary>The longest a window runs.</summary>
    public static readonly TimeSpan MaxWindow = TimeSpan.FromMinutes(10);

    private const string EventField = "UserDataSaved";
    private const int MaxKept = 200;
    private readonly object _target;
    private readonly Func<Assembly, string?> _pluginName;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly List<TimedSave> _saves = new();
    private readonly List<TimedPress> _presses = new();
    private EventHandler<UserDataSaveEventArgs>[] _originals = Array.Empty<EventHandler<UserDataSaveEventArgs>>();
    private EventHandler<UserDataSaveEventArgs>? _wrapper;
    private ITimer? _timer;
    private Guid _admin;
    private DateTimeOffset _endsAt;

    /// <summary>Initializes a new instance of the <see cref="WatchChangeTiming"/> class over [target]'s UserDataSaved (the server's user data manager).</summary>
    public WatchChangeTiming(object target, Func<Assembly, string?> pluginName, TimeProvider time)
    {
        _target = target;
        _pluginName = pluginName;
        _time = time;
    }

    /// <summary>Whether the listeners can be timed on this server (the event is where it's expected).</summary>
    public bool Supported => Field() is not null;

    /// <summary>Whether a window is running.</summary>
    public bool Running
    {
        get
        {
            lock (_lock)
            {
                return _wrapper is not null;
            }
        }
    }

    /// <summary>Starts timing for [admin] for [window] (a new start clears the last results and runs again).</summary>
    public WatchTimingReport Start(Guid admin, TimeSpan window)
    {
        if (window <= TimeSpan.Zero || window > MaxWindow)
        {
            window = MaxWindow;
        }

        lock (_lock)
        {
            Restore();
            _saves.Clear();
            _presses.Clear();
            _admin = admin;
            _endsAt = _time.GetUtcNow() + window;
            if (Field() is { } field && field.GetValue(_target) is EventHandler<UserDataSaveEventArgs> current)
            {
                _originals = current.GetInvocationList().Cast<EventHandler<UserDataSaveEventArgs>>().ToArray();
                _wrapper = Timed;
                field.SetValue(_target, _wrapper);
            }
            else
            {
                // Nothing to time (no listeners, or a Jellyfin that keeps them elsewhere): requests only.
                _originals = Array.Empty<EventHandler<UserDataSaveEventArgs>>();
                _wrapper = (_, _) => { };
            }

            _timer?.Dispose();
            _timer = _time.CreateTimer(_ => Stop(), null, window, Timeout.InfiniteTimeSpan);
        }

        return Report(admin)!;
    }

    /// <summary>Stops timing and puts the listeners back (the results stay until the next start).</summary>
    public void Stop()
    {
        lock (_lock)
        {
            Restore();
        }
    }

    /// <summary>The last window's results, for the admin who ran it (null for anyone else).</summary>
    public WatchTimingReport? Report(Guid caller)
    {
        lock (_lock)
        {
            if (caller != _admin && _admin != Guid.Empty)
            {
                return null;
            }

            var listeners = _saves.SelectMany(s => s.Listeners).GroupBy(l => l.Name)
                .Select(g => new ListenerSummary(g.Key, g.Count(), Median(g.Select(l => l.Ms)), Math.Round(g.Max(l => l.Ms), 1)))
                .OrderByDescending(l => l.MedianMs).ToList();
            var running = _wrapper is not null;
            return new WatchTimingReport(Supported, running, running ? _endsAt.ToUnixTimeMilliseconds() : 0, _presses.ToList(), _saves.ToList(), listeners);
        }
    }

    /// <summary>
    /// A request was answered (from the server's request timing): the admin's Watched/Unwatched
    /// (POST or DELETE /UserPlayedItems/{id}, or the older /Users/{id}/PlayedItems/{id}) is kept.
    /// </summary>
    public void RequestFinished(Guid? user, string method, string path, TimeSpan elapsed)
    {
        if (user is not { } who || !IsWatchedPress(method, path))
        {
            return;
        }

        lock (_lock)
        {
            if (_wrapper is null || who != _admin)
            {
                return;
            }

            _presses.Add(new TimedPress(_time.GetUtcNow().ToUnixTimeMilliseconds(), method.Equals("POST", StringComparison.OrdinalIgnoreCase), Math.Round(elapsed.TotalMilliseconds, 1)));
            if (_presses.Count > MaxKept)
            {
                _presses.RemoveAt(0);
            }
        }
    }

    /// <summary>Whether [method] [path] is Jellyfin's Watched or Unwatched.</summary>
    public static bool IsWatchedPress(string method, string path)
    {
        if (!method.Equals("POST", StringComparison.OrdinalIgnoreCase) && !method.Equals("DELETE", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var parts = path.Trim('/').Split('/');
        return (parts.Length == 2 && parts[0].Equals("UserPlayedItems", StringComparison.OrdinalIgnoreCase))
            || (parts.Length == 4 && parts[0].Equals("Users", StringComparison.OrdinalIgnoreCase) && parts[2].Equals("PlayedItems", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>A listener's name: its plugin (or Jellyfin's own, or the Companion) and its class.</summary>
    public string NameOf(Delegate listener)
    {
        var type = listener.Method.DeclaringType;
        var assembly = type?.Assembly;
        var owner = assembly is null ? "Unknown"
            : _pluginName(assembly)
              ?? (assembly.GetName().Name is { } a && a.StartsWith("Nitrate.Companion", StringComparison.Ordinal) ? "Nitrate Companion"
              : assembly.GetName().Name is { } j && (j.StartsWith("Emby.", StringComparison.Ordinal) || j.StartsWith("Jellyfin.", StringComparison.Ordinal) || j.StartsWith("MediaBrowser.", StringComparison.Ordinal)) ? "Jellyfin"
              : assembly.GetName().Name ?? "Unknown");
        var cls = type?.Name ?? "?";
        // A lambda's class is compiler-made: name the class it was written in.
        if (cls.Contains('<', StringComparison.Ordinal) && type?.DeclaringType is { } outer)
        {
            cls = outer.Name;
        }

        // An async listener hands its work off at its first wait: only the part before it holds the save up.
        var later = listener.Method.GetCustomAttribute<System.Runtime.CompilerServices.AsyncStateMachineAttribute>() is not null ? ", then carries on in the background" : string.Empty;
        return $"{owner} ({cls}{later})";
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        Stop();
        return Task.CompletedTask;
    }

    private void Timed(object? sender, UserDataSaveEventArgs e)
    {
        EventHandler<UserDataSaveEventArgs>[] listeners;
        bool keep;
        lock (_lock)
        {
            listeners = _originals;
            keep = e.UserId == _admin;
        }

        var times = keep ? new List<ListenerTime>(listeners.Length) : null;
        var all = Stopwatch.StartNew();
        foreach (var listener in listeners)
        {
            var one = Stopwatch.StartNew();
            try
            {
                // As Jellyfin would: each in turn, and one that throws stops the rest (and the save says so).
                listener(sender, e);
            }
            finally
            {
                times?.Add(new ListenerTime(NameOf(listener), Math.Round(one.Elapsed.TotalMilliseconds, 1)));
            }
        }

        if (times is null)
        {
            return;
        }

        var title = e.Item switch
        {
            Episode ep => $"{ep.SeriesName} S{ep.ParentIndexNumber}:E{ep.IndexNumber}",
            null => "?",
            var i => i.Name,
        };
        lock (_lock)
        {
            _saves.Add(new TimedSave(_time.GetUtcNow().ToUnixTimeMilliseconds(), title, e.SaveReason.ToString(), Math.Round(all.Elapsed.TotalMilliseconds, 1), times.OrderByDescending(t => t.Ms).ToList()));
            if (_saves.Count > MaxKept)
            {
                _saves.RemoveAt(0);
            }
        }
    }

    /// <summary>The listeners as they were, plus any added meanwhile (call under the lock).</summary>
    private void Restore()
    {
        _timer?.Dispose();
        _timer = null;
        var wrapper = _wrapper;
        _wrapper = null;
        if (wrapper is null || Field() is not { } field)
        {
            return;
        }

        var current = field.GetValue(_target) as Delegate;
        var now = current?.GetInvocationList() ?? Array.Empty<Delegate>();
        if (!now.Contains(wrapper))
        {
            // Someone replaced them meanwhile: theirs stands.
            return;
        }

        var added = now.Where(d => d != (Delegate)wrapper);
        field.SetValue(_target, Delegate.Combine(_originals.Cast<Delegate>().Concat(added).ToArray()));
        _originals = Array.Empty<EventHandler<UserDataSaveEventArgs>>();
    }

    private FieldInfo? Field()
    {
        for (var t = _target.GetType(); t is not null; t = t.BaseType)
        {
            if (t.GetField(EventField, BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public) is { } f && f.FieldType == typeof(EventHandler<UserDataSaveEventArgs>))
            {
                return f;
            }
        }

        return null;
    }

    private static double Median(IEnumerable<double> values)
    {
        var v = values.Order().ToList();
        return v.Count == 0 ? 0 : Math.Round(v.Count % 2 == 1 ? v[v.Count / 2] : (v[(v.Count / 2) - 1] + v[v.Count / 2]) / 2, 1);
    }
}
