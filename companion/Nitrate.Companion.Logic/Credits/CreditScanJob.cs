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
/// every film, and of episodes tagged as having a scene in the credits, through the server's own
/// ffmpeg as 320×180 frames with colour, one a second, measuring each frame as it arrives
/// (<see cref="CreditFeatureReader"/>: only the features are kept, never the pictures), and keeps
/// what <see cref="CreditFinder"/> decides, with the title's subtitle lines (the spoken-line index)
/// voting when it has them. Titles are checked once (again when their file changes), one at a time.
/// </summary>
public class CreditScanJob : IScheduledTask
{
    /// <summary>The frames' width.</summary>
    public const int Width = 320;

    /// <summary>The frames' height.</summary>
    public const int Height = 180;

    /// <summary>One frame every this long.</summary>
    public const long IntervalMs = 1_000;

    /// <summary>Titles shorter than this have no credits worth reading.</summary>
    public const long MinRuntimeMs = 10 * 60_000;

    private static readonly string[] Stingers = { "aftercreditsstinger", "duringcreditsstinger" };
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, bool> Reading = new();
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, (DateTimeOffset At, string Why)> Failed = new();
    private readonly CreditStore _store;
    private readonly ILibraryManager _library;
    private readonly IMediaEncoder _encoder;
    private readonly MediaBrowser.Controller.MediaSegments.IMediaSegmentManager _segments;
    private readonly Nitrate.Companion.Lines.LineIndex _lines;
    private readonly ILogger<CreditScanJob> _log;

    private readonly Nitrate.Companion.Scanning.ReadFailures _failures;

    /// <summary>Initializes a new instance of the <see cref="CreditScanJob"/> class.</summary>
    public CreditScanJob(CreditStore store, ILibraryManager library, IMediaEncoder encoder, MediaBrowser.Controller.MediaSegments.IMediaSegmentManager segments, Nitrate.Companion.Lines.LineIndex lines, ILogger<CreditScanJob> log, Nitrate.Companion.Storage.CompanionDb db)
    {
        _failures = new Nitrate.Companion.Scanning.ReadFailures(db, "credits");
        _store = store;
        _library = library;
        _encoder = encoder;
        _segments = segments;
        _lines = lines;
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

    /// <summary>The file version a result is kept against.</summary>
    public static string VersionOf(BaseItem item) => item.Path + "|" + item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);

    /// <summary>Whether [itemId] is being read for a report right now.</summary>
    public static bool IsReading(Guid itemId) => Reading.ContainsKey(itemId);

    /// <summary>Why a read for a report failed in the last 10 minutes (not tried again meanwhile), if it did.</summary>
    public static string? RecentFailure(Guid itemId) =>
        Failed.TryGetValue(itemId, out var f) && DateTimeOffset.UtcNow - f.At < TimeSpan.FromMinutes(10) ? f.Why : null;

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

    /// <summary>
    /// Reads one title now, in the background (an admin's Credits check when its features aren't
    /// kept yet), after whatever file reading is running; false when it's already being read.
    /// </summary>
    public bool ReadSoon(BaseItem item)
    {
        if (!Reading.TryAdd(item.Id, true))
        {
            return false;
        }

        _ = Task.Run(async () =>
        {
            await Nitrate.Companion.Scanning.FileReading.Gate.WaitAsync().ConfigureAwait(false);
            try
            {
                await ReadOneAsync(item, VersionOf(item), CancellationToken.None).ConfigureAwait(false);
                Failed.TryRemove(item.Id, out _);
            }
            catch (Exception e)
            {
                Failed[item.Id] = (DateTimeOffset.UtcNow, e.Message);
                _log.LogWarning("Credit scenes: couldn't read {Name}: {Message}", item.Name, e.Message);
            }
            finally
            {
                Nitrate.Companion.Scanning.FileReading.Gate.Release();
                Reading.TryRemove(item.Id, out _);
            }
        });
        return true;
    }

