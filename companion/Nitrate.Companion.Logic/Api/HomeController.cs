using System.Net.Mime;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.TV;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Querying;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Formats;
using Nitrate.Companion.Recommendations;

namespace Nitrate.Companion.Api;

/// <summary>One "Because you watched" row: its seed's name and the titles, as the server's own items.</summary>
/// <param name="SeedName">What it's based on.</param>
/// <param name="Items">The titles, not yet watched.</param>
public record BecauseOut(string SeedName, IReadOnlyList<BaseItemDto> Items);

/// <summary>One library's newest titles.</summary>
/// <param name="LibraryId">The library.</param>
/// <param name="Items">Its newest titles.</param>
public record LatestOut(Guid LibraryId, IReadOnlyList<BaseItemDto> Items);

/// <summary>Home's heaviest rows together (each only when asked for).</summary>
/// <param name="Resume">Continue Watching.</param>
/// <param name="NextUp">Next Up.</param>
/// <param name="Latest">"Latest in…" for each library asked for.</param>
/// <param name="Because">"Because you watched" (made overnight).</param>
/// <param name="Reading">Books under way (Continue Reading), with [reading].</param>
/// <param name="ReadingDone">Books finished lately (Continue Reading carries on a series from them), with [reading].</param>
/// <param name="Listening">Audiobooks under way (Continue Listening), with [listening].</param>
public record HomeOut(
    IReadOnlyList<BaseItemDto>? Resume,
    IReadOnlyList<BaseItemDto>? NextUp,
    IReadOnlyList<LatestOut> Latest,
    IReadOnlyList<BecauseOut>? Because,
    IReadOnlyList<BaseItemDto>? Reading = null,
    IReadOnlyList<BaseItemDto>? ReadingDone = null,
    IReadOnlyList<BaseItemDto>? Listening = null);

