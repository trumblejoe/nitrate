using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Watched;

namespace Nitrate.Companion.Api;

/// <summary>Watched or Unwatched pressed on titles.</summary>
/// <param name="ItemIds">The titles (a show or season marks every episode in it, as Jellyfin's own).</param>
/// <param name="Played">Watched (true) or Unwatched.</param>
public record WatchedPost(IReadOnlyList<Guid>? ItemIds, bool Played = true);

/// <summary>What happened to a press: the titles the caller can't see (Jellyfin's "not found"); the rest are kept, to be saved a moment later.</summary>
/// <param name="NotFound">Titles not marked.</param>
public record WatchedAnswer(IReadOnlyList<Guid> NotFound);

/// <summary>The caller's Watched/Unwatched, answered at once (see <see cref="WatchedQueue"/>).</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Watched")]
[Produces(MediaTypeNames.Application.Json)]
public class WatchedController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly WatchedQueue _queue;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="WatchedController"/> class.</summary>
    public WatchedController(WatchedQueue queue, IUserManager users)
    {
        _queue = queue;
        _users = users;
    }

    /// <summary>Marks titles watched or unwatched for the caller (only ever the caller, from their sign-in).</summary>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public ActionResult<WatchedAnswer> Press([FromBody] WatchedPost body)
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        if (!Guid.TryParse(claim, out var me) || _users.GetUserById(me) is null)
        {
            return Unauthorized();
        }

        var ids = body.ItemIds ?? Array.Empty<Guid>();
        if (ids.Count == 0 || ids.Count > WatchedQueue.MaxPerRequest)
        {
            return BadRequest("Between 1 and " + WatchedQueue.MaxPerRequest + " titles");
        }

        return new WatchedAnswer(_queue.Press(me, ids, body.Played));
    }
}
