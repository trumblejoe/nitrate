using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Updates;

namespace Nitrate.Companion.Api;

/// <summary>
/// The plugin's own updates, for admins: where they stand, a check now, and a restart to finish a
/// waiting one (refused while anything is playing). Used by its dashboard page and the apps' Settings.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Updates")]
[Produces(MediaTypeNames.Application.Json)]
public class UpdatesController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly SelfUpdater _updater;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="UpdatesController"/> class.</summary>
    public UpdatesController(SelfUpdater updater, IUserManager users)
    {
        _updater = updater;
        _users = users;
    }

    /// <summary>Where updates stand (admins only).</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<UpdateStatus> Status() => Admin() ?? Ok(_updater.Status());

    /// <summary>Looks for a newer version now and installs it (admins only).</summary>
    [HttpPost("Check")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<UpdateStatus>> Check(CancellationToken cancellationToken) =>
        Admin() ?? Ok(await _updater.CheckNow(cancellationToken).ConfigureAwait(false));

    /// <summary>
    /// Restarts the server to finish a waiting update (admins only): 202 when restarting, 409 with the
    /// reason when nothing is waiting or something is playing.
    /// </summary>
    [HttpPost("Restart")]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public ActionResult Restart()
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        return _updater.RestartNow() switch
        {
            RestartAnswer.Restarting => Accepted(),
            RestartAnswer.SomethingPlaying => Conflict("Something is playing; restarting now would stop it."),
            _ => Conflict("No update is waiting for a restart."),
        };
    }

    private ActionResult? Admin()
    {
        var id = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        var user = Guid.TryParse(id, out var guid) ? _users.GetUserById(guid) : null;
        if (user is null)
        {
            return Unauthorized();
        }

        return user.HasPermission(PermissionKind.IsAdministrator) ? null : StatusCode(StatusCodes.Status403Forbidden);
    }
}
