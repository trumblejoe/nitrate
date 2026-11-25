using System.Globalization;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.IO;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaEncoding;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Lines;

/// <summary>
/// "Index spoken lines" (Dashboard → Scheduled tasks, nightly): reads the text subtitles of every
/// film and episode into the <see cref="LineIndex"/>, up to two languages per title. Only titles
/// new or changed since the last run are read; ones gone from the library are forgotten.
/// External subtitle files are read as they are. Embedded ones are read from the copy the server
/// already extracted (<see cref="IPathManager.GetSubtitlePath"/>, the folder Jellyfin keeps them in)
/// when there is one; only without one does the server extract them, reading the whole file, and
/// only then is the file-reading gate taken, so the first run over a large library takes a while.
/// </summary>
public class LineIndexJob : IScheduledTask
{
    /// <summary>Languages read per title.</summary>
    public const int MaxLanguages = 2;

    private readonly LineIndex _index;
    private readonly ILibraryManager _library;
    private readonly IMediaSourceManager _sources;
    private readonly ISubtitleEncoder _subtitles;
    private readonly IPathManager? _paths;
    private readonly ILogger<LineIndexJob> _log;

    private readonly Nitrate.Companion.Scanning.ReadFailures _failures;

    /// <summary>Initializes a new instance of the <see cref="LineIndexJob"/> class.</summary>
    public LineIndexJob(LineIndex index, ILibraryManager library, IMediaSourceManager sources, ISubtitleEncoder subtitles, ILogger<LineIndexJob> log, Nitrate.Companion.Storage.CompanionDb db, IPathManager? paths = null)
    {
        _paths = paths;
        _failures = new Nitrate.Companion.Scanning.ReadFailures(db, "lines");
        _index = index;
        _library = library;
        _sources = sources;
        _subtitles = subtitles;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Index spoken lines";

    /// <inheritdoc />
    public string Key => "NitrateIndexSpokenLines";

    /// <inheritdoc />
    public string Description => "Reads the subtitles of films and episodes so Nitrate can search what people say.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(3).Ticks },
    };

