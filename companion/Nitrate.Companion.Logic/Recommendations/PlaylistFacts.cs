using System.Collections.Concurrent;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Playlists;
using Microsoft.Extensions.Hosting;
using Nitrate.Companion.Changes;

namespace Nitrate.Companion.Recommendations;

/// <summary>One episode of a show or season put in a playlist whole, with the person's watch state.</summary>
/// <param name="Id">The episode.</param>
/// <param name="SeasonId">Its season.</param>
/// <param name="Season">Its season number (0: specials).</param>
/// <param name="Number">Its episode number.</param>
/// <param name="Missing">Known but not in the library.</param>
/// <param name="Played">Watched.</param>
/// <param name="LastPlayedMs">When last played (any app), epoch ms.</param>
/// <param name="PositionMs">Where it was left, ms.</param>
public record EpisodeFacts(Guid Id, Guid? SeasonId, int? Season, int? Number, bool Missing, bool Played, long? LastPlayedMs, long PositionMs);

/// <summary>One entry of a playlist as it's stored, with the person's watch state.</summary>
/// <param name="Id">The title, show or season.</param>
/// <param name="Kind">Jellyfin's item kind ("Movie", "Episode", "Series", "Season", "Video"…).</param>
/// <param name="SeriesId">An episode's or season's show.</param>
/// <param name="SeasonId">An episode's season.</param>
/// <param name="Season">An episode's or season's number.</param>
/// <param name="Played">Watched.</param>
/// <param name="LastPlayedMs">When last played (any app), epoch ms.</param>
/// <param name="PositionMs">Where it was left, ms.</param>
/// <param name="Episodes">A show's or season's episodes in the show's order (specials and missing ones included, marked).</param>
public record EntryFacts(Guid Id, string Kind, Guid? SeriesId, Guid? SeasonId, int? Season, bool Played, long? LastPlayedMs, long PositionMs, IReadOnlyList<EpisodeFacts>? Episodes);

/// <summary>A playlist's entries in its own order.</summary>
/// <param name="Id">The playlist.</param>
/// <param name="Entries">What it holds.</param>
public record PlaylistFactsOut(Guid Id, IReadOnlyList<EntryFacts> Entries);

/// <summary>
/// Facts about the playlists a person is watching through, for the apps' own rules (core
/// <c>PlaylistPlaces</c>: where each was left, what's next): which playlists hold something they
/// played since a moment the app names, and what those hold with their watch state. No rule is
/// applied here. Kept cheap: which playlists hold what is kept in memory (read again only for a
/// playlist that changes), what each person played lately is read once and then followed from the
/// server's watch events, and an answer is kept while nothing it depends on has changed. One
/// working-out per person at a time; anyone asking meanwhile shares it. Big playlists (watch-order
/// guides hold hundreds of entries, a title often in several) cost nothing after the first time:
/// each playlist's shape (its entries, and a show's or season's episodes) is kept per person until
/// the playlist or something in it changes, and each person's watch state per title is kept and
/// followed from the watch events, so an answer after a play is put together in memory.
/// </summary>
public class PlaylistFacts : IHostedService
{
    private const int RecentKept = 200;

    /// <summary>How long a playlist's shape is kept with nothing changing (a person's access can change unseen).</summary>
    private static readonly TimeSpan ShapeKept = TimeSpan.FromHours(6);

    private readonly ILibraryManager _library;
    private readonly IUserDataManager _userData;
    private readonly ChangeCounters _changes;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, IReadOnlyList<Guid>> _members = new();
    private readonly Dictionary<Guid, HashSet<Guid>> _holders = new();
    private readonly HashSet<Guid> _dirty = new();
    private readonly ConcurrentDictionary<Guid, List<Played>> _recent = new();
    private readonly ConcurrentDictionary<(Guid User, long Since), (ChangesOut At, IReadOnlyList<PlaylistFactsOut> Facts)> _answers = new();
    private readonly ConcurrentDictionary<(Guid User, long Since), Lazy<Task<IReadOnlyList<PlaylistFactsOut>>>> _working = new();
    private readonly ConcurrentDictionary<(Guid User, Guid Playlist), PlaylistShape> _shapes = new();
    private readonly ConcurrentDictionary<Guid, WatchStates> _states = new();
    private bool _built;

    /// <summary>Initializes a new instance of the <see cref="PlaylistFacts"/> class.</summary>
    public PlaylistFacts(ILibraryManager library, IUserDataManager userData, ChangeCounters changes)
    {
        _library = library;
        _userData = userData;
        _changes = changes;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded += OnItem;
        _library.ItemUpdated += OnItem;
        _library.ItemRemoved += OnItem;
        _userData.UserDataSaved += OnUserData;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded -= OnItem;
        _library.ItemUpdated -= OnItem;
        _library.ItemRemoved -= OnItem;
        _userData.UserDataSaved -= OnUserData;
        return Task.CompletedTask;
    }

