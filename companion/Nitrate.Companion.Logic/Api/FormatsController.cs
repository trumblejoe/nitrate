using System.Net.Mime;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Formats;

namespace Nitrate.Companion.Api;

/// <summary>What the library is made of: the apps' format filters, and their device check.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Formats")]
[Produces(MediaTypeNames.Application.Json)]
public class FormatsController : ControllerBase
{
    private readonly FormatIndex _formats;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="FormatsController"/> class.</summary>
    public FormatsController(FormatIndex formats, IUserManager users)
    {
        _formats = formats;
        _users = users;
    }

    /// <summary>
    /// What every film and show (and other video) the caller can see in [parentId] is made of, for
    /// format filters: video codec, bit depth, HDR and Dolby Vision profile, and sound codecs.
    /// </summary>
    [HttpGet]
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

    /// <summary>
    /// The hardest file to decode in each of [codecs] (comma-separated: "vc1,mpeg2video") that the
    /// caller can see, for timing a device's software decoder on real files. Codecs the library
    /// doesn't hold are left out.
    /// </summary>
    [HttpGet("Heaviest")]
    public ActionResult<IReadOnlyList<HeaviestFile>> Heaviest([FromQuery] string? codecs)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        var wanted = (codecs ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return wanted.Length == 0 ? Ok(Array.Empty<HeaviestFile>()) : Ok(_formats.Heaviest(user, wanted));
    }

    private Jellyfin.Database.Implementations.Entities.User? Me() =>
        Guid.TryParse(User.FindFirst("Jellyfin-UserId")?.Value, out var id) ? _users.GetUserById(id) : null;
}
