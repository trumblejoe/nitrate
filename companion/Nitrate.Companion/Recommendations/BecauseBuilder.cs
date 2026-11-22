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
/// server's own similar-titles search; no media file is read), so the apps read them in one cheap
/// request instead of asking the costly similar search themselves. Someone without rows yet gets
/// them made on first asking.
/// </summary>
public class BecauseBuilder : IScheduledTask
{
    /// <summary>Recently watched titles looked at for seeds.</summary>
    public const int Recent = 12;

    /// <summary>Rows older than this are made again when asked for (the nightly task normally does it first).</summary>
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

    /// <summary>The person's rows, made now when there are none or they're too old.</summary>
    public async Task<BecauseSet> ForAsync(User user, CancellationToken cancellationToken)
    {
        var kept = _store.Get(user.Id);
        if (kept != null && DateTimeOffset.UtcNow - kept.Built < MaxAge)
        {
            return kept;
        }

        return await BuildAsync(user, cancellationToken).ConfigureAwait(false);
    }

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