    /// <summary>
    /// The facts for [user]: playlists holding something they played since [sinceMs] (or an episode of
    /// a show or season put in whole), most recently played from first, at most [max]. Shared with
    /// anyone asking the same meanwhile; [cancellationToken] only stops this caller waiting.
    /// </summary>
    public async Task<IReadOnlyList<PlaylistFactsOut>> ForAsync(User user, long sinceMs, int max, CancellationToken cancellationToken)
    {
        var key = (user.Id, sinceMs);
        var now = _changes.For(user.Id, null);
        if (_answers.TryGetValue(key, out var kept) && kept.At == now)
        {
            return kept.Facts.Take(max).ToList();
        }

        var work = _working.GetOrAdd(key, k => new Lazy<Task<IReadOnlyList<PlaylistFactsOut>>>(() => Task.Run(() =>
        {
            try
            {
                var facts = Work(user, sinceMs);
                _answers[key] = (now, facts);
                return facts;
            }
            finally
            {
                _working.TryRemove(key, out _);
            }
        })));
        var result = await work.Value.WaitAsync(cancellationToken).ConfigureAwait(false);
        return result.Take(max).ToList();
    }

    /// <summary>Which playlists (ids) hold any of [ids], from the kept index.</summary>
    public IReadOnlyList<Guid> Holding(IEnumerable<Guid> ids)
    {
        lock (_lock)
        {
            Refresh();
            return ids.SelectMany(id => _holders.TryGetValue(id, out var p) ? p : Enumerable.Empty<Guid>()).Distinct().ToList();
        }
    }

    /// <summary>
    /// Keeps the index current: every playlist the first time, then only those that changed. Called
    /// under the lock.
    /// </summary>
    private void Refresh()
    {
        if (!_built)
        {
            foreach (var p in _library.GetItemList(new InternalItemsQuery { IncludeItemTypes = new[] { BaseItemKind.Playlist }, Recursive = true }).OfType<Playlist>())
            {
                Put(p.Id, p);
            }

            _built = true;
            _dirty.Clear();
            return;
        }

        foreach (var id in _dirty.ToList())
        {
            Put(id, _library.GetItemById(id) as Playlist);
        }

        _dirty.Clear();
    }

    private void Put(Guid id, Playlist? playlist)
    {
        if (_members.TryGetValue(id, out var old))
        {
            foreach (var m in old)
            {
                if (_holders.TryGetValue(m, out var set))
                {
                    set.Remove(id);
                }
            }

            _members.Remove(id);
        }

        if (playlist is null || playlist.MediaType == MediaType.Audio)
        {
            return;
        }

        var members = playlist.GetLinkedChildren().Select(c => c.Id).Distinct().ToList();
        _members[id] = members;
        foreach (var m in members)
        {
            if (!_holders.TryGetValue(m, out var set))
            {
                _holders[m] = set = new HashSet<Guid>();
            }

            set.Add(id);
        }
    }

    private IReadOnlyList<PlaylistFactsOut> Work(User user, long sinceMs)
    {
        var since = DateTimeOffset.FromUnixTimeMilliseconds(sinceMs).UtcDateTime;
        var all = Recent(user);
        List<Played> recent;
        lock (all)
        {
            recent = all.Where(p => p.At >= since).ToList();
        }

        if (recent.Count == 0)
        {
            return Array.Empty<PlaylistFactsOut>();
        }

        // Each playlist holding something played lately (itself, or its show or season put in whole), latest first.
        var latest = new Dictionary<Guid, DateTime>();
        lock (_lock)
        {
            Refresh();
            foreach (var played in recent)
            {
                foreach (var id in new[] { (Guid?)played.Id, played.SeriesId, played.SeasonId }.OfType<Guid>())
                {
                    if (!_holders.TryGetValue(id, out var playlists))
                    {
                        continue;
                    }

                    foreach (var p in playlists)
                    {
                        if (!latest.TryGetValue(p, out var at) || played.At > at)
                        {
                            latest[p] = played.At;
                        }
                    }
                }
            }
        }

        return latest.OrderByDescending(p => p.Value)
            .Select(p => _library.GetItemById(p.Key) as Playlist)
            .Where(p => p is not null && p.IsVisible(user))
            .Take(20)
            .Select(p => Assemble(Shape(p!, user), States(user, user.Id)))
            .ToList();
    }

