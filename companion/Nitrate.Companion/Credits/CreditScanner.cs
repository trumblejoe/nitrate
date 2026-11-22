using System.Diagnostics;
using System.Globalization;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaEncoding;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Credits;

/// <summary>
/// "Find scenes in the credits" (Dashboard → Scheduled tasks, nightly): reads the last part of
/// every film, and of episodes tagged as having a scene in the credits, as tiny greyscale frames
/// through the server's own ffmpeg (keyframes only, one frame every few seconds), and keeps what
/// <see cref="CreditFinder"/> finds. Titles are checked once (again when their file changes), so
/// the first run over a large library takes a while and later ones only look at new titles.
/// </summary>
public class CreditScanner : IScheduledTask
{
    /// <summary>The frames' size: the same grid the apps sample thumbnails into.</summary>
    public const int Width = 48;

    /// <summary>The frames' height.</summary>
    public const int Height = 27;

    /// <summary>One frame every this long.</summary>
    public const long IntervalMs = 5_000;

    /// <summary>Titles shorter than this have no credits worth reading.</summary>
    public const long MinRuntimeMs = 10 * 60_000;

    private static readonly string[] Stingers = { "aftercreditsstinger", "duringcreditsstinger" };
    private readonly CreditStore _store;
    private readonly ILibraryManager _library;
    private readonly IMediaEncoder _encoder;
    private readonly MediaBrowser.Controller.MediaSegments.IMediaSegmentManager _segments;
    private readonly ILogger<CreditScanner> _log;

    private readonly Nitrate.Companion.Scanning.ReadFailures _failures;

    /// <summary>Initializes a new instance of the <see cref="CreditScanner"/> class.</summary>
    public CreditScanner(CreditStore store, ILibraryManager library, IMediaEncoder encoder, MediaBrowser.Controller.MediaSegments.IMediaSegmentManager segments, ILogger<CreditScanner> log, Nitrate.Companion.Storage.CompanionDb db)
    {
        _failures = new Nitrate.Companion.Scanning.ReadFailures(db, "credits");
        _store = store;
        _library = library;
        _encoder = encoder;
        _segments = segments;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Find scenes in the credits";

    /// <inheritdoc />
    public string Key => "NitrateFindCreditScenes";

    /// <inheritdoc />
    public string Description => "Reads the end of films (and episodes tagged as having one) so Nitrate can skip to scenes during and after the credits.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(4).Ticks },
    };

