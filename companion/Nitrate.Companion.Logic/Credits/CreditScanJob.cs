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
/// "Find scenes in the credits" (Dashboard → Scheduled tasks, nightly): reads the end of every
/// film, and of episodes tagged as having a scene in the credits, through the server's own ffmpeg
/// as 320×180 frames with colour, one per keyframe, backwards from the end until it's clearly back
/// in the film, then each boundary closer (<see cref="CreditReader"/>), in hardware when Jellyfin
/// decodes that codec in hardware (<see cref="HardwareDecoding"/>), measuring each frame as it
/// arrives (<see cref="CreditFeatureReader"/>: only the features are kept, never the pictures), and
/// keeps what <see cref="CreditFinder"/> decides, with the title's subtitle lines (the spoken-line
/// index) voting when it has them. Titles are checked once (again when their file changes), one file
/// read at a time across the file-reading tasks (<see cref="Nitrate.Companion.Scanning.FileReading"/>).
/// </summary>
public class CreditScanJob : IScheduledTask
{
    /// <summary>The frames' width.</summary>
    public const int Width = 320;

    /// <summary>The frames' height.</summary>
    public const int Height = 180;

    /// <summary>One frame every this long in readings kept from before keyframes (and in reports with nothing read yet).</summary>
    public const long IntervalMs = 1_000;

    /// <summary>Titles shorter than this have no credits worth reading.</summary>
    public const long MinRuntimeMs = 10 * 60_000;

    private static readonly string[] Stingers = { "aftercreditsstinger", "duringcreditsstinger" };
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, bool> Reading = new();
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, (DateTimeOffset At, string Why)> Failed = new();
    private readonly CreditStore _store;
    private readonly ILibraryManager _library;
    private readonly IMediaEncoder _encoder;
    private readonly IMediaSourceManager? _sources;
    private readonly MediaBrowser.Controller.Configuration.IServerConfigurationManager? _config;
    private readonly MediaBrowser.Controller.MediaSegments.IMediaSegmentManager _segments;
    private readonly Nitrate.Companion.Lines.LineIndex _lines;
    private readonly ILogger<CreditScanJob> _log;

    private readonly Nitrate.Companion.Scanning.ReadFailures _failures;

    /// <summary>Initializes a new instance of the <see cref="CreditScanJob"/> class.</summary>
    public CreditScanJob(CreditStore store, ILibraryManager library, IMediaEncoder encoder, MediaBrowser.Controller.MediaSegments.IMediaSegmentManager segments, Nitrate.Companion.Lines.LineIndex lines, ILogger<CreditScanJob> log, Nitrate.Companion.Storage.CompanionDb db, IMediaSourceManager? sources = null, MediaBrowser.Controller.Configuration.IServerConfigurationManager? config = null)
    {
        _sources = sources;
        _config = config;
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
    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken) =>
        // One file read at a time across the file-reading tasks: the gate is taken per file (FileReading.Gate).
        RunAsync(progress, cancellationToken);

