using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Credits;

namespace Nitrate.Companion.Api;

/// <summary>What the server found in a title's credits.</summary>
/// <param name="Skips">The scenes found (empty: checked, none there).</param>
/// <param name="Checked">When it was checked.</param>
public record CreditsOut(IReadOnlyList<CreditSkip> Skips, DateTimeOffset Checked);

/// <summary>Scenes in the credits, found once on the server for every device ("Find scenes in the credits").</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Credits")]
[Produces(MediaTypeNames.Application.Json)]
public class CreditsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly CreditStore _store;
    private readonly CreditScanJob _scanner;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="CreditsController"/> class.</summary>
    public CreditsController(CreditStore store, CreditScanJob scanner, ILibraryManager library, IUserManager users)
    {
        _store = store;
        _scanner = scanner;
        _library = library;
        _users = users;
    }

    /// <summary>The title's scenes in the credits; 404 when it hasn't been checked yet.</summary>
    [HttpGet("{itemId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<CreditsOut> Get([FromRoute] Guid itemId) =>
        _store.Get(itemId) is { } r ? Ok(new CreditsOut(r.Skips, r.Checked)) : NotFound();

    /// <summary>
    /// The Credits check (admins only): the title's last part as the credits reader saw it, one row
    /// per frame, from the features kept by the nightly read. Not read the current way yet: 202 with
    /// <c>Reading</c>, and it's read in the background (after any file reading running).
    /// </summary>
    [HttpGet("{itemId}/Report")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status202Accepted)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<CreditReport> Report([FromRoute] Guid itemId)
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

        var item = _library.GetItemById(itemId);
        if (item == null)
        {
            return NotFound();
        }

        var runtimeMs = (item.RunTimeTicks ?? 0) / TimeSpan.TicksPerMillisecond;
        var result = _store.Get(itemId);
        if (runtimeMs < CreditScanJob.MinRuntimeMs || string.IsNullOrEmpty(item.Path))
        {
            return Ok(CreditReport.Build(itemId, runtimeMs, result, null, null, note: "Too short (or no file) to have credits worth reading"));
        }

        var version = CreditScanJob.VersionOf(item);
        var frames = _store.Frames(itemId);
        if (frames == null || frames.Version != version || result is not { Format: CreditResult.CurrentFormat } || result.Version != version)
        {
            if (CreditScanJob.RecentFailure(itemId) is { } why)
            {
                return Ok(CreditReport.Build(itemId, runtimeMs, result, null, null, note: "Couldn't read the file: " + why));
            }

            var reading = CreditScanJob.IsReading(itemId) || _scanner.ReadSoon(item);
            return StatusCode(StatusCodes.Status202Accepted, CreditReport.Build(itemId, runtimeMs, result, null, null, reading, "Reading the end of the title now"));
        }

        return Ok(CreditReport.Build(itemId, runtimeMs, result, frames, _scanner.Dialogue(itemId, frames.StartMs, runtimeMs)));
    }
}
