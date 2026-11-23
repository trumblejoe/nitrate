using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Channels;

namespace Nitrate.Companion.Api;

/// <summary>A channel; <paramref name="Users"/> (who it's granted to) only for admins.</summary>
public record ChannelOut(Guid Id, string Name, int Number, IReadOnlyList<ChannelSource> Sources, ChannelMix Mix, int AlignMinutes, IReadOnlyList<ChannelBlock> Blocks, bool Everyone, IReadOnlyList<Guid> Users, DateTimeOffset UpdatedAt);

/// <summary>A channel and its programs in the asked-for time.</summary>
public record GuideChannel(ChannelOut Channel, IReadOnlyList<GuideProgram> Programs);

/// <summary>
/// The household's channels: a TV guide made from the library, watched on demand. Everyone sees
/// the channels granted to them; admins make and change them.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Channels")]
[Produces(MediaTypeNames.Application.Json)]
public class ChannelsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly ChannelHub _channels;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="ChannelsController"/> class.</summary>
    public ChannelsController(ChannelHub channels, IUserManager users)
    {
        _channels = channels;
        _users = users;
    }

    /// <summary>The channels the caller can watch.</summary>
    [HttpGet]
    public ActionResult<IReadOnlyList<ChannelOut>> List() =>
        Run<IReadOnlyList<ChannelOut>>((me, admin) => _channels.List(me, admin).Select(c => Out(c, admin)).ToList());

    /// <summary>The guide from <paramref name="fromMs"/> to <paramref name="toMs"/> (Unix milliseconds).</summary>
    [HttpGet("Guide")]
    public ActionResult<IReadOnlyList<GuideChannel>> Guide([FromQuery] long fromMs, [FromQuery] long toMs) =>
        Run<IReadOnlyList<GuideChannel>>((me, admin) => _channels.Guide(me, admin, DateTimeOffset.FromUnixTimeMilliseconds(fromMs), DateTimeOffset.FromUnixTimeMilliseconds(toMs))
            .Select(g => new GuideChannel(Out(g.Channel, admin), g.Programs)).ToList());

    /// <summary>Tuning in: what's on now (from its start) and what follows, in order.</summary>
    [HttpGet("{id}/Tune")]
    public ActionResult<IReadOnlyList<GuideProgram>> Tune([FromRoute] Guid id, [FromQuery] int count = 20) =>
        Run((me, admin) => _channels.Tune(id, me, admin, count));

    /// <summary>Makes a channel (admins).</summary>
    [HttpPost]
    public ActionResult<ChannelOut> Create([FromBody] ChannelEdit body) =>
        Run((me, admin) => Out(_channels.Create(body, me, admin), admin));

    /// <summary>Changes a channel (admins); <paramref name="startOver"/> starts every show from its beginning.</summary>
    [HttpPost("{id}")]
    public ActionResult<ChannelOut> Update([FromRoute] Guid id, [FromBody] ChannelEdit body, [FromQuery] bool startOver = false) =>
        Run((me, admin) => Out(_channels.Update(id, body, admin, startOver), admin));

    /// <summary>Deletes a channel (admins).</summary>
    [HttpDelete("{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult Delete([FromRoute] Guid id)
    {
        var result = Run((me, admin) =>
        {
            _channels.Delete(id, admin);
            return true;
        });
        return result.Result ?? NoContent();
    }

    private static ChannelOut Out(StoredChannel c, bool admin) =>
        new(c.Id, c.Name, c.Number, c.Sources, c.Mix, c.AlignMinutes, c.Blocks, c.Everyone, admin ? c.Users : Array.Empty<Guid>(), c.UpdatedAt);

    private ActionResult<T> Run<T>(Func<Guid, bool, T> action)
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        var user = Guid.TryParse(claim, out var id) ? _users.GetUserById(id) : null;
        if (user is null)
        {
            return Unauthorized();
        }

        try
        {
            return action(user.Id, user.HasPermission(PermissionKind.IsAdministrator));
        }
        catch (ChannelException e)
        {
            return e.Error switch
            {
                ChannelError.NotFound => NotFound(e.Message),
                ChannelError.NotAdmin => StatusCode(StatusCodes.Status403Forbidden, e.Message),
                _ => BadRequest(e.Message),
            };
        }
    }
}
