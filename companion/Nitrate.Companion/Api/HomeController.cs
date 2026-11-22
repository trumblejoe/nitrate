using System.Net.Mime;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
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
public record HomeOut(IReadOnlyList<BaseItemDto>? Resume, IReadOnlyList<BaseItemDto>? NextUp, IReadOnlyList<LatestOut> Latest, IReadOnlyList<BecauseOut>? Because);

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
    private readonly BecauseBuilder _because;
    private readonly FormatIndex _formats;

    /// <summary>Initializes a new instance of the <see cref="HomeController"/> class.</summary>
    public HomeController(ILibraryManager library, IUserManager users, IDtoService dto, ITVSeriesManager tv, BecauseBuilder because, FormatIndex formats)
    {
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
        return Ok(new HomeOut(resumeOut, nextOut, latestOut, becauseOut));
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
