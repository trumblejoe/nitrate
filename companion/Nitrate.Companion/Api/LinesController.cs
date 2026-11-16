using System.Globalization;
using System.Net.Mime;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Lines;

namespace Nitrate.Companion.Api;

/// <summary>A line someone says, where, and in what.</summary>
public record SpokenLineOut(
    Guid ItemId,
    string Kind,
    string Name,
    Guid? SeriesId,
    string? SeriesName,
    int? Season,
    int? Episode,
    int? Year,
    long StartMs,
    long EndMs,
    string Text,
    string? Language,
    string? Before,
    string? After);

/// <summary>How much is indexed.</summary>
public record LinesStatus(int Titles, long Lines, bool Enabled);

/// <summary>"Search what people say" (see <see cref="LineIndex"/>).</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Lines")]
[Produces(MediaTypeNames.Application.Json)]
public class LinesController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly LineIndex _index;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="LinesController"/> class.</summary>
    public LinesController(LineIndex index, ILibraryManager library, IUserManager users)
    {
        _index = index;
        _library = library;
        _users = users;
    }

    /// <summary>Lines with the words of <paramref name="query"/> in that order, in titles the caller can see.</summary>
    [HttpGet]
    public ActionResult<IReadOnlyList<SpokenLineOut>> Search([FromQuery] string query, [FromQuery] int limit = 40)
    {
        if (!Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var userId) || _users.GetUserById(userId) is not { } user)
        {
            return Forbid();
        }

        if (!(Plugin.Instance?.Configuration.EnableLines ?? true))
        {
            return Array.Empty<SpokenLineOut>();
        }

        var items = new Dictionary<Guid, BaseItem?>();
        BaseItem? Item(Guid id)
        {
            if (!items.TryGetValue(id, out var item))
            {
                item = _library.GetItemById(id);
                items[id] = item;
            }

            return item;
        }

        var hits = _index.Search(query ?? string.Empty, Math.Clamp(limit, 1, 100), id => Item(id) is { } i && i.IsVisibleStandalone(user));
        return hits.Select(h =>
        {
            var item = Item(h.ItemId)!;
            var episode = item as Episode;
            return new SpokenLineOut(
                h.ItemId,
                item.GetBaseItemKind().ToString(),
                item.Name,
                episode?.SeriesId,
                episode?.SeriesName,
                episode?.ParentIndexNumber,
                episode?.IndexNumber,
                item.ProductionYear,
                h.StartMs,
                h.EndMs,
                h.Text,
                h.Language,
                h.Before,
                h.After);
        }).ToList();
    }

    /// <summary>How much is indexed so far.</summary>
    [HttpGet("Status")]
    public ActionResult<LinesStatus> Status()
    {
        var (titles, lines) = _index.Count();
        return new LinesStatus(titles, lines, Plugin.Instance?.Configuration.EnableLines ?? true);
    }
}
