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
/// (<see cref="Segments.CompanionSegmentProvider"/>), with any "Previously on…" recap
/// (<see cref="RecapFinder"/>), so Skip Intro and Skip Recap work in every app. A season is looked at
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
    private readonly Nitrate.Companion.Scanning.ReadFailures _failures;

    /// <summary>Initializes a new instance of the <see cref="IntroScanner"/> class.</summary>
    public IntroScanner(IntroStore store, ILibraryManager library, IMediaEncoder encoder, IMediaSegmentManager segments, ILogger<IntroScanner> log, Nitrate.Companion.Storage.CompanionDb db)
    {
        _failures = new Nitrate.Companion.Scanning.ReadFailures(db, "intros");
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
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(1.5).Ticks },
    };

    /// <summary>The version of an episode's file the results belong to.</summary>
    public static string VersionOf(BaseItem item) => item.Path + "|" + item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture);

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
        var episodes = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = new[] { BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
        }).OfType<Episode>().Where(e => !string.IsNullOrEmpty(e.Path)).ToList();
        _store.KeepOnly(new HashSet<Guid>(episodes.Select(e => e.Id)));
        // Each show's episodes in order (specials left out), and its seasons that need looking at.
        var shows = episodes
            .Where(e => (e.ParentIndexNumber ?? 0) != 0)
            .GroupBy(e => e.SeriesId)
            .Select(g => g.OrderBy(e => e.ParentIndexNumber).ThenBy(e => e.IndexNumber ?? int.MaxValue).ToList())
            .ToList();
        var work = shows
            .SelectMany(show => show.GroupBy(e => e.ParentIndexNumber)
                .Select(g => (Show: show, Season: g.ToList()))
                .Where(x => x.Season.Count >= 2 && x.Season.Any(e => _store.Needs(e.Id, VersionOf(e)) && _failures.MayRead(e.Id, VersionOf(e)))))
            .ToList();
        var done = 0;
        foreach (var group in work.GroupBy(w => w.Show))
        {
            // Whole-episode fingerprints for recaps, only while this show is being looked at.
            var whole = new Dictionary<Guid, uint[]>();
            foreach (var (show, season) in group)
            {
                cancellationToken.ThrowIfCancellationRequested();
                progress.Report(100.0 * done++ / Math.Max(1, work.Count));
                try
                {
                    await ScanSeasonAsync(show, season, whole, cancellationToken).ConfigureAwait(false);
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
        }

        // Whole-sound fingerprints are kept only for each show's last few episodes: the ones the
        // next new episode's recap is compared with.
        _store.KeepWholeOnly(new HashSet<Guid>(shows.SelectMany(show => show.TakeLast(RecapFrom)).Select(e => e.Id)));
        _failures.KeepOnly(new HashSet<Guid>(episodes.Select(e => e.Id)));
        progress.Report(100);
    }

    /// <summary>How many episodes before one its recap is looked for in (the previous season's too).</summary>
    public const int RecapFrom = 3;

    private async Task ScanSeasonAsync(List<Episode> show, List<Episode> season, Dictionary<Guid, uint[]> whole, CancellationToken cancellationToken)
    {
        var prints = new List<uint[]>();
        var kept = new List<Episode>();
        foreach (var e in season)
        {
            var version = VersionOf(e);
            var print = _store.Print(e.Id, version);
            if (print == null)
            {
                // A file that couldn't be read waits a while before it's tried again (ReadFailures).
                if (!_failures.MayRead(e.Id, version))
                {
                    continue;
                }

                try
                {
                    print = await ReadAsync(e, opening: true, cancellationToken).ConfigureAwait(false);
                    _store.SavePrint(e.Id, version, print);
                    _failures.Succeeded(e.Id);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    // One unreadable file never stops the rest of the season.
                    _failures.Failed(e.Id, version);
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
            var before = _store.Get(e.Id);
            // A recap only needs finding for a new or changed episode: the others keep theirs, so a
            // season gaining an episode never has its earlier episodes' sound read again.
            var recap = before == null || before.Version != VersionOf(e)
                ? await RecapAsync(show, e, prints[i], found[i], whole, cancellationToken).ConfigureAwait(false)
                : before.Recap;
            // What people rejected stays rejected until the file itself changes.
            var same = before?.Version == VersionOf(e);
            _store.Put(e.Id, new IntroResult(VersionOf(e), found[i], DateTimeOffset.UtcNow, recap, same && before!.IntroRejected, same && before!.RecapRejected));
            if ((found[i] != null || recap != null) && (found[i] != before?.Intro || recap != before?.Recap))
            {
                // Jellyfin asks every segment provider again (others' segments are made afresh too).
                await _segments.RunSegmentPluginProviders(e, _library.GetLibraryOptions(e), true, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>[episode]'s recap, from the <see cref="RecapFrom"/> episodes before it (read whole, kept in [whole] meanwhile).</summary>
    private async Task<Intro?> RecapAsync(List<Episode> show, Episode episode, uint[] opening, Intro? intro, Dictionary<Guid, uint[]> whole, CancellationToken cancellationToken)
    {
        var at = show.IndexOf(episode);
        if (at <= 0)
        {
            return null;
        }

        var earlier = new List<RecapFinder.Earlier>();
        foreach (var e in show.Skip(Math.Max(0, at - RecapFrom)).Take(Math.Min(RecapFrom, at)))
        {
            if (!whole.TryGetValue(e.Id, out var print))
            {
                try
                {
                    // Kept for the last few episodes of a show (KeepWholeOnly), read once each.
                    print = _store.Print(e.Id, VersionOf(e), whole: true);
                    if (print == null && _failures.MayRead(e.Id, VersionOf(e), "whole"))
                    {
                        print = await ReadAsync(e, opening: false, cancellationToken).ConfigureAwait(false);
                        // Kept only for the show's last few episodes, the ones a new episode is compared with.
                        if (show.IndexOf(e) >= show.Count - RecapFrom)
                        {
                            _store.SavePrint(e.Id, VersionOf(e), print, whole: true);
                        }

                        _failures.Succeeded(e.Id, "whole");
                    }

                    if (print == null)
                    {
                        continue;
                    }

                    whole[e.Id] = print;
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    _failures.Failed(e.Id, VersionOf(e), "whole");
                    _log.LogWarning("Intros: couldn't read {Name} for recaps: {Message}", e.Name, ex.Message);
                    continue;
                }
            }

            earlier.Add(new RecapFinder.Earlier(print, _store.Get(e.Id)?.Intro));
        }

        return RecapFinder.Find(opening, intro, earlier);
    }

    /// <summary>The fingerprint of [episode]'s sound: its opening (<see cref="IntroFinder.SearchMs"/>), or all of it.</summary>
    private async Task<uint[]> ReadAsync(Episode episode, bool opening, CancellationToken cancellationToken)
    {
        var runtimeMs = (episode.RunTimeTicks ?? 0) / TimeSpan.TicksPerMillisecond;
        var searchMs = opening ? IntroFinder.SearchMs(runtimeMs > 0 ? runtimeMs : 30 * 60_000) : (runtimeMs > 0 ? runtimeMs + 60_000 : 4 * 60 * 60_000);
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