    /// <inheritdoc />
    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken) =>
        // One file read at a time across the file-reading tasks: the gate is taken per file (FileReading.Gate).
        RunAsync(progress, cancellationToken);

    private async Task RunAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        if (!(Plugin.Instance?.Configuration.EnableLines ?? true))
        {
            return;
        }

        var items = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
        });
        var present = new HashSet<Guid>(items.Select(i => i.Id));
        foreach (var gone in _index.Items().Where(id => !present.Contains(id)))
        {
            _index.Remove(gone);
        }

        _failures.KeepOnly(present);

        var done = 0;
        foreach (var item in items)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress.Report(100.0 * done++ / Math.Max(1, items.Count));
            try
            {
                await IndexAsync(item, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception e)
            {
                // One unreadable file never stops the rest.
                _log.LogWarning("Spoken lines: couldn't read {Name}'s subtitles: {Message}", item.Name, e.Message);
            }
        }

        progress.Report(100);
    }

    /// <summary>Reads one title's subtitles, unless they're unchanged since last time.</summary>
    public async Task IndexAsync(BaseItem item, CancellationToken cancellationToken)
    {
        var streams = Pick(_sources.GetMediaStreams(item.Id));
        var version = string.Join(
            ';',
            new[] { item.DateModified.Ticks.ToString(CultureInfo.InvariantCulture) }.Concat(streams.Select(s => $"{s.Index}:{s.Language}:{s.Path}")));
        if (!_index.NeedsIndex(item.Id, version) || !_failures.MayRead(item.Id, version))
        {
            return;
        }

        try
        {
            await ReadAsync(item, streams, version, cancellationToken).ConfigureAwait(false);
            _failures.Succeeded(item.Id);
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            // Waits a day, a week, then a month before another try (or until the file changes).
            _failures.Failed(item.Id, version);
            throw;
        }
    }

    private async Task ReadAsync(BaseItem item, IReadOnlyList<MediaStream> streams, string version, CancellationToken cancellationToken)
    {
        var tracks = new List<(string? Language, IReadOnlyList<LineCue> Cues)>();
        foreach (var s in streams)
        {
            IReadOnlyList<LineCue> cues;
            if (!s.IsExternal && Cached(item, s) is { } cached)
            {
                // The server's own extracted copy: no need to read the media file.
                cues = SubtitleText.ParseAny(await File.ReadAllTextAsync(cached, cancellationToken).ConfigureAwait(false), Path.GetExtension(cached));
            }
            else if (s.IsExternal)
            {
                // A file beside the media: read as it is (the server converts it to SRT).
                cues = await ServerSrtAsync(item, s, cancellationToken).ConfigureAwait(false);
            }
            else
            {
                // Extracting reads the whole media file: one file read at a time (FileReading.Gate).
                cues = await Nitrate.Companion.Scanning.FileReading.Gate.RunAsync(() => ServerSrtAsync(item, s, cancellationToken), cancellationToken).ConfigureAwait(false);
            }

            if (cues.Count > 0)
            {
                tracks.Add((s.Language, cues));
            }
        }

        _index.Replace(item.Id, version, tracks);
    }

    private async Task<IReadOnlyList<LineCue>> ServerSrtAsync(BaseItem item, MediaStream s, CancellationToken cancellationToken)
    {
        var stream = await _subtitles.GetSubtitles(item, item.Id.ToString("N", CultureInfo.InvariantCulture), s.Index, "srt", 0, 0, false, cancellationToken).ConfigureAwait(false);
        await using (stream.ConfigureAwait(false))
        {
            using var reader = new StreamReader(stream);
            return SubtitleText.Parse(await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false));
        }
    }

    /// <summary>
    /// The copy of embedded subtitle [stream] the server already extracted, if there is one and it's
    /// no older than the file: never asks for an extraction.
    /// </summary>
    private string? Cached(BaseItem item, MediaStream stream)
    {
        if (_paths == null)
        {
            return null;
        }

        try
        {
            return CachedCopy(i => CachedCandidates(_paths, item.Id.ToString("N", CultureInfo.InvariantCulture), i), stream.Index, item.DateModified);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    private static IEnumerable<string> CachedCandidates(IPathManager paths, string mediaSourceId, int index)
    {
        foreach (var ext in SubtitleText.CachedExtensions)
        {
            // Jellyfin passes the extension with its dot; without is tried too, in case that changes.
            foreach (var e in new[] { "." + ext, ext })
            {
                if (paths.GetSubtitlePath(mediaSourceId, index, e) is { Length: > 0 } path)
                {
                    yield return path;
                }
            }
        }
    }

    /// <summary>
    /// The first of [candidates] (for stream [index]) that exists, isn't empty and was written no
    /// earlier than the media file changed ([modified]); null when none.
    /// </summary>
    public static string? CachedCopy(Func<int, IEnumerable<string>> candidates, int index, DateTime modified) =>
        candidates(index).FirstOrDefault(p => File.Exists(p) && new FileInfo(p).Length > 0 && File.GetLastWriteTimeUtc(p) >= (modified.Kind == DateTimeKind.Local ? modified.ToUniversalTime() : modified));

    /// <summary>
    /// Which subtitles to read: text ones (not forced, which hold only a few lines), one per
    /// language, plain before hearing-impaired, at most <see cref="MaxLanguages"/>.
    /// </summary>
    public static IReadOnlyList<MediaStream> Pick(IEnumerable<MediaStream> streams) =>
        streams
            .Where(s => s.Type == MediaStreamType.Subtitle && s.IsTextSubtitleStream && !s.IsForced)
            .OrderBy(s => s.IsHearingImpaired)
            .ThenBy(s => s.Index)
            .GroupBy(s => (s.Language ?? "und").ToLowerInvariant())
            .Select(g => g.First())
            .Take(MaxLanguages)
            .ToList();
}