    /// <summary>
    /// Reads one title now, in the background (an admin's Credits check when its features aren't
    /// kept yet), as soon as the file being read now is done: it goes before the nightly tasks'
    /// next file; false when it's already being read.
    /// </summary>
    public bool ReadSoon(BaseItem item)
    {
        if (!Reading.TryAdd(item.Id, true))
        {
            return false;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await ReadOneAsync(item, VersionOf(item), CancellationToken.None, priority: true).ConfigureAwait(false);
                Failed.TryRemove(item.Id, out _);
            }
            catch (Exception e)
            {
                Failed[item.Id] = (DateTimeOffset.UtcNow, e.Message);
                _log.LogWarning("Credit scenes: couldn't read {Name}: {Message}", item.Name, e.Message);
            }
            finally
            {
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

    private async Task ReadOneAsync(BaseItem item, string version, CancellationToken cancellationToken, bool priority = false)
    {
        var before = _store.Get(item.Id)?.OutroStartMs;
        // One file read at a time; a requested read goes next.
        var result = await Nitrate.Companion.Scanning.FileReading.Gate.RunAsync(() => FindAsync(item, version, cancellationToken), cancellationToken, priority).ConfigureAwait(false);
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

        var hardware = HardwareFor(item);
        var clock = Stopwatch.StartNew();
        var runs = 0;
        var inHardware = 0;
        var read = await CreditReader.ReadAsync(
            runtimeMs,
            async (request, ct) =>
            {
                var (run, used) = await HardwareDecoding.RunAsync(
                    hardware,
                    hw => DecodeAsync(item.Path, request, hw, ct),
                    r => r.Count,
                    why => _log.LogWarning("Credit scenes: {Name}: {Why}", item.Name, why)).ConfigureAwait(false);
                runs++;
                inHardware += used == null ? 0 : 1;
                return run;
            },
            from => Dialogue(item.Id, from, runtimeMs),
            cancellationToken).ConfigureAwait(false);
        clock.Stop();
        var usedHardware = runs > 0 && inHardware == runs ? hardware?.Name : null;
        _log.LogInformation(
            "Credit scenes: {Name}: read {Back:0.0} min back in {Seconds:0.0} s, {Frames} frames decoded, {How}",
            item.Name,
            (runtimeMs - read.ReadFromMs) / 60_000.0,
            clock.Elapsed.TotalSeconds,
            read.Decoded,
            usedHardware ?? (hardware != null ? "software (" + hardware.Name + " failed)" : "software"));
        _store.PutFrames(item.Id, CreditFrameSet.Of(version, read.ReadFromMs, read.Frames, read.Timeline, clock.ElapsedMilliseconds, read.Decoded, usedHardware));
        var decision = read.Decision;
        return new CreditResult(version, decision.Skips, DateTimeOffset.UtcNow, decision.OutroStartMs, decision.OutroStartMs == null ? null : runtimeMs, CreditResult.CurrentFormat);
    }

    /// <summary>How [item]'s video is decoded in hardware, by Jellyfin's settings; null for software.</summary>
    private HardwareChoice? HardwareFor(BaseItem item)
    {
        try
        {
            if (_config == null || _sources == null)
            {
                return null;
            }

            var options = MediaBrowser.Common.Configuration.EncodingConfigurationExtensions.GetEncodingOptions(_config);
            var video = _sources.GetMediaStreams(item.Id).FirstOrDefault(s => s.Type == MediaBrowser.Model.Entities.MediaStreamType.Video);
            return options == null || video == null
                ? null
                : HardwareDecoding.From(options).For(video.Codec, video.BitDepth, video.PixelFormat, OperatingSystem.IsWindows(), _encoder.SupportsHwaccel);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            _log.LogDebug("Credit scenes: no hardware decoding for {Name}: {Message}", item.Name, e.Message);
            return null;
        }
    }

    /// <summary>
    /// Decodes [request] of the file at [path] with the server's ffmpeg ([hardware] or software) and
    /// measures the frames as they arrive (320×180 yuv420p, one frame's bytes held at a time), each
    /// at its time from ffmpeg's <c>showinfo</c>.
    /// </summary>
    private async Task<CreditRun> DecodeAsync(string path, CreditDecodeRequest request, HardwareChoice? hardware, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo(_encoder.EncoderPath)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var arg in Arguments(path, request, hardware))
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
        var times = System.Threading.Channels.Channel.CreateUnbounded<double>();
        var errors = new List<string>();
        var stderr = Task.Run(
            async () =>
            {
                try
                {
                    while (await process.StandardError.ReadLineAsync(cancellationToken).ConfigureAwait(false) is { } line)
                    {
                        if (ShowInfoTime(line) is { } t)
                        {
                            times.Writer.TryWrite(t);
                        }
                        else if (!line.Contains("Parsed_showinfo", StringComparison.Ordinal) && line.Length > 0)
                        {
                            lock (errors)
                            {
                                errors.Add(line);
                                if (errors.Count > 20)
                                {
                                    errors.RemoveAt(0);
                                }
                            }
                        }
                    }
                }
                finally
                {
                    times.Writer.TryComplete();
                }
            },
            CancellationToken.None);
        var builder = new CreditRunBuilder(Width, Height, request.FromMs, request.ToMs, request.MaxShift);
        await ReadFramesAsync(process.StandardOutput.BaseStream, Width, Height, async ct =>
        {
            // Frame k's time is showinfo's k-th line (written before the frame leaves the filters).
            return await times.Reader.WaitToReadAsync(ct).ConfigureAwait(false) && times.Reader.TryRead(out var t) ? request.FromMs + (long)Math.Round(t * 1000) : null;
        }, builder.Add, cancellationToken).ConfigureAwait(false);
        await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        await stderr.ConfigureAwait(false);
        if (process.ExitCode != 0)
        {
            lock (errors)
            {
                throw new InvalidOperationException("ffmpeg: " + (errors.LastOrDefault() ?? "exit code " + process.ExitCode.ToString(CultureInfo.InvariantCulture)).Trim());
            }
        }

        return builder.Build();
    }

    /// <summary>The time (s, from where decoding started) in one line of ffmpeg's <c>showinfo</c>; null for other lines.</summary>
    public static double? ShowInfoTime(string line)
    {
        var at = line.IndexOf(" pts_time:", StringComparison.Ordinal);
        if (at < 0 || !line.Contains("n:", StringComparison.Ordinal))
        {
            return null;
        }

        var start = at + " pts_time:".Length;
        var end = start;
        while (end < line.Length && !char.IsWhiteSpace(line[end]))
        {
            end++;
        }

        return double.TryParse(line.AsSpan(start, end - start), NumberStyles.Float, CultureInfo.InvariantCulture, out var t) ? t : null;
    }

    /// <summary>
    /// ffmpeg's arguments for [request] of [path]: [hardware]'s input arguments (frames come back
    /// in system memory), then either only keyframes (<c>-skip_frame nokey</c>, each frame passed
    /// through as decoded) or every frame (references only) thinned to <see cref="CreditDecodeRequest.Fps"/>;
    /// no deblocking (nothing a 320×180 frame shows); from <see cref="CreditDecodeRequest.FromMs"/>
    /// for as long as asked (to the end without <see cref="CreditDecodeRequest.ToMs"/>); each frame's
    /// time logged by <c>showinfo</c>; scaled by area averaging, as yuv420p.
    /// </summary>
    public static IReadOnlyList<string> Arguments(string path, CreditDecodeRequest request, HardwareChoice? hardware = null)
    {
        var args = new List<string> { "-hide_banner", "-nostdin", "-nostats", "-loglevel", "info" };
        if (hardware != null)
        {
            args.AddRange(hardware.Arguments);
        }

        args.AddRange(new[] { "-skip_frame", request.Fps <= 0 ? "nokey" : "noref", "-skip_loop_filter", "all" });
        args.AddRange(new[] { "-ss", Seconds(request.FromMs) });
        if (request.ToMs is { } to)
        {
            // A little past the end: keyframes are cut by their own times here, not by ffmpeg.
            args.AddRange(new[] { "-t", Seconds(Math.Max(1, to - request.FromMs) + 1_000) });
        }

        args.AddRange(new[] { "-i", path, "-an", "-sn", "-dn", "-fps_mode", "passthrough" });
        var thin = request.Fps > 0 ? $"fps={request.Fps.ToString(CultureInfo.InvariantCulture)}," : string.Empty;
        args.AddRange(new[] { "-vf", $"{thin}showinfo,scale={Width}:{Height}:flags=area,format=yuv420p", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-" });
        return args;
    }

    private static string Seconds(long ms) => (ms / 1000.0).ToString("0.###", CultureInfo.InvariantCulture);

    /// <summary>
    /// Hands each yuv420p frame [width]×[height] read from [stream] to [frame] with its time from
    /// [timeOf] (asked as each frame arrives; null: no time for it, so it's left out), until the
    /// stream ends. One frame's bytes are held at a time.
    /// </summary>
    public static async Task ReadFramesAsync(Stream stream, int width, int height, Func<CancellationToken, Task<long?>> timeOf, CreditFrameSink frame, CancellationToken cancellationToken)
    {
        var buffer = new byte[(width * height) + (2 * (width / 2) * (height / 2))];
        while (true)
        {
            var read = 0;
            while (read < buffer.Length)
            {
                var n = await stream.ReadAsync(buffer.AsMemory(read), cancellationToken).ConfigureAwait(false);
                if (n == 0)
                {
                    return;
                }

                read += n;
            }

            if (await timeOf(cancellationToken).ConfigureAwait(false) is { } at)
            {
                frame(at, buffer);
            }
        }
    }

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

/// <summary>Takes one frame (its time, its yuv420p bytes, valid only during the call).</summary>
public delegate void CreditFrameSink(long atMs, ReadOnlySpan<byte> frame);
