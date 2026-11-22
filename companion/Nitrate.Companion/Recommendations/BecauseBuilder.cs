using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Recommendations;

/// <summary>
/// "Make Because you watched" (Dashboard → Scheduled tasks, nightly): works out each person's
/// "Because you watched" rows inside the server, from its database only (play dates and the
/// server's own similar-titles search; no media file is read), kept in memory, so the apps read
/// them in one cheap request instead of asking the costly similar search themselves. After a
/// restart they're made again in the background; someone asking before theirs is ready gets none
/// that once (and theirs made then), never a wait.
/// </summary>
public class BecauseBuilder : IScheduledTask
{
    /// <summary>Recently watched titles looked at for seeds.</summary>
    public const int Recent = 12;

    /// <summary>Rows older than this are made again in the background when asked for (the nightly task normally does it first).</summary>
    public static readonly TimeSpan MaxAge = TimeSpan.FromHours(30);

    private readonly BecauseStore _store;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IUserDataManager _userData;
    private readonly ISimilarItemsManager _similar;
    private readonly ILogger<BecauseBuilder> _log;
    private readonly SemaphoreSlim _one = new(1, 1);

    /// <summary>Initializes a new instance of the <see cref="BecauseBuilder"/> class.</summary>
    public BecauseBuilder(BecauseStore store, ILibraryManager library, IUserManager users, IUserDataManager userData, ISimilarItemsManager similar, ILogger<BecauseBuilder> log)
    {
        _store = store;
        _library = library;
        _users = users;
        _userData = userData;
        _similar = similar;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Make \"Because you watched\"";

    /// <inheritdoc />
    public string Key => "NitrateBecauseYouWatched";

    /// <inheritdoc />
    public string Description => "Works out each person's \"Because you watched\" rows overnight from the server's database (no media files are read), so Nitrate's Home doesn't ask for them as it opens.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(2).Ticks },
    };

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var users = _users.GetUsers().ToList();
        var done = 0;
        foreach (var user in users)
        {
            cancellationToken.ThrowIfCancellationRequested();
            progress.Report(100.0 * done++ / Math.Max(1, users.Count));
            try
            {
                await BuildAsync(user, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                _log.LogWarning("Because you watched: couldn't make {User}'s rows: {Message}", user.Username, e.Message);
            }
        }
    }

    /// <summary>
    /// The person's rows as kept. None yet (the server just started) or too old: they're made in
    /// the background and what's kept (maybe nothing) is answered now, so Home never waits for the
    /// similar-titles search.
    /// </summary>
    public BecauseSet For(User user)
    {
        var kept = _store.Get(user.Id);
        if (kept == null || DateTimeOffset.UtcNow - kept.Built >= MaxAge)
        {
            BuildSoon(user);
        }

        return kept ?? new BecauseSet(DateTimeOffset.MinValue, Array.Empty<BecauseRow>());
    }

    /// <summary>Makes the person's rows in the background (once at a time per person).</summary>
    public void BuildSoon(User user)
    {
        if (!Building.TryAdd(user.Id, true))
        {
            return;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await BuildAsync(user, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                _log.LogWarning("Because you watched: couldn't make {User}'s rows: {Message}", user.Username, e.Message);
            }
            finally
            {
                Building.TryRemove(user.Id, out _);
            }
        });
    }

    /// <summary>Makes the rows now for everyone who watched something lately, one person at a time.</summary>
    public async Task BuildRecentAsync(TimeSpan within, CancellationToken cancellationToken)
    {
        foreach (var user in _users.GetUsers().ToList())
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (_store.Get(user.Id) != null || !WatchedWithin(user, within))
            {
                continue;
            }

            try
            {
                await BuildAsync(user, cancellationToken).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                _log.LogWarning("Because you watched: couldn't make {User}'s rows: {Message}", user.Username, e.Message);
            }
        }
    }

    /// <summary>Whether the person finished or played anything within [within].</summary>
    private bool WatchedWithin(User user, TimeSpan within)
    {
        var last = _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode },
            Recursive = true,
            IsPlayed = true,
            OrderBy = new[] { (ItemSortBy.DatePlayed, SortOrder.Descending) },
            Limit = 1,
            DtoOptions = new DtoOptions(false) { EnableImages = false },
        }).FirstOrDefault();
        var at = last is null ? null : _userData.GetUserData(user, last)?.LastPlayedDate;
        return at != null && DateTime.UtcNow - at.Value <= within;
    }

    /// <summary>People whose rows are being made right now (shared by every copy of the builder).</summary>
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, bool> Building = new();

    /// <summary>Whether the person has watched [id].</summary>
    public bool Watched(User user, Guid id) => _library.GetItemById(id) is { } item && _userData.GetUserData(user, item)?.Played == true;

    private async Task<BecauseSet> BuildAsync(User user, CancellationToken cancellationToken)
    {
        await _one.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var recent = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Episode },
                Recursive = true,
                IsPlayed = true,
                IsVirtualItem = false,
                OrderBy = new[] { (ItemSortBy.DatePlayed, SortOrder.Descending) },
                Limit = Recent,
                DtoOptions = new DtoOptions(false) { EnableImages = false },
            });
            var seeds = BecauseRows.Seeds(
                recent.Select(i => i is Episode e && e.SeriesId != Guid.Empty ? new BecauseSeed(e.SeriesId, e.SeriesName ?? e.Name) : new BecauseSeed(i.Id, i.Name)),
                BecauseRows.Rows * 3);
            var rows = await BecauseRows.BuildAsync(
                seeds,
                async seed =>
                {
                    var item = _library.GetItemById(seed.Id);
                    if (item == null)
                    {
                        return Array.Empty<Guid>();
                    }

                    var like = await _similar.GetSimilarItemsAsync(item, Array.Empty<Guid>(), user, new DtoOptions(false) { EnableImages = false }, BecauseRows.Keep + 10, _library.GetLibraryOptions(item), cancellationToken).ConfigureAwait(false);
                    return like.Select(i => i.Id).ToList();
                },
                id => Watched(user, id)).ConfigureAwait(false);
            var set = new BecauseSet(DateTimeOffset.UtcNow, rows);
            _store.Put(user.Id, set);
            return set;
        }
        finally
        {
            _one.Release();
        }
    }
}
