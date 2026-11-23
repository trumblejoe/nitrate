using System.Net.Mime;
using System.Text.Json;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Devices;

namespace Nitrate.Companion.Api;

/// <summary>A device's summary to keep.</summary>
/// <param name="DeviceId">The sending device's id.</param>
/// <param name="DeviceName">Its name.</param>
/// <param name="Summary">The app's summary (its own JSON).</param>
public record DeviceReportPost(string DeviceId, string DeviceName, JsonElement Summary);

/// <summary>
/// Household devices: every signed-in device sends its own playback summary; only admins read them.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Devices")]
[Produces(MediaTypeNames.Application.Json)]
public class DevicesController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly DeviceHub _devices;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="DevicesController"/> class.</summary>
    public DevicesController(DeviceHub devices, IUserManager users)
    {
        _devices = devices;
        _users = users;
    }

    /// <summary>Keeps the caller's summary for one of their devices (replacing its last).</summary>
    [HttpPost("Report")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public ActionResult Report([FromBody] DeviceReportPost post)
    {
        if (Caller() is not { } user)
        {
            return Unauthorized();
        }

        try
        {
            _devices.Report(user.Id, user.Username, post.DeviceId, post.DeviceName, post.Summary);
            return NoContent();
        }
        catch (DeviceReportException e)
        {
            return BadRequest(e.Message);
        }
    }

    /// <summary>Every household device's latest summary (admins only).</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<DeviceReport>> All()
    {
        if (Caller() is not { } user)
        {
            return Unauthorized();
        }

        return user.HasPermission(PermissionKind.IsAdministrator) ? Ok(_devices.All()) : StatusCode(StatusCodes.Status403Forbidden);
    }

    /// <summary>Forgets one device's summary (admins, or the person it belongs to).</summary>
    [HttpDelete("{userId}/{deviceId}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult Forget([FromRoute] Guid userId, [FromRoute] string deviceId)
    {
        if (Caller() is not { } user)
        {
            return Unauthorized();
        }

        if (user.Id != userId && !user.HasPermission(PermissionKind.IsAdministrator))
        {
            return StatusCode(StatusCodes.Status403Forbidden);
        }

        return _devices.Forget(userId, deviceId) ? NoContent() : NotFound();
    }

    private Jellyfin.Database.Implementations.Entities.User? Caller()
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        return Guid.TryParse(claim, out var id) ? _users.GetUserById(id) : null;
    }
}
