using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.WatchTiming;

namespace Nitrate.Companion.Api;

/// <summary>Timing the caller's own Watched/Unwatched and each watch-state listener (admins only; see <see cref="WatchChangeTiming"/>).</summary>
[ApiController]
[Authorize]
[Route("Nitrate/WatchTiming")]
[Produces(MediaTypeNames.Application.Json)]
public class WatchTimingController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly WatchChangeTiming _timing;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="WatchTimingController"/> class.</summary>
    public WatchTimingController(WatchChangeTiming timing, IUserManager users)
    {
        _timing = timing;
        _users = users;
    }

    /// <summary>Starts timing the caller's next Watched/Unwatched presses for [minutes] (at most ten).</summary>
    [HttpPost("Start")]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<WatchTimingReport> Start([FromQuery] int minutes = 3) =>
        Admin() is { } me ? _timing.Start(me, TimeSpan.FromMinutes(Math.Clamp(minutes, 1, 10))) : StatusCode(StatusCodes.Status403Forbidden);

    /// <summary>Stops timing (the results stay).</summary>
    [HttpPost("Stop")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult Stop()
    {
        if (Admin() is null)
        {
            return StatusCode(StatusCodes.Status403Forbidden);
        }

        _timing.Stop();
        return NoContent();
    }

    /// <summary>The caller's last timing, as it stands.</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<WatchTimingReport> Report() =>
        Admin() is not { } me ? StatusCode(StatusCodes.Status403Forbidden) : _timing.Report(me) is { } r ? r : NotFound();

    private Guid? Admin()
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        return Guid.TryParse(claim, out var id) && _users.GetUserById(id) is { } user && user.HasPermission(PermissionKind.IsAdministrator) ? id : null;
    }
}