    /// <summary>Whether [item] is worth reading: every film, and episodes tagged as having a scene.</summary>
    public static bool Wanted(BaseItem item) =>
        item is MediaBrowser.Controller.Entities.Movies.Movie ||
        item.Tags.Any(t => Stingers.Contains(t.Replace(" ", string.Empty, StringComparison.Ordinal).ToLowerInvariant()));

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        // One file-reading task at a time (FileReading.Gate).
        await Nitrate.Companion.Scanning.FileReading.Gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await RunAsync(progress, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            Nitrate.Companion.Scanning.FileReading.Gate.Release();
        }
    }

    private async Task RunAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var all = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
        });
        _store.KeepOnly(new HashSet<Guid>(all.Select(i => i.Id)));
        _failures.KeepOnly(new HashSet<Guid>(all.Select(i => i.Id)));
        var items = all.Where(Wanted).ToList();
        var done = 0;
        foreach (var item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress.Report(100.0 * done++ / Math.Max(1, items.Count));
            var version = item.Path + "|" + item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);
            if (!_store.Needs(item.Id, version) || !_failures.MayRead(item.Id, version))
            {
                continue;
            }

            try
            {
                var before = _store.Get(item.Id)?.OutroStartMs;
                var result = await FindAsync(item, version, cancellationToken).ConfigureAwait(false);
                _store.Put(item.Id, result);
                _failures.Succeeded(item.Id);
                if (result.OutroStartMs != null && result.OutroStartMs != before)
                {
                    // The final credits go to Jellyfin as an Outro segment (Segments/CompanionSegmentProvider).
                    await _segments.RunSegmentPluginProviders(item, _library.GetLibraryOptions(item), true, cancellationToken).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception e)
            {
                // One unreadable file never stops the rest; it waits a day, a week, then a month before another try.
                _failures.Failed(item.Id, version);
                _log.LogWarning("Credit scenes: couldn't read {Name}: {Message}", item.Name, e.Message);
            }
        }

        progress.Report(100);
    }

    private async Task<CreditResult> FindAsync(BaseItem item, string version, CancellationToken cancellationToken)
    {
        var runtimeMs = (item.RunTimeTicks ?? 0) / TimeSpan.TicksPerMillisecond;
        if (runtimeMs < MinRuntimeMs || string.IsNullOrEmpty(item.Path) || !File.Exists(item.Path))
        {
            return new CreditResult(version, Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow, Format: CreditResult.CurrentFormat);
        }

        var startMs = (long)(runtimeMs * CreditFinder.CreditsFrom);
        var frames = await SampleAsync(item.Path, startMs, runtimeMs, cancellationToken).ConfigureAwait(false);
        var outro = CreditFinder.FinalCredits(runtimeMs, startMs, frames, IntervalMs);
        return new CreditResult(version, CreditFinder.Find(runtimeMs, startMs, frames, IntervalMs), DateTimeOffset.UtcNow, outro, outro == null ? null : runtimeMs, CreditResult.CurrentFormat);
    }

    /// <summary>
    /// The file's frames from [startMs] to [runtimeMs], one every <see cref="IntervalMs"/>: only its
    /// keyframes are decoded (cheap even for 4K), each held until the next, as tiny greyscale grids.
    /// </summary>
    private async Task<IReadOnlyList<byte[]>> SampleAsync(string path, long startMs, long runtimeMs, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(_encoder.EncoderPath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in new[]
        {
            "-hide_banner", "-nostdin",
            "-skip_frame", "nokey",
            "-ss", (startMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture),
            "-i", path,
            "-an", "-sn", "-dn",
            // showinfo reports each frame's time (from the seek point) on stderr.
            "-vf", $"scale={Width}:{Height},format=gray,showinfo",
            "-fps_mode", "passthrough",
            "-f", "rawvideo", "-pix_fmt", "gray", "-",
        })
        {
            info.ArgumentList.Add(arg);
        }

        using var process = Process.Start(info) ?? throw new InvalidOperationException("ffmpeg didn't start");
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
        using var output = new MemoryStream();
        await process.StandardOutput.BaseStream.CopyToAsync(output, cancellationToken).ConfigureAwait(false);
        await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        var log = await errors.ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException("ffmpeg: " + log.Trim().Split('\n').LastOrDefault());
        }

        var size = Width * Height;
        var bytes = output.ToArray();
        var times = Times(log);
        var keyframes = Enumerable.Range(0, Math.Min(bytes.Length / size, times.Count))
            .Select(i => (AtMs: startMs + times[i], Frame: bytes.AsSpan(i * size, size).ToArray()))
            .ToList();
        return Resample(keyframes, startMs, runtimeMs, IntervalMs);
    }

    /// <summary>Each frame's time in ms, in order, from ffmpeg's showinfo lines ("pts_time:12.5").</summary>
    public static IReadOnlyList<long> Times(string log) =>
        System.Text.RegularExpressions.Regex.Matches(log, @"pts_time:(-?[0-9.]+)")
            .Select(m => (long)(double.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture) * 1000))
            .ToList();

    /// <summary>
    /// Keyframes at their own times onto a steady grid (one every [intervalMs] from [startMs] to
    /// [runtimeMs]), each grid point showing the last keyframe at or before it.
    /// </summary>
    public static IReadOnlyList<byte[]> Resample(IReadOnlyList<(long AtMs, byte[] Frame)> keyframes, long startMs, long runtimeMs, long intervalMs)
    {
        if (keyframes.Count == 0)
        {
            return Array.Empty<byte[]>();
        }

        var sorted = keyframes.OrderBy(k => k.AtMs).ToList();
        var grid = new List<byte[]>();
        var k = 0;
        for (var t = startMs; t < runtimeMs; t += intervalMs)
        {
            while (k + 1 < sorted.Count && sorted[k + 1].AtMs <= t)
            {
                k++;
            }

            grid.Add(sorted[k].Frame);
        }

        return grid;
    }
}
