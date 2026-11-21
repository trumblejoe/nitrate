using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.IO;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Comics;

namespace Nitrate.Companion.Api;

/// <summary>A book's ComicInfo.xml after a change.</summary>
public record ComicInfoOut(string ComicInfo);

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
    private readonly IProviderManager _providers;
    private readonly IFileSystem _fileSystem;

    /// <summary>Initializes a new instance of the <see cref="ComicsController"/> class.</summary>
    public ComicsController(ComicStore store, ILibraryManager library, IUserManager users, IProviderManager providers, IFileSystem fileSystem)
    {
        _store = store;
        _library = library;
        _users = users;
        _providers = providers;
        _fileSystem = fileSystem;
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

    /// <summary>
    /// Changes the book's ComicInfo.xml (admins; CBZ only): each field given is set, or removed when
    /// empty. Jellyfin reads the book again afterwards.
    /// </summary>
    [HttpPut("{itemId}/Info")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<ComicInfoOut> SetInfo([FromRoute] Guid itemId, [FromBody] Dictionary<string, string?> changes)
    {
        if (!Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var me) || _users.GetUserById(me) is not { } user
            || !user.HasPermission(PermissionKind.IsAdministrator))
        {
            return StatusCode(StatusCodes.Status403Forbidden);
        }

        if (Book(itemId) is not { } path || _library.GetItemById(itemId) is not { } item)
        {
            return NotFound();
        }

        try
        {
            var xml = ComicInfoEditor.Apply(path, changes);
            _providers.QueueRefresh(
                item.Id,
                new MetadataRefreshOptions(new DirectoryService(_fileSystem)) { MetadataRefreshMode = MetadataRefreshMode.FullRefresh, ReplaceAllMetadata = true },
                RefreshPriority.High);
            return new ComicInfoOut(xml);
        }
        catch (ComicInfoException e)
        {
            return Problem(e.Message, statusCode: StatusCodes.Status400BadRequest);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Xml.XmlException or InvalidDataException)
        {
            return Problem("The book couldn't be changed: " + e.Message, statusCode: StatusCodes.Status409Conflict);
        }
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