    /// <summary>[item]'s subtitle lines from [fromMs] as the dialogue vote takes them; null when none are indexed.</summary>
    public IReadOnlyList<DialogueLine>? Dialogue(Guid item, long fromMs, long toMs)
    {
        if (!(Plugin.Instance?.Configuration.EnableLines ?? true) || !_lines.Indexed(item))
        {
            return null;
        }

        return _lines.Cues(item, fromMs, toMs)
            .Select(c => new DialogueLine(c.Cue.StartMs, c.Cue.EndMs, c.Cue.Marks.HasFlag(Nitrate.Companion.Lines.CueMarks.Sung), c.Cue.Marks.HasFlag(Nitrate.Companion.Lines.CueMarks.Italic)))
            .ToList();
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
            var version = VersionOf(item);
            if (!_store.Needs(item.Id, version) || !_failures.MayRead(item.Id, version))
            {
                continue;
            }

            try
            {
                await ReadOneAsync(item, version, cancellationToken).ConfigureAwait(false);
                _failures.Succeeded(item.Id);
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

    private async Task ReadOneAsync(BaseItem item, string version, CancellationToken cancellationToken)
    {
        var before = _store.Get(item.Id)?.OutroStartMs;
        var result = await FindAsync(item, version, cancellationToken).ConfigureAwait(false);
        // Rejected final credits stay rejected until the file itself changes.
        if (_store.Get(item.Id) is { OutroRejected: true } old && old.Version == version)
        {
            result = result with { OutroRejected = true };
        }

        _store.Put(item.Id, result);
        if (result.OutroStartMs != null && result.OutroStartMs != before)
        {
            // The final credits go to Jellyfin as an Outro segment (Segments/CompanionSegmentProvider).
            await _segments.RunSegmentPluginProviders(item, _library.GetLibraryOptions(item), true, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task<CreditResult> FindAsync(BaseItem item, string version, CancellationToken cancellationToken)
    {
        var runtimeMs = (item.RunTimeTicks ?? 0) / TimeSpan.TicksPerMillisecond;
        if (runtimeMs < MinRuntimeMs || string.IsNullOrEmpty(item.Path) || !File.Exists(item.Path))
        {
            return new CreditResult(version, Array.Empty<CreditSkip>(), DateTimeOffset.UtcNow, Format: CreditResult.CurrentFormat);
        }

        var startMs = (long)(runtimeMs * CreditFinder.CreditsFrom);
        // Decided on the features as kept, so the admins' report shows exactly what was decided on.
        var frames = CreditFrameCodec.Round(await SampleAsync(item.Path, startMs, cancellationToken).ConfigureAwait(false));
        _store.PutFrames(item.Id, new CreditFrameSet(version, startMs, IntervalMs, Convert.ToBase64String(CreditFrameCodec.Pack(frames))));
        var decision = CreditFinder.Decide(runtimeMs, startMs, frames, IntervalMs, Dialogue(item.Id, startMs, runtimeMs));
        return new CreditResult(version, decision.Skips, DateTimeOffset.UtcNow, decision.OutroStartMs, decision.OutroStartMs == null ? null : runtimeMs, CreditResult.CurrentFormat);
    }

    /// <summary>
    /// The file's frames from [startMs] to its end, one a second, measured as ffmpeg hands them
    /// over (320×180 yuv420p, one frame's bytes held at a time): only the features are kept.
    /// </summary>
    private async Task<IReadOnlyList<CreditFeatures>> SampleAsync(string path, long startMs, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(_encoder.EncoderPath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in Arguments(path, startMs))
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
        var frames = await ReadFramesAsync(process.StandardOutput.BaseStream, Width, Height, 0.25, cancellationToken).ConfigureAwait(false);
        await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        var log = await errors.ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException("ffmpeg: " + log.Trim().Split('\n').LastOrDefault());
        }

        return frames;
    }

    /// <summary>
    /// ffmpeg's arguments: decode from [startMs] (references only, no deblocking: cheaper, and
    /// nothing a 320×180 frame shows), one frame a second, scaled by area averaging, as yuv420p.
    /// </summary>
    public static IReadOnlyList<string> Arguments(string path, long startMs) => new[]
    {
        "-hide_banner", "-nostdin", "-nostats", "-loglevel", "error",
        "-skip_frame", "noref", "-skip_loop_filter", "all",
        "-ss", (startMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture),
        "-i", path,
        "-an", "-sn", "-dn",
        "-vf", $"fps=1,scale={Width}:{Height}:flags=area,format=yuv420p",
        "-f", "rawvideo", "-pix_fmt", "yuv420p", "-",
    };

    /// <summary>Measures yuv420p frames [width]×[height] read from [stream] until it ends.</summary>
    public static async Task<IReadOnlyList<CreditFeatures>> ReadFramesAsync(Stream stream, int width, int height, double maxShift, CancellationToken cancellationToken)
    {
        var lumaSize = width * height;
        var chromaSize = (width / 2) * (height / 2);
        var buffer = new byte[lumaSize + (2 * chromaSize)];
        var reader = new CreditFeatureReader(width, height, maxShift);
        var frames = new List<CreditFeatures>();
        while (true)
        {
            var read = 0;
            while (read < buffer.Length)
            {
                var n = await stream.ReadAsync(buffer.AsMemory(read), cancellationToken).ConfigureAwait(false);
                if (n == 0)
                {
                    return frames;
                }

                read += n;
            }

            frames.Add(reader.Next(buffer.AsSpan(0, lumaSize), buffer.AsSpan(lumaSize, chromaSize), buffer.AsSpan(lumaSize + chromaSize, chromaSize)));
        }
    }
}
