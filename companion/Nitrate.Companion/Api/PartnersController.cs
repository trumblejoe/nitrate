using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Partners;

namespace Nitrate.Companion.Api;

/// <summary>Whether the caller is private: by their own choice, or an admin's.</summary>
public record PrivacyOut(bool Private, bool ByAdmin);

/// <summary>Make the caller private, or not.</summary>
public record PrivacyPut(bool Private);

/// <summary>The caller's watch groups and invites.</summary>
public record PartnersOut(IReadOnlyList<GroupView> Groups, IReadOnlyList<IncomingView> Incoming, IReadOnlyList<OutgoingView> Outgoing);

/// <summary>
/// An invite: to someone the caller can see (<paramref name="ToUserId"/>) or an exact user name,
/// into one of the caller's groups (<paramref name="GroupId"/>) or a new one for the show.
/// </summary>
public record PartnerRequestPost(Guid? ToUserId, string? ToName, Guid SeriesId, string SeriesName, Guid? GroupId = null, GroupSchedule? Schedule = null);

/// <summary>A vote on a group's waiting schedule change.</summary>
public record VotePost(bool Yes);

/// <summary>An episode watched together with a group.</summary>
public record TogetherPost(Guid ItemId, Guid GroupId);

/// <summary>Who an episode was marked watched for.</summary>
public record TogetherOut(IReadOnlyList<Guid> MarkedFor);

/// <summary>"We're in the same room": parts of the group's title watched with <paramref name="Present"/> (members of the group; the caller counts as there).</summary>
public record WatchedWithPost(IReadOnlyList<Guid> ItemIds, IReadOnlyList<Guid>? Present);

/// <summary>Who it counted for, the caller included.</summary>
public record WatchedWithOut(IReadOnlyList<Guid> CountedFor);

/// <summary>Watch partners and private accounts (see <see cref="PartnerHub"/>).</summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class PartnersController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly PartnerHub _partners;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="PartnersController"/> class.</summary>
    public PartnersController(PartnerHub partners, IUserManager users)
    {
        _partners = partners;
        _users = users;
    }

    /// <summary>Whether the caller is private.</summary>
    [HttpGet("Privacy")]
    public ActionResult<PrivacyOut> Privacy() => Run(me =>
    {
        var (self, admin) = _partners.Privacy(me);
        return new PrivacyOut(self || admin, admin);
    });

    /// <summary>Makes the caller private (or not). An admin's choice still holds either way.</summary>
    [HttpPut("Privacy")]
    public ActionResult<PrivacyOut> SetPrivacy([FromBody] PrivacyPut body) => Run(me =>
    {
        _partners.SetPrivate(me, body.Private);
        var (self, admin) = _partners.Privacy(me);
        return new PrivacyOut(self || admin, admin);
    });

    /// <summary>People the caller can ask: enabled and not private.</summary>
    [HttpGet("Partners/People")]
    public ActionResult<IReadOnlyList<UserRef>> People() =>
        Run<IReadOnlyList<UserRef>>(me => _partners.VisibleTo(me).Select(u => new UserRef(u.Id, u.Name)).ToList());

    /// <summary>The caller's groups, invites to answer, and invites waiting.</summary>
    [HttpGet("Partners")]
    public ActionResult<PartnersOut> List() => Run(me => new PartnersOut(_partners.Groups(me), _partners.Incoming(me), _partners.Outgoing(me)));

    /// <summary>Invites someone to watch a show together. Always answers "sent".</summary>
    [HttpPost("Partners/Requests")]
    public ActionResult<OutgoingView> Ask([FromBody] PartnerRequestPost body) =>
        Run(me => _partners.Request(me, body.ToUserId, body.ToName, body.SeriesId, body.SeriesName ?? string.Empty, body.GroupId, body.Schedule));

    /// <summary>Asks the group to change its schedule (it takes effect once more than half agree).</summary>
    [HttpPost("Partners/Groups/{id}/Schedule")]
    public ActionResult ProposeSchedule([FromRoute] Guid id, [FromBody] GroupSchedule body) => Done(me => _partners.ProposeSchedule(me, id, body));

    /// <summary>Says yes or no to the group's waiting schedule change.</summary>
    [HttpPost("Partners/Groups/{id}/Schedule/{changeId}/Vote")]
    public ActionResult VoteSchedule([FromRoute] Guid id, [FromRoute] Guid changeId, [FromBody] VotePost body) => Done(me => _partners.Vote(me, id, changeId, body.Yes));

    /// <summary>Says yes to an invite.</summary>
    [HttpPost("Partners/Requests/{id}/Accept")]
    public ActionResult<GroupView> Accept([FromRoute] Guid id) => Run(me => _partners.Accept(me, id));

    /// <summary>Says no to a request (the asker isn't told).</summary>
    [HttpPost("Partners/Requests/{id}/Decline")]
    public ActionResult Decline([FromRoute] Guid id) => Done(me => _partners.Decline(me, id));

    /// <summary>Withdraws the caller's own request.</summary>
    [HttpDelete("Partners/Requests/{id}")]
    public ActionResult Cancel([FromRoute] Guid id) => Done(me => _partners.Cancel(me, id));

    /// <summary>Leaves a watch group (a group left with one person ends).</summary>
    [HttpDelete("Partners/Groups/{id}")]
    public ActionResult Leave([FromRoute] Guid id) => Done(me => _partners.Leave(me, id));

    /// <summary>Where the other members of the caller's groups on a show are.</summary>
    [HttpGet("Partners/Progress")]
    public ActionResult<IReadOnlyList<PartnerProgressView>> Progress([FromQuery] Guid seriesId) => Run(me => _partners.Progress(me, seriesId));

    /// <summary>Marks an episode watched for the rest of the group the caller watched it with.</summary>
    [HttpPost("Partners/Together")]
    public ActionResult<TogetherOut> Together([FromBody] TogetherPost body) =>
        Run(me => new TogetherOut(_partners.MarkTogether(me, body.ItemId, body.GroupId)));

    /// <summary>
    /// Counts parts as watched with the group by the members who were there (the same room); for a
    /// together-only group in its own record. Members only.
    /// </summary>
    [HttpPost("Partners/Groups/{id}/Watched")]
    public ActionResult<WatchedWithOut> WatchedWith([FromRoute] Guid id, [FromBody] WatchedWithPost body) =>
        Run(me => new WatchedWithOut(_partners.WatchedWith(me, id, body.ItemIds ?? Array.Empty<Guid>(), body.Present ?? Array.Empty<Guid>())));

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