/// <summary>
/// Home in one request, "Because you watched" made overnight, and what each title is made of for
/// format filters: answered inside the server from its database, with the same rules as Jellyfin's
/// own endpoints, so the apps make one trip instead of ten and never ask the costly similar search.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class HomeController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";

    /// <summary>The fields a card reads (as the apps ask for them).</summary>
    private static readonly ItemFields[] CardFields = { ItemFields.Overview, ItemFields.PrimaryImageAspectRatio, ItemFields.Genres };

    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IDtoService _dto;
    private readonly ITVSeriesManager _tv;
    private readonly BecauseRowsBuilder _because;
    private readonly FormatIndex _formats;
    private readonly IUserDataManager _userData;
    private readonly Nitrate.Companion.Changes.ChangeCounters _changes;
    private readonly BecauseStore _becauseStore;

    /// <summary>Initializes a new instance of the <see cref="HomeController"/> class.</summary>
    public HomeController(ILibraryManager library, IUserManager users, IDtoService dto, ITVSeriesManager tv, BecauseRowsBuilder because, FormatIndex formats, IUserDataManager userData, Nitrate.Companion.Changes.ChangeCounters changes, BecauseStore becauseStore)
    {
        _changes = changes;
        _becauseStore = becauseStore;
        _userData = userData;
        _library = library;
        _users = users;
        _dto = dto;
        _tv = tv;
        _because = because;
        _formats = formats;
    }

    /// <summary>The caller's "Because you watched" rows: [limit] titles each, watched ones left out.</summary>
    [HttpGet("Because")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<BecauseOut>>> Because([FromQuery] int limit = 16, CancellationToken cancellationToken = default) =>
        Me() is { } user ? Ok(await BecauseFor(user, limit, cancellationToken).ConfigureAwait(false)) : Unauthorized();

    /// <summary>
    /// Home's rows in one answer: Continue Watching and Next Up when asked for, "Latest in…" for each
    /// of [latest] ("&lt;library id&gt;:Movie" or ":Series", newest first) and "Because you watched".
    /// </summary>
    [HttpGet("Home")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<HomeOut>> Home(
        [FromQuery] bool resume = true,
        [FromQuery] bool nextUp = true,
        [FromQuery] bool because = true,
        [FromQuery] string? latest = null,
        [FromQuery] int limit = 24,
        [FromQuery] bool reading = false,
        [FromQuery] bool listening = false,
        CancellationToken cancellationToken = default)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        var options = Options();
        IReadOnlyList<BaseItemDto>? resumeOut = null;
        if (resume)
        {
            var items = _library.GetItemsResult(new InternalItemsQuery(user)
            {
                OrderBy = new[] { (ItemSortBy.DatePlayed, SortOrder.Descending) },
                IsResumable = true,
                Limit = limit,
                Recursive = true,
                DtoOptions = options,
                MediaTypes = new[] { MediaType.Video },
                IsVirtualItem = false,
                CollapseBoxSetItems = false,
                EnableTotalRecordCount = false,
            }).Items;
            resumeOut = Dtos(items, options, user);
        }

        IReadOnlyList<BaseItemDto>? nextOut = null;
        if (nextUp)
        {
            var next = _tv.GetNextUp(
                new NextUpQuery { User = user, Limit = limit, EnableResumable = true, EnableRewatching = false, NextUpDateCutoff = DateTime.MinValue, EnableTotalRecordCount = false },
                options);
            nextOut = Dtos(next.Items, options, user);
        }

        var latestOut = new List<LatestOut>();
        foreach (var (library, kind) in ParseLatest(latest))
        {
            var sort = kind == BaseItemKind.Series ? ItemSortBy.DateLastContentAdded : ItemSortBy.DateCreated;
            var items = _library.GetItemList(new InternalItemsQuery(user)
            {
                ParentId = library,
                IncludeItemTypes = new[] { kind },
                Recursive = true,
                IsVirtualItem = false,
                OrderBy = new[] { (sort, SortOrder.Descending), (ItemSortBy.SortName, SortOrder.Ascending) },
                Limit = limit,
                DtoOptions = options,
            });
            latestOut.Add(new LatestOut(library, Dtos(items, options, user)));
        }

        var becauseOut = because ? await BecauseFor(user, 16, cancellationToken).ConfigureAwait(false) : null;
        // Continue Reading and Listening, as the apps' own requests would have them (books sorted by date played).
        IReadOnlyList<BaseItemDto>? Played(BaseItemKind kind, bool? resumable, bool? played, int count) => Dtos(
            _library.GetItemsResult(new InternalItemsQuery(user)
            {
                IncludeItemTypes = new[] { kind },
                Recursive = true,
                IsResumable = resumable,
                IsPlayed = played,
                OrderBy = new[] { (ItemSortBy.DatePlayed, SortOrder.Descending) },
                Limit = count,
                DtoOptions = options,
                EnableTotalRecordCount = false,
            }).Items,
            options,
            user);
        return Ok(new HomeOut(
            resumeOut,
            nextOut,
            latestOut,
            becauseOut,
            reading ? Played(BaseItemKind.Book, true, null, limit) : null,
            reading ? Played(BaseItemKind.Book, null, true, 10) : null,
            listening ? Played(BaseItemKind.AudioBook, true, null, limit) : null));
    }

    /// <summary>
    /// What changed, as counters: an app keeps the rows it has (on the device, across runs) while the
    /// counters its rows depend on are the same as when it loaded them. One tiny answer, nothing read.
    /// </summary>
    [HttpGet("Changes")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<Nitrate.Companion.Changes.ChangesOut> Changes() =>
        Me() is { } user ? Ok(_changes.For(user.Id, _becauseStore.Get(user.Id)?.Built)) : Unauthorized();

    /// <summary>
    /// Home's "Continue your playlists": for each video playlist the caller watched from in the last 60
    /// days (most recent first), what carries on from the entry played last. Worked out here from each
    /// playlist's own list and the caller's play dates, so the apps never download whole playlists.
    /// </summary>
    [HttpGet("Home/Playlists")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<IReadOnlyList<BaseItemDto>> Playlists([FromQuery] int limit = 12)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        var seen = new Dictionary<Guid, BaseItem>();
        var lists = new List<IReadOnlyList<PlaylistEntry>>();
        var playlists = _library.GetItemList(new InternalItemsQuery(user) { IncludeItemTypes = new[] { BaseItemKind.Playlist }, Recursive = true })
            .OfType<MediaBrowser.Controller.Playlists.Playlist>()
            .Where(p => p.MediaType != MediaType.Audio)
            .Take(200);
        foreach (var playlist in playlists)
        {
            var entries = new List<PlaylistEntry>();
            var inIt = new HashSet<Guid>();
            foreach (var item in playlist.GetChildren(user, true, new InternalItemsQuery(user)).SelectMany(Expand))
            {
                if (!inIt.Add(item.Id))
                {
                    continue;
                }

                var data = _userData.GetUserData(user, item);
                seen[item.Id] = item;
                entries.Add(new PlaylistEntry(item.Id, data?.Played == true, data?.LastPlayedDate));
            }

            lists.Add(entries);
        }

        var ids = PlaylistPlaces.Continue(lists, DateTime.UtcNow, Math.Clamp(limit, 1, 50));
        var options = Options();
        return Ok(Dtos(ids.Select(id => seen[id]).ToList(), options, user));
    }

    /// <summary>
    /// A playlist entry as what's watched: a show or season put in whole is its episodes (specials and
    /// missing ones left out), films, episodes and videos are themselves, anything else is left out.
    /// </summary>
    private IEnumerable<BaseItem> Expand(BaseItem item)
    {
        if (item is Series or Season)
        {
            return _library.GetItemList(new InternalItemsQuery
            {
                AncestorIds = new[] { item.Id },
                IncludeItemTypes = new[] { BaseItemKind.Episode },
                Recursive = true,
                IsVirtualItem = false,
                OrderBy = new[] { (ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending) },
            }).OfType<Episode>().Where(e => (e.ParentIndexNumber ?? 1) > 0);
        }

        return item.GetBaseItemKind() is BaseItemKind.Movie or BaseItemKind.Episode or BaseItemKind.Video ? new[] { item } : Array.Empty<BaseItem>();
    }

    /// <summary>
    /// What every film and show (and other video) the caller can see in [parentId] is made of, for
    /// format filters: video codec, bit depth, HDR and Dolby Vision profile, and sound codecs.
    /// </summary>
    [HttpGet("Formats")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<IReadOnlyList<TitleFormats>> Formats([FromQuery] Guid? parentId, [FromQuery] string? types)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        var kinds = (types ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(t => Enum.TryParse<BaseItemKind>(t, true, out var k) ? k : (BaseItemKind?)null)
            .OfType<BaseItemKind>().ToList();
        return Ok(_formats.For(user, parentId, kinds));
    }

    /// <summary>"&lt;id&gt;:Movie,&lt;id&gt;:Series" into libraries and kinds (anything else left out).</summary>
    public static IReadOnlyList<(Guid Library, BaseItemKind Kind)> ParseLatest(string? latest) =>
        (latest ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(part => part.Split(':'))
            .Where(p => p.Length == 2 && Guid.TryParse(p[0], out _) && (p[1] == "Movie" || p[1] == "Series"))
            .Select(p => (Guid.Parse(p[0]), p[1] == "Movie" ? BaseItemKind.Movie : BaseItemKind.Series))
            .Distinct()
            .Take(20)
            .ToList();

    private async Task<IReadOnlyList<BecauseOut>> BecauseFor(User user, int limit, CancellationToken cancellationToken)
    {
        var set = _because.For(user);
        var options = Options();
        var ids = set.Rows.SelectMany(r => r.ItemIds).Distinct().ToArray();
        if (ids.Length == 0)
        {
            return Array.Empty<BecauseOut>();
        }

        // One read for every row's titles, as the person may see them now, with their watch state.
        var dtos = Dtos(_library.GetItemList(new InternalItemsQuery(user) { ItemIds = ids, DtoOptions = options }), options, user).ToDictionary(d => d.Id);
        return set.Rows
            .Select(r => new BecauseOut(
                r.SeedName,
                BecauseRows.Showing(r, id => !dtos.TryGetValue(id, out var d) || d.UserData?.Played == true, limit).Select(id => dtos[id]).ToList()))
            .Where(r => r.Items.Count > 0)
            .ToList();
    }

    private static DtoOptions Options() => new(false) { Fields = CardFields, EnableImages = true, EnableUserData = true };

    private IReadOnlyList<BaseItemDto> Dtos(IReadOnlyList<BaseItem> items, DtoOptions options, User user) =>
        items.Count == 0 ? Array.Empty<BaseItemDto>() : _dto.GetBaseItemDtos(items, options, user, null, false);

    private User? Me() => Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;
}