    /// <summary>[shape] with the watch state of each entry and episode from [states].</summary>
    public static PlaylistFactsOut Assemble(PlaylistShape shape, Func<Guid, WatchState> states) =>
        new(shape.Id, shape.Entries.Select(e =>
        {
            var w = states(e.Id);
            var episodes = e.Episodes?.Select(ep =>
            {
                var s = states(ep.Id);
                return new EpisodeFacts(ep.Id, ep.SeasonId, ep.Season, ep.Number, ep.Missing, s.Played, s.LastPlayedMs, s.PositionMs);
            }).ToList();
            return new EntryFacts(e.Id, e.Kind, e.SeriesId, e.SeasonId, e.Season, w.Played, w.LastPlayedMs, w.PositionMs, episodes);
        }).ToList());

    /// <summary>[playlist]'s shape for [user]: kept, read again when it or something in it changed (or after a while).</summary>
    private PlaylistShape Shape(Playlist playlist, User user)
    {
        var key = (user.Id, playlist.Id);
        if (_shapes.TryGetValue(key, out var kept) && DateTime.UtcNow - kept.Read < ShapeKept)
        {
            return kept;
        }

        var shape = new PlaylistShape(playlist.Id, playlist.GetChildren(user, true, new InternalItemsQuery(user)).Select(e => EntryShape(e, user)).ToList(), DateTime.UtcNow);
        _shapes[key] = shape;
        return shape;
    }

    private EntryShape EntryShape(BaseItem item, User user)
    {
        IReadOnlyList<EpisodeShape>? episodes = item is Series or Season
            ? _library.GetItemList(new InternalItemsQuery(user)
            {
                AncestorIds = new[] { item.Id },
                IncludeItemTypes = new[] { BaseItemKind.Episode },
                Recursive = true,
                OrderBy = new[] { (ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending) },
            }).OfType<Episode>().Select(e => new EpisodeShape(e.Id, e.SeasonId, e.ParentIndexNumber, e.IndexNumber, e.IsVirtualItem)).ToList()
            : null;
        return item switch
        {
            Episode e => new EntryShape(e.Id, "Episode", e.SeriesId, e.SeasonId, e.ParentIndexNumber, null),
            Season s => new EntryShape(s.Id, "Season", s.SeriesId, null, s.IndexNumber, episodes),
            _ => new EntryShape(item.Id, item.GetBaseItemKind().ToString(), null, null, null, episodes),
        };
    }

    /// <summary>[user]'s watch state per title: read once each, then followed from the watch events.</summary>
    private Func<Guid, WatchState> States(User user, Guid userId)
    {
        var states = _states.GetOrAdd(userId, _ => new WatchStates());
        return id => states.Get(id, () => State(id, user));
    }

    private WatchState State(Guid id, User user)
    {
        var item = _library.GetItemById(id);
        var data = item is null ? null : _userData.GetUserData(user, item);
        return WatchState.Of(data?.Played == true, data?.LastPlayedDate, data?.PlaybackPositionTicks ?? 0);
    }

