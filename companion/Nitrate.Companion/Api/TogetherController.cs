using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Partners;
using Nitrate.Companion.Together;

namespace Nitrate.Companion.Api;

/// <summary>An invite to send.</summary>
/// <param name="UserIds">Who to invite (from the people list).</param>
/// <param name="GroupId">The watch-together group to join.</param>
/// <param name="ItemId">What's playing, if anything.</param>
/// <param name="ItemName">Its name.</param>
public record InvitePost(IReadOnlyList<Guid> UserIds, string GroupId, string? ItemId, string? ItemName);

/// <summary>Watch-together invites: send one to people, see your own, dismiss one.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Together/Invites")]
[Produces(MediaTypeNames.Application.Json)]
public class TogetherController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly InviteHub _invites;
    private readonly PartnerHub _partners;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="TogetherController"/> class.</summary>
    public TogetherController(InviteHub invites, PartnerHub partners, IUserManager users)
    {
        _invites = invites;
        _partners = partners;
        _users = users;
    }

    /// <summary>
    /// Invites people to the caller's group. Always answers "sent": invites to private or unknown
    /// accounts are dropped without saying so.
    /// </summary>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public ActionResult Send([FromBody] InvitePost post)
    {
        if (Caller() is not { } me)
        {
            return Unauthorized();
        }

        try
        {
            _invites.Send(me.Id, me.Username, post.UserIds ?? Array.Empty<Guid>(), post.GroupId, post.ItemId, post.ItemName,
                to => _users.GetUserById(to) is not null && !_partners.IsPrivate(to));
            return NoContent();
        }
        catch (ArgumentException e)
        {
            return BadRequest(e.Message);
        }
    }

    /// <summary>The caller's open invites, newest first.</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<IReadOnlyList<WatchInvite>> Mine() =>
        Caller() is { } me ? Ok(_invites.For(me.Id)) : Unauthorized();

    /// <summary>Dismisses one of the caller's invites (joined or turned down).</summary>
    [HttpDelete("{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult Dismiss([FromRoute] string id)
    {
        if (Caller() is not { } me)
        {
            return Unauthorized();
        }

        _invites.Dismiss(me.Id, id);
        return NoContent();
    }

    private Jellyfin.Database.Implementations.Entities.User? Caller() =>
        Guid.TryParse(User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;
}
