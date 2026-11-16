using System.Globalization;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
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
/// Embedded subtitles are extracted by the server (once, then cached), so the first run over a
/// large library takes a while.
/// </summary>
public class LineIndexer : IScheduledTask
{
    /// <summary>Languages read per title.</summary>
    public const int MaxLanguages = 2;

    private readonly LineIndex _index;
    private readonly ILibraryManager _library;
    private readonly IMediaSourceManager _sources;
    private readonly ISubtitleEncoder _subtitles;
    private readonly ILogger<LineIndexer> _log;

    /// <summary>Initializes a new instance of the <see cref="LineIndexer"/> class.</summary>
    public LineIndexer(LineIndex index, ILibraryManager library, IMediaSourceManager sources, ISubtitleEncoder subtitles, ILogger<LineIndexer> log)
    {
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
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
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
        if (!_index.NeedsIndex(item.Id, version))
        {
            return;
        }

        var tracks = new List<(string? Language, IReadOnlyList<LineCue> Cues)>();
        foreach (var s in streams)
        {
            var stream = await _subtitles.GetSubtitles(item, item.Id.ToString("N", CultureInfo.InvariantCulture), s.Index, "srt", 0, 0, false, cancellationToken).ConfigureAwait(false);
            await using (stream.ConfigureAwait(false))
            {
                using var reader = new StreamReader(stream);
                var text = await reader.ReadToEndAsync(cancellationToken).ConfigureAwait(false);
                var cues = SubtitleText.Parse(text);
                if (cues.Count > 0)
                {
                    tracks.Add((s.Language, cues));
                }
            }
        }

        _index.Replace(item.Id, version, tracks);
    }

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
