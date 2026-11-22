using System.Diagnostics;
using System.Globalization;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaEncoding;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Timing;

/// <summary>
/// "Check files' timing" (Dashboard → Scheduled tasks, nightly): asks the server's own ffprobe
/// about the start of every film and episode (its streams' start times and the packets of the
/// first five minutes, no decoding) and keeps what <see cref="TimingFinder"/> finds, for Library
/// health. Each file is checked once (again when it changes), so later runs only look at new ones.
/// </summary>
public class TimingScanner : IScheduledTask
{
    private readonly TimingStore _store;
    private readonly ILibraryManager _library;
    private readonly IMediaEncoder _encoder;
    private readonly ILogger<TimingScanner> _log;

    /// <summary>Initializes a new instance of the <see cref="TimingScanner"/> class.</summary>
    public TimingScanner(TimingStore store, ILibraryManager library, IMediaEncoder encoder, ILogger<TimingScanner> log)
    {
        _store = store;
        _library = library;
        _encoder = encoder;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Check files' timing";

    /// <inheritdoc />
    public string Key => "NitrateCheckTiming";

    /// <inheritdoc />
    public string Description => "Looks at how each film and episode was made for things that put sound and picture out of step (an offset at the start, gaps, faulty timestamps), listed in Nitrate's Library health.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(4.5).Ticks },
    };

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var items = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
        });
        _store.KeepOnly(new HashSet<Guid>(items.Select(i => i.Id)));
        var done = 0;
        foreach (var item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress.Report(100.0 * done++ / Math.Max(1, items.Count));
            if (string.IsNullOrEmpty(item.Path) || !File.Exists(item.Path))
            {
                continue;
            }

            var version = item.Path + "|" + item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);
            if (!_store.Needs(item.Id, version))
            {
                continue;
            }

            try
            {
                var (streams, packets) = TimingFinder.Parse(await ProbeAsync(item.Path, cancellationToken).ConfigureAwait(false));
                _store.Put(item.Id, new TimingResult(version, TimingFinder.Find(streams, packets), DateTimeOffset.UtcNow));
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception e)
            {
                // One unreadable file never stops the rest (it's tried again next night).
                _log.LogWarning("Timing check: couldn't read {Name}: {Message}", item.Name, e.Message);
            }
        }

        progress.Report(100);
    }

    private async Task<string> ProbeAsync(string path, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(_encoder.ProbePath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in new[]
        {
            "-v", "error",
            "-print_format", "json",
            "-read_intervals", "%+" + (TimingFinder.ReadMs / 1000).ToString(CultureInfo.InvariantCulture),
            "-show_entries", "stream=index,codec_type,start_time,avg_frame_rate,r_frame_rate:packet=stream_index,pts_time,dts_time,duration_time",
            path,
        })
        {
            info.ArgumentList.Add(arg);
        }

        using var process = Process.Start(info) ?? throw new InvalidOperationException("ffprobe didn't start");
        await using var registration = cancellationToken.Register(() =>
        {
            try
            {
                process.Kill(true);
            }
            catch (InvalidOperationException)
            {
            }
        });
        var errors = process.StandardError.ReadToEndAsync(cancellationToken);
        var output = await process.StandardOutput.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
        await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException("ffprobe: " + (await errors.ConfigureAwait(false)).Trim().Split('\n').LastOrDefault());
        }

        return output;
    }
}
