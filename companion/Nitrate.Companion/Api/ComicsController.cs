using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Comics;

namespace Nitrate.Companion.Api;

/// <summary>
/// Books read page by page: the apps show the first page at once and fetch the rest in one burst,
/// and CBR, CB7 and CBT work on devices with no way to open them.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Comics")]
[Produces(MediaTypeNames.Application.Json)]
public class ComicsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly ComicStore _store;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="ComicsController"/> class.</summary>
    public ComicsController(ComicStore store, ILibraryManager library, IUserManager users)
    {
        _store = store;
        _library = library;
        _users = users;
    }

    /// <summary>The book's format, its pages (names, sizes) and its ComicInfo.xml.</summary>
    [HttpGet("{itemId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<ComicListing>> List([FromRoute] Guid itemId)
    {
        if (Book(itemId) is not { } path)
        {
            return NotFound();
        }

        try
        {
            return await _store.ListAsync(itemId.ToString("N"), path, HttpContext.RequestAborted).ConfigureAwait(false);
        }
        catch (Exception e) when (e is InvalidDataException or IOException or InvalidOperationException or NotSupportedException)
        {
            return Problem("The book's file couldn't be read: " + e.Message, statusCode: StatusCodes.Status422UnprocessableEntity);
        }
    }

    /// <summary>One page's picture (0-based).</summary>
    [HttpGet("{itemId}/Pages/{index}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> Page([FromRoute] Guid itemId, [FromRoute] int index)
    {
        if (Book(itemId) is not { } path)
        {
            return NotFound();
        }

        var page = await _store.PageAsync(itemId.ToString("N"), path, index, HttpContext.RequestAborted).ConfigureAwait(false);
        if (page is not { } p)
        {
            return NotFound();
        }

        Response.Headers.CacheControl = "private, max-age=86400";
        return File(p.Data, p.ContentType);
    }

    /// <summary>The book's file when the caller may see it and the feature is on.</summary>
    private string? Book(Guid itemId)
    {
        if (!(Plugin.Instance?.Configuration.EnableComics ?? true)
            || !Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var me)
            || _users.GetUserById(me) is not { } user)
        {
            return null;
        }

        var item = _library.GetItemById(itemId);
        return item is not null && item.IsVisibleStandalone(user) && !string.IsNullOrEmpty(item.Path) && System.IO.File.Exists(item.Path) ? item.Path : null;
    }
}
