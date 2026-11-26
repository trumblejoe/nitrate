using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Images;

namespace Nitrate.Companion.Api;

/// <summary>
/// Library images overview (admins): every film, show, season, episode and collection's images,
/// their files' sizes, and what's missing, small, the wrong shape or gone, by the app's guide.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Images")]
[Produces(MediaTypeNames.Application.Json)]
public class ImagesController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";

    /// <summary>Kinds always looked at (besides those the app names in its expectations).</summary>
    private static readonly BaseItemKind[] DefaultKinds = { BaseItemKind.Movie, BaseItemKind.Series, BaseItemKind.Season, BaseItemKind.Episode, BaseItemKind.BoxSet, BaseItemKind.MusicVideo, BaseItemKind.Video };

    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly ImageFiles _files;

    /// <summary>Initializes a new instance of the <see cref="ImagesController"/> class.</summary>
    public ImagesController(ILibraryManager library, IUserManager users, ImageFiles files)
    {
        _library = library;
        _users = users;
        _files = files;
    }

    /// <summary>The overview of one library's images (or every library's), by the guide in the body.</summary>
    [HttpPost("Overview")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<ImageOverview>> Overview([FromBody] ImageOverviewPost post, CancellationToken cancellationToken)
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        if (!Guid.TryParse(claim, out var id) || _users.GetUserById(id) is not { } user)
        {
            return Unauthorized();
        }

        if (!user.HasPermission(PermissionKind.IsAdministrator))
        {
            return StatusCode(StatusCodes.Status403Forbidden);
        }

        var kinds = DefaultKinds.ToHashSet();
        foreach (var name in post.Expected?.Keys ?? Enumerable.Empty<string>())
        {
            if (Enum.TryParse<BaseItemKind>(name, true, out var kind))
            {
                kinds.Add(kind);
            }
        }

        return await Task.Run(
            () =>
            {
                var items = _library.GetItemList(new InternalItemsQuery(user)
                {
                    IncludeItemTypes = kinds.ToArray(),
                    Recursive = true,
                    IsVirtualItem = false,
                    ParentId = post.LibraryId ?? Guid.Empty,
                    DtoOptions = new DtoOptions(false) { EnableImages = true },
                });
                var survey = items.Select(Of).Where(_ => !cancellationToken.IsCancellationRequested);
                return Ok(ImageSurvey.Run(survey, post, _files.Of));
            },
            cancellationToken).ConfigureAwait(false);
    }

    private static SurveyItem Of(BaseItem item)
    {
        var images = item.ImageInfos
            .Where(i => i.Type != MediaBrowser.Model.Entities.ImageType.Chapter && !string.IsNullOrEmpty(i.Path))
            .GroupBy(i => i.Type)
            .SelectMany(g => g.Select((i, index) => new SurveyImage(i.Type.ToString(), index, i.Path, i.Width, i.Height, i.IsLocalFile, i.DateModified)))
            .ToList();
        return item switch
        {
            Episode e => new SurveyItem(item.Id, item.Name ?? string.Empty, "Episode", e.SeriesName, e.ParentIndexNumber, e.IndexNumber, images),
            Season s => new SurveyItem(item.Id, item.Name ?? string.Empty, "Season", s.SeriesName, s.IndexNumber, null, images),
            _ => new SurveyItem(item.Id, item.Name ?? string.Empty, item.GetBaseItemKind().ToString(), null, null, null, images),
        };
    }
}
