using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.QueryTimes;

namespace Nitrate.Companion.Api;

/// <summary>The server's own times for the caller's tagged requests (the apps' query analyzer).</summary>
[ApiController]
[Authorize]
[Route("Nitrate/QueryTimes")]
[Produces(MediaTypeNames.Application.Json)]
public class QueryTimesController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly QueryTimeStore _store;

    /// <summary>Initializes a new instance of the <see cref="QueryTimesController"/> class.</summary>
    public QueryTimesController(QueryTimeStore store, MediaBrowser.Controller.Library.IUserManager users)
    {
        _store = store;
        _users = users;
    }

    private readonly MediaBrowser.Controller.Library.IUserManager _users;

    /// <summary>Each kind of request's typical and slow server times by day, the last [days] days (admins only).</summary>
    [HttpGet("Trends")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<Nitrate.Companion.Insights.QueryTrend>> Trends([FromServices] Nitrate.Companion.Insights.QueryTrendStore trends, [FromQuery] int days = 30)
    {
        var id = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        var user = Guid.TryParse(id, out var g) ? _users.GetUserById(g) : null;
        if (user == null)
        {
            return Unauthorized();
        }

        return user.HasPermission(PermissionKind.IsAdministrator) ? Ok(trends.Trends(days, DateTimeOffset.UtcNow)) : StatusCode(StatusCodes.Status403Forbidden);
    }

    /// <summary>Every person's requests of the last two hours by kind, costliest first (admins only; no names, titles or search words).</summary>
    [HttpGet("Household")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<HouseholdQuery>> Household()
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

        return Ok(_store.Household());
    }

    /// <summary>The caller's own times for one app run.</summary>
    /// <param name="run">The run's prefix (the part of the tag before the dash).</param>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<IReadOnlyList<QueryTime>> Times([FromQuery] string run)
    {
        var id = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        if (!Guid.TryParse(id, out var user))
        {
            return Unauthorized();
        }

        if (!QueryTimeStore.ValidTag(run))
        {
            return BadRequest();
        }

        return Ok(_store.For(user, run));
    }
}
