using System.Collections.Concurrent;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Formats;

/// <summary>A video stream's format, as the server's database has it.</summary>
/// <param name="Codec">The codec ("hevc").</param>
/// <param name="BitDepth">Bits per sample, when known.</param>
/// <param name="RangeType">Jellyfin's video range type ("DOVIWithHDR10", "HDR10"…).</param>
/// <param name="DvProfile">The Dolby Vision profile, when there is one.</param>
public record VideoFormat(string? Codec, int? BitDepth, string? RangeType, int? DvProfile);

/// <summary>A title's formats: every video stream, and its sound codecs (a show: all its episodes').</summary>
/// <param name="Id">The film or show.</param>
/// <param name="Video">Its video streams.</param>
/// <param name="Audio">Its sound codecs.</param>
public record TitleFormats(Guid Id, IReadOnlyList<VideoFormat> Video, IReadOnlyList<string> Audio);

/// <summary>
/// What each title is made of (codecs, bit depth, HDR, sound), from the server's database only, for
/// the apps' format filters: kept in memory per title once read, and forgotten for a title as soon
/// as the library says it changed, so a filter never needs every title's full stream details sent
/// to the app again. Who may see what is checked per request.
/// </summary>
public class FormatIndex : IHostedService
{
    private readonly ILibraryManager _library;
    private readonly ConcurrentDictionary<Guid, (IReadOnlyList<VideoFormat> Video, IReadOnlyList<string> Audio)> _items = new();

    /// <summary>Initializes a new instance of the <see cref="FormatIndex"/> class.</summary>
    public FormatIndex(ILibraryManager library) => _library = library;

    /// <summary>How many titles' formats are kept.</summary>
    public int Count => _items.Count;

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded += OnChanged;
        _library.ItemUpdated += OnChanged;
        _library.ItemRemoved += OnChanged;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded -= OnChanged;
        _library.ItemUpdated -= OnChanged;
        _library.ItemRemoved -= OnChanged;
        return Task.CompletedTask;
    }

    /// <summary>Forgets what was kept for an item (it was added, changed or removed).</summary>
    public void Forget(Guid id) => _items.TryRemove(id, out _);

    /// <summary>
    /// The formats of every film and show (and other videos) the person can see in [parentId] (a
    /// library, or everything), shows from their episodes. [types] narrows it (Movie, Series, Video…).
    /// </summary>
    public IReadOnlyList<TitleFormats> For(User user, Guid? parentId, IReadOnlyCollection<BaseItemKind> types)
    {
        var shows = types.Count == 0 || types.Contains(BaseItemKind.Series);
        var others = types.Where(t => t != BaseItemKind.Series).ToArray();
        if (types.Count == 0)
        {
            others = new[] { BaseItemKind.Movie, BaseItemKind.Video, BaseItemKind.MusicVideo };
        }

        var result = new List<TitleFormats>();
        if (others.Length > 0)
        {
            foreach (var item in List(user, parentId, others))
            {
                var (video, audio) = Of(item);
                result.Add(new TitleFormats(item.Id, video, audio));
            }
        }

        if (shows)
        {
            var bySeries = new Dictionary<Guid, (HashSet<VideoFormat> Video, HashSet<string> Audio)>();
            foreach (var item in List(user, parentId, new[] { BaseItemKind.Episode }))
            {
                if (item is not Episode e || e.SeriesId == Guid.Empty)
                {
                    continue;
                }

                var (video, audio) = Of(item);
                if (!bySeries.TryGetValue(e.SeriesId, out var all))
                {
                    all = (new HashSet<VideoFormat>(), new HashSet<string>());
                    bySeries[e.SeriesId] = all;
                }

                all.Video.UnionWith(video);
                all.Audio.UnionWith(audio);
            }

            result.AddRange(bySeries.Select(kv => new TitleFormats(kv.Key, kv.Value.Video.ToList(), kv.Value.Audio.ToList())));
        }

        return result;
    }

    /// <summary>One title's formats from its streams (kept from now on).</summary>
    public static (IReadOnlyList<VideoFormat> Video, IReadOnlyList<string> Audio) Summarize(IEnumerable<MediaStream> streams)
    {
        var list = streams.ToList();
        var video = list.Where(s => s.Type == MediaStreamType.Video)
            .Select(s => new VideoFormat(s.Codec, s.BitDepth, s.VideoRangeType.ToString(), s.DvProfile))
            .Distinct().ToList();
        var audio = list.Where(s => s.Type == MediaStreamType.Audio && !string.IsNullOrEmpty(s.Codec))
            .Select(s => s.Codec!).Distinct().ToList();
        return (video, audio);
    }

    private (IReadOnlyList<VideoFormat> Video, IReadOnlyList<string> Audio) Of(BaseItem item) =>
        _items.GetOrAdd(item.Id, _ => Summarize(item.GetMediaStreams()));

    private IReadOnlyList<BaseItem> List(User user, Guid? parentId, BaseItemKind[] kinds) => _library.GetItemList(new InternalItemsQuery(user)
    {
        IncludeItemTypes = kinds,
        Recursive = true,
        IsVirtualItem = false,
        ParentId = parentId ?? Guid.Empty,
        DtoOptions = new DtoOptions(false) { EnableImages = false },
    });

    private void OnChanged(object? sender, ItemChangeEventArgs e)
    {
        if (e.Item != null)
        {
            Forget(e.Item.Id);
        }
    }
}
