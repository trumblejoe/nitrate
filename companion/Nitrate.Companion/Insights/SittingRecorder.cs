using System.Collections.Concurrent;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Insights;

/// <summary>
/// Real watch time, in any app: follows the server's own playback reports for films, episodes and
/// other videos, counts the time played (not paused, gaps in the reports capped), and keeps one
/// <see cref="Sitting"/> per person when playback stops, if a minute or more was watched.
/// </summary>
public class SittingRecorder : IHostedService
{
    /// <summary>Shorter sittings aren't kept (a peek, a wrong title).</summary>
    public static readonly TimeSpan MinWatched = TimeSpan.FromMinutes(1);

    private readonly ISessionManager _sessions;
    private readonly PeopleRecords _records;
    private readonly ConcurrentDictionary<string, SittingClock> _open = new();

    /// <summary>Initializes a new instance of the <see cref="SittingRecorder"/> class.</summary>
    public SittingRecorder(ISessionManager sessions, PeopleRecords records)
    {
        _sessions = sessions;
        _records = records;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart += OnProgress;
        _sessions.PlaybackProgress += OnProgress;
        _sessions.PlaybackStopped += OnStopped;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart -= OnProgress;
        _sessions.PlaybackProgress -= OnProgress;
        _sessions.PlaybackStopped -= OnStopped;
        return Task.CompletedTask;
    }

    private static bool Counted(BaseItem? item) => item is Video;

    private static string Key(PlaybackProgressEventArgs e) => (e.PlaySessionId ?? e.DeviceId ?? string.Empty) + "|" + e.Item?.Id.ToString("N");

    private void OnProgress(object? sender, PlaybackProgressEventArgs e)
    {
        if (!Counted(e.Item))
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        _open.GetOrAdd(Key(e), _ => new SittingClock(now)).Tick(now, e.IsPaused);
    }

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        if (!Counted(e.Item) || !_open.TryRemove(Key(e), out var clock))
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        clock.Tick(now, true);
        if (clock.Watched < MinWatched)
        {
            return;
        }

        var item = e.Item!;
        var sitting = new Sitting(
            item.Id,
            item.GetType().Name,
            item.Name,
            (item as Episode)?.SeriesName,
            clock.Started.ToUnixTimeMilliseconds(),
            now.ToUnixTimeMilliseconds(),
            (long)clock.Watched.TotalMilliseconds);
        foreach (var user in e.Users ?? new())
        {
            _records.AddSitting(user.Id, sitting);
        }
    }
}
