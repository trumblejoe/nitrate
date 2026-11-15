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

/// <summary>The caller's partners and requests.</summary>
public record PartnersOut(IReadOnlyList<PartnerView> Partners, IReadOnlyList<IncomingView> Incoming, IReadOnlyList<OutgoingView> Outgoing);

/// <summary>A request: to someone the caller can see (<paramref name="ToUserId"/>), or to an exact user name.</summary>
public record PartnerRequestPost(Guid? ToUserId, string? ToName, Guid SeriesId, string SeriesName);

/// <summary>A show to add to a partnership.</summary>
public record ShowPost(Guid SeriesId, string Name);

/// <summary>An episode watched together, and with whom.</summary>
public record TogetherPost(Guid ItemId, IReadOnlyList<Guid> PartnerIds);

/// <summary>Who an episode was marked watched for.</summary>
public record TogetherOut(IReadOnlyList<Guid> MarkedFor);

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

    /// <summary>The caller's partners, requests to answer, and requests waiting.</summary>
    [HttpGet("Partners")]
    public ActionResult<PartnersOut> List() => Run(me => new PartnersOut(_partners.Partners(me), _partners.Incoming(me), _partners.Outgoing(me)));

    /// <summary>Asks someone to watch a show together. Always answers "sent".</summary>
    [HttpPost("Partners/Requests")]
    public ActionResult<OutgoingView> Ask([FromBody] PartnerRequestPost body) =>
        Run(me => _partners.Request(me, body.ToUserId, body.ToName, body.SeriesId, body.SeriesName ?? string.Empty));

    /// <summary>Says yes to a request.</summary>
    [HttpPost("Partners/Requests/{id}/Accept")]
    public ActionResult<PartnerView> Accept([FromRoute] Guid id) => Run(me => _partners.Accept(me, id));

    /// <summary>Says no to a request (the asker isn't told).</summary>
    [HttpPost("Partners/Requests/{id}/Decline")]
    public ActionResult Decline([FromRoute] Guid id) => Done(me => _partners.Decline(me, id));

    /// <summary>Withdraws the caller's own request.</summary>
    [HttpDelete("Partners/Requests/{id}")]
    public ActionResult Cancel([FromRoute] Guid id) => Done(me => _partners.Cancel(me, id));

    /// <summary>Adds a show to a partnership.</summary>
    [HttpPost("Partners/{id}/Shows")]
    public ActionResult<PartnerView> AddShow([FromRoute] Guid id, [FromBody] ShowPost body) => Run(me => _partners.AddShow(me, id, body.SeriesId, body.Name ?? string.Empty));

    /// <summary>Stops watching a show together.</summary>
    [HttpDelete("Partners/{id}/Shows/{seriesId}")]
    public ActionResult<PartnerView> RemoveShow([FromRoute] Guid id, [FromRoute] Guid seriesId) => Run(me => _partners.RemoveShow(me, id, seriesId));

    /// <summary>Ends a partnership.</summary>
    [HttpDelete("Partners/{id}")]
    public ActionResult End([FromRoute] Guid id) => Done(me => _partners.End(me, id));

    /// <summary>Where the caller's partners on a show are.</summary>
    [HttpGet("Partners/Progress")]
    public ActionResult<IReadOnlyList<PartnerProgressView>> Progress([FromQuery] Guid seriesId) => Run(me => _partners.Progress(me, seriesId));

    /// <summary>Marks an episode watched for the partners who watched it with the caller.</summary>
    [HttpPost("Partners/Together")]
    public ActionResult<TogetherOut> Together([FromBody] TogetherPost body) =>
        Run(me => new TogetherOut(_partners.MarkTogether(me, body.ItemId, body.PartnerIds ?? Array.Empty<Guid>())));

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
