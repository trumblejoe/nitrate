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

/// <summary>One library's newest titles.</summary>
/// <param name="LibraryId">The library.</param>
/// <param name="Items">Its newest titles.</param>
public record LatestOut(Guid LibraryId, IReadOnlyList<BaseItemDto> Items);

/// <summary>Home's heaviest rows together (each only when asked for).</summary>
/// <param name="Resume">Continue Watching.</param>
/// <param name="NextUp">Next Up.</param>
/// <param name="Latest">"Latest in…" for each library asked for.</param>
/// <param name="Reading">Books under way (Continue Reading), with [reading].</param>
/// <param name="ReadingDone">Books finished lately (Continue Reading carries on a series from them), with [reading].</param>
/// <param name="Listening">Audiobooks under way (Continue Listening), with [listening].</param>
public record HomeOut(
    IReadOnlyList<BaseItemDto>? Resume,
    IReadOnlyList<BaseItemDto>? NextUp,
    IReadOnlyList<LatestOut> Latest,
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
    private readonly Nitrate.Companion.Changes.ChangeCounters _changes;
    private readonly BecauseStore _becauseStore;
    private readonly PlaylistFacts _playlistFacts;

    /// <summary>Initializes a new instance of the <see cref="HomeController"/> class.</summary>
    public HomeController(ILibraryManager library, IUserManager users, IDtoService dto, ITVSeriesManager tv, BecauseRowsBuilder because, FormatIndex formats, Nitrate.Companion.Changes.ChangeCounters changes, BecauseStore becauseStore, PlaylistFacts playlistFacts)
    {
        _playlistFacts = playlistFacts;
        _changes = changes;
        _becauseStore = becauseStore;
        _library = library;
        _users = users;
        _dto = dto;
        _tv = tv;
        _because = because;
        _formats = formats;
    }

    /// <summary>
    /// Facts for the caller's "Because you watched" (made overnight): what they played lately and the
    /// server's similar titles for the first few films and shows of it. The apps make the rows.
    /// None yet after a start: they're made in the background and an empty answer is given meanwhile.
    /// </summary>
    [HttpGet("Because/Facts")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<BecauseFacts> BecauseFacts() => Me() is { } user ? Ok(_because.For(user)) : Unauthorized();

    /// <summary>
    /// Home's rows in one answer: Continue Watching and Next Up when asked for, "Latest in…" for each
    /// of [latest] ("&lt;library id&gt;:Movie" or ":Series", newest first), Continue Reading and Listening.
    /// </summary>
    [HttpGet("Home")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<HomeOut>> Home(
        [FromQuery] bool resume = true,
        [FromQuery] bool nextUp = true,
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
    /// Facts about the playlists the caller is watching through, for the apps' own rules: playlists
    /// holding something they played since [since] (epoch ms), and what those hold with their watch
    /// state. Nothing is decided here (see <see cref="PlaylistFacts"/>).
    /// </summary>
    [HttpGet("Playlists/Watching")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<PlaylistFactsOut>>> PlaylistsWatching([FromQuery] long since, [FromQuery] int limit = 20, CancellationToken cancellationToken = default) =>
        Me() is { } user ? Ok(await _playlistFacts.ForAsync(user, since, Math.Clamp(limit, 1, 20), cancellationToken).ConfigureAwait(false)) : Unauthorized();

    /// <summary>"&lt;id&gt;:Movie,&lt;id&gt;:Series" into libraries and kinds (anything else left out).</summary>
    public static IReadOnlyList<(Guid Library, BaseItemKind Kind)> ParseLatest(string? latest) =>
        (latest ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(part => part.Split(':'))
            .Where(p => p.Length == 2 && Guid.TryParse(p[0], out _) && (p[1] == "Movie" || p[1] == "Series"))
            .Select(p => (Guid.Parse(p[0]), p[1] == "Movie" ? BaseItemKind.Movie : BaseItemKind.Series))
            .Distinct()
            .Take(20)
            .ToList();

    private static DtoOptions Options() => new(false) { Fields = CardFields, EnableImages = true, EnableUserData = true };

    private IReadOnlyList<BaseItemDto> Dtos(IReadOnlyList<BaseItem> items, DtoOptions options, User user) =>
        items.Count == 0 ? Array.Empty<BaseItemDto>() : _dto.GetBaseItemDtos(items, options, user, null, false);

    private User? Me() => Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;
}
