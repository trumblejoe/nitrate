using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Catalogs;
using Nitrate.Companion.Comics;

namespace Nitrate.Companion.Api;

/// <summary>Where someone is in a catalog book (a page; for an EPUB thousandths of it).</summary>
public record CatalogPosition(int? Page);

/// <summary>
/// The comic catalogs the admin set up (OPDS, CLU), reached through the plugin: it signs in for
/// everyone, so passwords and tokens stay on the server, and it serves books page by page (CBR
/// too) the way it does the server's own. Where each person is in a book is kept here.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Catalogs")]
[Produces(MediaTypeNames.Application.Json)]
public class CatalogsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly CatalogFetcher _fetcher;
    private readonly CatalogPositions _positions;
    private readonly ComicStore _store;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="CatalogsController"/> class.</summary>
    public CatalogsController(CatalogFetcher fetcher, CatalogPositions positions, ComicStore store, IUserManager users)
    {
        _fetcher = fetcher;
        _positions = positions;
        _store = store;
        _users = users;
    }

    private static IReadOnlyList<CatalogSettings> Catalogs =>
        (Plugin.Instance?.Configuration.EnableComics ?? true) ? CatalogAccess.Usable(Plugin.Instance?.Configuration.ComicCatalogs) : [];

    /// <summary>The catalogs everyone can read (never their sign-in details).</summary>
    [HttpGet]
    public ActionResult<IReadOnlyList<CatalogInfo>> List() =>
        Me() is null ? Forbid() : Ok(Catalogs.Select(CatalogAccess.Public).ToList());

    /// <summary>Anything on the catalog's own server (a feed, a cover, a streamed page), fetched with its sign-in.</summary>
    [HttpGet("{catalogId}/Fetch")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> Fetch([FromRoute] string catalogId, [FromQuery] string url)
    {
        if (Find(catalogId) is not { } catalog)
        {
            return NotFound();
        }

        try
        {
            var response = await _fetcher.GetAsync(catalog, url, HttpContext.RequestAborted).ConfigureAwait(false);
            HttpContext.Response.RegisterForDispose(response);
            var status = (int)response.StatusCode;
            if (!response.IsSuccessStatusCode)
            {
                // The app's own sign-in is fine: a refusal is the catalog's, said in words.
                return Problem(
                    status is 401 or 403 ? "The catalog didn't accept the sign-in details set up on the server." : $"The catalog answered {status}.",
                    statusCode: status == 404 ? 404 : 502);
            }

            Response.Headers.CacheControl = "private, max-age=600";
            if (response.Content.Headers.ContentLength is { } length)
            {
                Response.ContentLength = length;
            }

            var stream = await response.Content.ReadAsStreamAsync(HttpContext.RequestAborted).ConfigureAwait(false);
            return File(stream, response.Content.Headers.ContentType?.ToString() ?? "application/octet-stream");
        }
        catch (CatalogFetchException e)
        {
            return Problem(e.Message, statusCode: e.Status);
        }
    }

    /// <summary>A catalog book's format and pages (fetched whole onto the server once, then read like the server's own).</summary>
    [HttpGet("{catalogId}/Book")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<ComicListing>> Book([FromRoute] string catalogId, [FromQuery] string url)
    {
        if (Find(catalogId) is not { } catalog)
        {
            return NotFound();
        }

        try
        {
            var (path, key) = await _fetcher.BookAsync(catalog, url, HttpContext.RequestAborted).ConfigureAwait(false);
            return await _store.ListAsync(key, path, HttpContext.RequestAborted).ConfigureAwait(false);
        }
        catch (CatalogFetchException e)
        {
            return Problem(e.Message, statusCode: e.Status);
        }
        catch (Exception e) when (e is InvalidDataException or IOException or InvalidOperationException or NotSupportedException)
        {
            return Problem("The book's file couldn't be read: " + e.Message, statusCode: StatusCodes.Status422UnprocessableEntity);
        }
    }

    /// <summary>One page of a catalog book (0-based).</summary>
    [HttpGet("{catalogId}/Book/Pages/{index}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> Page([FromRoute] string catalogId, [FromRoute] int index, [FromQuery] string url)
    {
        if (Find(catalogId) is not { } catalog)
        {
            return NotFound();
        }

        try
        {
            var (path, key) = await _fetcher.BookAsync(catalog, url, HttpContext.RequestAborted).ConfigureAwait(false);
            if (await _store.PageAsync(key, path, index, HttpContext.RequestAborted).ConfigureAwait(false) is not { } page)
            {
                return NotFound();
            }

            Response.Headers.CacheControl = "private, max-age=86400";
            return File(page.Data, page.ContentType);
        }
        catch (CatalogFetchException e)
        {
            return Problem(e.Message, statusCode: e.Status);
        }
    }

    /// <summary>A catalog book's whole file (PDF, EPUB), from the copy the server fetched.</summary>
    [HttpGet("{catalogId}/Book/File")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> BookFile([FromRoute] string catalogId, [FromQuery] string url)
    {
        if (Find(catalogId) is not { } catalog)
        {
            return NotFound();
        }

        try
        {
            var (path, _) = await _fetcher.BookAsync(catalog, url, HttpContext.RequestAborted).ConfigureAwait(false);
            return PhysicalFile(path, "application/octet-stream");
        }
        catch (CatalogFetchException e)
        {
            return Problem(e.Message, statusCode: e.Status);
        }
    }

    /// <summary>Where the caller is in a book.</summary>
    [HttpGet("{catalogId}/Position")]
    public ActionResult<CatalogPosition> Position([FromRoute] string catalogId, [FromQuery] string book)
    {
        if (Me() is not { } me || Find(catalogId) is null)
        {
            return NotFound();
        }

        return new CatalogPosition(_positions.Get(me, catalogId, book));
    }

    /// <summary>Remembers where the caller is in a book.</summary>
    [HttpPut("{catalogId}/Position")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult SetPosition([FromRoute] string catalogId, [FromQuery] string book, [FromBody] CatalogPosition position)
    {
        if (Me() is not { } me || Find(catalogId) is null || position.Page is not { } page || string.IsNullOrEmpty(book) || book.Length > 2000)
        {
            return NotFound();
        }

        _positions.Set(me, catalogId, book, page);
        return NoContent();
    }

    private Guid? Me() =>
        Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var me) && _users.GetUserById(me) is not null ? me : null;

    private CatalogSettings? Find(string catalogId) =>
        Me() is null ? null : Catalogs.FirstOrDefault(c => c.Id == catalogId);
}
