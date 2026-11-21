using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaEncoding;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Intros;

/// <summary>
/// "Find intros" (Dashboard → Scheduled tasks, nightly): reads the opening minutes of every
/// episode's sound once through the server's own ffmpeg, compares each season's episodes
/// (<see cref="IntroFinder"/>) and hands what it finds to Jellyfin as Intro segments
/// (<see cref="IntroSegmentProvider"/>), so Skip Intro works in every app. A season is looked at
/// again only when one of its episodes is new or changed; fingerprints are kept, so that costs
/// only the new file's read.
/// </summary>
public class IntroScanner : IScheduledTask
{
    private readonly IntroStore _store;
    private readonly ILibraryManager _library;
    private readonly IMediaEncoder _encoder;
    private readonly IMediaSegmentManager _segments;
    private readonly ILogger<IntroScanner> _log;

    /// <summary>Initializes a new instance of the <see cref="IntroScanner"/> class.</summary>
    public IntroScanner(IntroStore store, ILibraryManager library, IMediaEncoder encoder, IMediaSegmentManager segments, ILogger<IntroScanner> log)
    {
        _store = store;
        _library = library;
        _encoder = encoder;
        _segments = segments;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Find intros";

    /// <inheritdoc />
    public string Key => "NitrateFindIntros";

    /// <inheritdoc />
    public string Description => "Compares the opening minutes of each season's episodes and marks the intro they share, so Skip Intro works in every app.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(3).Ticks },
    };

    /// <summary>The version of an episode's file the results belong to.</summary>
    public static string VersionOf(BaseItem item) => item.Path + "|" + item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var episodes = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = new[] { BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
        }).OfType<Episode>().Where(e => !string.IsNullOrEmpty(e.Path)).ToList();
        _store.KeepOnly(new HashSet<Guid>(episodes.Select(e => e.Id)));
        // Seasons (specials left out), episodes in order.
        var seasons = episodes
            .Where(e => (e.ParentIndexNumber ?? 0) != 0)
            .GroupBy(e => (e.SeriesId, e.ParentIndexNumber))
            .Select(g => g.OrderBy(e => e.IndexNumber ?? int.MaxValue).ToList())
            .Where(s => s.Count >= 2 && s.Any(e => _store.Needs(e.Id, VersionOf(e))))
            .ToList();
        var done = 0;
        foreach (var season in seasons)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress.Report(100.0 * done++ / Math.Max(1, seasons.Count));
            try
            {
                await ScanSeasonAsync(season, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception e)
            {
                _log.LogWarning("Intros: couldn't check {Series} season {Season}: {Message}", season[0].SeriesName, season[0].ParentIndexNumber, e.Message);
            }
        }

        progress.Report(100);
    }

    private async Task ScanSeasonAsync(List<Episode> season, CancellationToken cancellationToken)
    {
        var prints = new List<uint[]>();
        var kept = new List<Episode>();
        foreach (var e in season)
        {
            var version = VersionOf(e);
            var print = _store.Print(e.Id, version);
            if (print == null)
            {
                try
                {
                    print = await ReadAsync(e, cancellationToken).ConfigureAwait(false);
                    _store.SavePrint(e.Id, version, print);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    // One unreadable file never stops the rest of the season (it's tried again next night).
                    _log.LogWarning("Intros: couldn't read {Name}: {Message}", e.Name, ex.Message);
                    continue;
                }
            }

            prints.Add(print);
            kept.Add(e);
        }

        var found = IntroFinder.Find(prints);
        for (var i = 0; i < kept.Count; i++)
        {
            var e = kept[i];
            var before = _store.Get(e.Id)?.Intro;
            _store.Put(e.Id, new IntroResult(VersionOf(e), found[i], DateTimeOffset.UtcNow));
            if (found[i] != null && found[i] != before)
            {
                // Jellyfin asks every segment provider again (others' segments are made afresh too).
                await _segments.RunSegmentPluginProviders(e, _library.GetLibraryOptions(e), true, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>The fingerprint of the opening of [episode]'s sound (<see cref="IntroFinder.SearchMs"/>).</summary>
    private async Task<uint[]> ReadAsync(Episode episode, CancellationToken cancellationToken)
    {
        var runtimeMs = (episode.RunTimeTicks ?? 0) / TimeSpan.TicksPerMillisecond;
        var searchMs = IntroFinder.SearchMs(runtimeMs > 0 ? runtimeMs : 30 * 60_000);
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
            "-t", (searchMs / 1000.0).ToString("0.###", CultureInfo.InvariantCulture),
            "-i", episode.Path,
            "-vn", "-sn", "-dn",
            "-ac", "1", "-ar", AudioPrint.SampleRate.ToString(CultureInfo.InvariantCulture),
            "-f", "s16le", "-",
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

        var bytes = output.ToArray();
        return AudioPrint.Of(MemoryMarshal.Cast<byte, short>(bytes.AsSpan(0, bytes.Length & ~1)));
    }
}
