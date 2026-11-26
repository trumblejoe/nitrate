using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Partners;

namespace Nitrate.Companion.Api;

/// <summary>Start a Group Watch Session of a group on one part of its titles.</summary>
/// <param name="GroupId">The watch group.</param>
/// <param name="ItemId">The episode or film.</param>
/// <param name="StartMs">Where it starts (0: the beginning).</param>
/// <param name="GatherMinutes">How long it waits for the group before starting by itself (0: the usual minute; a phone's remote asks for longer).</param>
public record SessionPost(Guid GroupId, Guid ItemId, long StartMs = 0, int GatherMinutes = 0);

/// <summary>Join a session: at where the group is now, or (<paramref name="FromStart"/>) from the beginning, when it's already playing.</summary>
/// <param name="FromStart">Start from the beginning.</param>
public record SessionJoinPost(bool FromStart = false);

/// <summary>Where the caller is in the session's part, and what just happened (pause, play, seek; null: the regular report).</summary>
/// <param name="PositionMs">The caller's position.</param>
/// <param name="Paused">Whether they're paused.</param>
/// <param name="Event">"pause", "play" or "seek" when that just happened.</param>
public record SessionReportPost(long PositionMs, bool Paused = false, string? Event = null);

/// <summary>Ask the group to start a session: now (0) or one of the postpone steps later (15, 30, 60 minutes).</summary>
/// <param name="GroupId">The watch group.</param>
/// <param name="ItemId">The episode or film.</param>
/// <param name="LaterMinutes">0 for now, else 15, 30 or 60.</param>
public record AskPost(Guid GroupId, Guid ItemId, int LaterMinutes = 0);

/// <summary>Group Watch Sessions (see <see cref="WatchSessions"/>) and asks to start one (<see cref="WatchAsks"/>). Every answer carries the server's clock.</summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class SessionsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly WatchSessions _sessions;
    private readonly WatchAsks _asks;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="SessionsController"/> class.</summary>
    public SessionsController(WatchSessions sessions, WatchAsks asks, IUserManager users)
    {
        _sessions = sessions;
        _asks = asks;
        _users = users;
    }

    /// <summary>The asks the caller should see: open ones in their groups, and their own lately.</summary>
    [HttpGet("Sessions/Asks")]
    public ActionResult<IReadOnlyList<AskView>> Asks() => Run(me => _asks.Mine(me));

    /// <summary>Asks the group to start a session (everyone else in it is asked at once).</summary>
    [HttpPost("Sessions/Asks")]
    public ActionResult<AskView> Ask([FromBody] AskPost body) => Run(me => _asks.Create(me, body.GroupId, body.ItemId, body.LaterMinutes));

    /// <summary>"Start it": the caller hosts it (the first one wins).</summary>
    [HttpPost("Sessions/Asks/{id}/Take")]
    public ActionResult<AskView> Take([FromRoute] Guid id) => Run(me => _asks.Take(me, id));

    /// <summary>Not now (told to the asker).</summary>
    [HttpPost("Sessions/Asks/{id}/Decline")]
    public ActionResult<AskView> Decline([FromRoute] Guid id) => Run(me => _asks.Decline(me, id));

    /// <summary>The asker calls it off.</summary>
    [HttpPost("Sessions/Asks/{id}/Cancel")]
    public ActionResult Cancel([FromRoute] Guid id) => Done(me => _asks.Cancel(me, id));

    /// <summary>The live sessions of the caller's groups.</summary>
    [HttpGet("Sessions")]
    public ActionResult<IReadOnlyList<SessionView>> Mine() => Run(me => _sessions.Mine(me));

    /// <summary>Starts a session (or joins the group's live one); the group's open apps are told at once.</summary>
    [HttpPost("Sessions")]
    public ActionResult<SessionJoinView> Start([FromBody] SessionPost body) => Run(me => _sessions.Start(me, body.GroupId, body.ItemId, body.StartMs, body.GatherMinutes));

    /// <summary>One session, as the caller sees it.</summary>
    [HttpGet("Sessions/{id}")]
    public ActionResult<SessionView> Get([FromRoute] Guid id) => Run(me => _sessions.Get(me, id));

    /// <summary>Joins a session.</summary>
    [HttpPost("Sessions/{id}/Join")]
    public ActionResult<SessionJoinView> Join([FromRoute] Guid id, [FromBody] SessionJoinPost? body) => Run(me => _sessions.Join(me, id, body?.FromStart == true));

    /// <summary>The starter starts it now, without waiting for the rest.</summary>
    [HttpPost("Sessions/{id}/Start")]
    public ActionResult<SessionView> StartNow([FromRoute] Guid id) => Run(me => _sessions.StartNow(me, id));

    /// <summary>Where the caller is (every ten seconds, and on a pause, play or jump).</summary>
    [HttpPost("Sessions/{id}/Report")]
    public ActionResult<SessionView> Report([FromRoute] Guid id, [FromBody] SessionReportPost body) => Run(me => _sessions.Report(me, id, body.PositionMs, body.Paused, body.Event));

    /// <summary>The caller leaves (the last one out ends it).</summary>
    [HttpPost("Sessions/{id}/Leave")]
    public ActionResult Leave([FromRoute] Guid id) => Done(me => _sessions.Leave(me, id));

    /// <summary>The starter ends it for everyone.</summary>
    [HttpPost("Sessions/{id}/End")]
    public ActionResult End([FromRoute] Guid id) => Done(me => _sessions.EndSession(me, id));

    private Guid? Me()
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        return Guid.TryParse(claim, out var id) && _users.GetUserById(id) is not null ? id : null;
    }

    private ActionResult<T> Run<T>(Func<Guid, T> action)
    {
        if (Me() is not { } me)
        {
            return Unauthorized();
        }

        try
        {
            return action(me);
        }
        catch (PartnerException e)
        {
            return e.Error == PartnerError.NotFound ? NotFound(e.Message) : BadRequest(e.Message);
        }
    }

    private ActionResult Done(Action<Guid> action)
    {
        var result = Run(me =>
        {
            action(me);
            return true;
        });
        return result.Result ?? NoContent();
    }
}