    /// <summary>What [user] played lately, most recent first: read once, then followed from the watch events.</summary>
    private List<Played> Recent(User user)
    {
        return _recent.GetOrAdd(user.Id, _ => _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode, BaseItemKind.Video },
                Recursive = true,
                IsVirtualItem = false,
                OrderBy = new[] { (ItemSortBy.DatePlayed, SortOrder.Descending) },
                Limit = RecentKept,
            })
            .Select(i => (Item: i, At: _userData.GetUserData(user, i)?.LastPlayedDate))
            .Where(p => p.At is not null)
            .Select(p => new Played(p.Item.Id, (p.Item as Episode)?.SeriesId, (p.Item as Episode)?.SeasonId, DateTime.SpecifyKind(p.At!.Value, DateTimeKind.Utc)))
            .ToList());
    }

    private void OnItem(object? sender, ItemChangeEventArgs e)
    {
        if (e.Item is Playlist)
        {
            lock (_lock)
            {
                _dirty.Add(e.Item.Id);
            }

            Forget(new[] { e.Item.Id });
            return;
        }

        // An episode added, replaced or renumbered changes the shape of every playlist holding it,
        // its season or its show; anything else in a playlist changes that playlist's shape.
        var ids = new[] { (Guid?)e.Item.Id, (e.Item as Episode)?.SeriesId, (e.Item as Episode)?.SeasonId, (e.Item as Season)?.SeriesId }.OfType<Guid>();
        List<Guid> playlists;
        lock (_lock)
        {
            playlists = ids.SelectMany(id => _holders.TryGetValue(id, out var p) ? p : Enumerable.Empty<Guid>()).Distinct().ToList();
        }

        Forget(playlists);
    }

    private void Forget(IReadOnlyCollection<Guid> playlists)
    {
        if (playlists.Count == 0)
        {
            return;
        }

        foreach (var key in _shapes.Keys.Where(k => playlists.Contains(k.Playlist)).ToList())
        {
            _shapes.TryRemove(key, out _);
        }
    }

    private void OnUserData(object? sender, UserDataSaveEventArgs e)
    {
        if (e.Item is not null && e.UserData is { } saved && _states.TryGetValue(e.UserId, out var states))
        {
            states.Put(e.Item.Id, WatchState.Of(saved.Played, saved.LastPlayedDate, saved.PlaybackPositionTicks));
        }

        if (!_recent.TryGetValue(e.UserId, out var list) || e.UserData?.LastPlayedDate is not { } at || e.Item is null)
        {
            return;
        }

        lock (list)
        {
            list.RemoveAll(p => p.Id == e.Item.Id);
            list.Insert(0, new Played(e.Item.Id, (e.Item as Episode)?.SeriesId, (e.Item as Episode)?.SeasonId, DateTime.SpecifyKind(at, DateTimeKind.Utc)));
            if (list.Count > RecentKept)
            {
                list.RemoveRange(RecentKept, list.Count - RecentKept);
            }
        }
    }

    private sealed record Played(Guid Id, Guid? SeriesId, Guid? SeasonId, DateTime At);
}

/// <summary>A person's watch state for one title.</summary>
/// <param name="Played">Watched.</param>
/// <param name="LastPlayedMs">When last played, epoch ms.</param>
/// <param name="PositionMs">Where it was left, ms.</param>
public record WatchState(bool Played, long? LastPlayedMs, long PositionMs)
{
    /// <summary>From the server's own user data.</summary>
    public static WatchState Of(bool played, DateTime? lastPlayed, long positionTicks) => new(
        played,
        lastPlayed is { } d ? new DateTimeOffset(DateTime.SpecifyKind(d, DateTimeKind.Utc)).ToUnixTimeMilliseconds() : null,
        positionTicks / TimeSpan.TicksPerMillisecond);
}

/// <summary>
/// One person's watch state per title: each read once, then replaced by what the watch events say.
/// A read racing a save never wins over it (an event's state is always put; a read only fills a gap).
/// </summary>
public class WatchStates
{
    /// <summary>Titles kept before all are let go (read again as needed).</summary>
    public const int MaxKept = 50_000;

    private readonly ConcurrentDictionary<Guid, WatchState> _states = new();

    /// <summary>[id]'s state, read with [read] the first time.</summary>
    public WatchState Get(Guid id, Func<WatchState> read)
    {
        if (_states.TryGetValue(id, out var kept))
        {
            return kept;
        }

        if (_states.Count >= MaxKept)
        {
            _states.Clear();
        }

        return _states.GetOrAdd(id, _ => read());
    }

    /// <summary>[id]'s state as just saved.</summary>
    public void Put(Guid id, WatchState state) => _states[id] = state;
}

/// <summary>A playlist's entries without anyone's watch state, as one person sees them.</summary>
/// <param name="Id">The playlist.</param>
/// <param name="Entries">Its entries in its own order.</param>
/// <param name="Read">When it was read.</param>
public record PlaylistShape(Guid Id, IReadOnlyList<EntryShape> Entries, DateTime Read);

/// <summary>One entry of a <see cref="PlaylistShape"/> (see <see cref="EntryFacts"/>).</summary>
/// <param name="Id">The title, show or season.</param>
/// <param name="Kind">Jellyfin's item kind.</param>
/// <param name="SeriesId">An episode's or season's show.</param>
/// <param name="SeasonId">An episode's season.</param>
/// <param name="Season">An episode's or season's number.</param>
/// <param name="Episodes">A show's or season's episodes in the show's order.</param>
public record EntryShape(Guid Id, string Kind, Guid? SeriesId, Guid? SeasonId, int? Season, IReadOnlyList<EpisodeShape>? Episodes);

/// <summary>One episode of a show or season entry (see <see cref="EpisodeFacts"/>).</summary>
/// <param name="Id">The episode.</param>
/// <param name="SeasonId">Its season.</param>
/// <param name="Season">Its season number.</param>
/// <param name="Number">Its episode number.</param>
/// <param name="Missing">Known but not in the library.</param>
public record EpisodeShape(Guid Id, Guid? SeasonId, int? Season, int? Number, bool Missing);

