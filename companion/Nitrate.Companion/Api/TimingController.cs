using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Timing;

namespace Nitrate.Companion.Api;

/// <summary>One title whose file has timing trouble.</summary>
/// <param name="ItemId">The title.</param>
/// <param name="Issues">What's wrong.</param>
/// <param name="Checked">When it was checked.</param>
public record TimingOut(Guid ItemId, IReadOnlyList<TimingIssue> Issues, DateTimeOffset Checked);

/// <summary>Files whose timing can put sound and picture out of step ("Check files' timing"), for Library health (admins).</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Timing")]
[Produces(MediaTypeNames.Application.Json)]
public class TimingController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly TimingStore _store;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="TimingController"/> class.</summary>
    public TimingController(TimingStore store, IUserManager users)
    {
        _store = store;
        _users = users;
    }

    /// <summary>Every checked title with something wrong (admins only).</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<TimingOut>> All()
    {
        var id = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        var user = Guid.TryParse(id, out var g) ? _users.GetUserById(g) : null;
        if (user == null)
        {
            return Unauthorized();
        }

        if (!user.HasPermission(PermissionKind.IsAdministrator))
        {
            return StatusCode(StatusCodes.Status403Forbidden);
        }

        return Ok(_store.WithIssues().Select(kv => new TimingOut(kv.Key, kv.Value.Issues, kv.Value.Checked)).ToList());
    }
}
