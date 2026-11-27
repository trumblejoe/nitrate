using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Partners;
using Nitrate.Companion.Playlists;

namespace Nitrate.Companion.Api;

/// <summary>A playlist's own record as an app sends it (started from the person's own progress, or entries marked in it).</summary>
/// <param name="Watched">Entries watched in it.</param>
/// <param name="Resume">Where each started entry left off, ms.</param>
/// <param name="LastId">The entry last played from it.</param>
/// <param name="LastAtMs">When, epoch ms.</param>
public record PlaylistRecordPut(IReadOnlyList<Guid>? Watched, Dictionary<Guid, long>? Resume, Guid? LastId, long LastAtMs);

/// <summary>A playback that's a sitting of a playlist, said as it starts.</summary>
/// <param name="ItemId">What's playing.</param>
/// <param name="PlaylistId">The playlist it's played from; null: the caller's own after all.</param>
/// <param name="Before">The caller's own state for it from before.</param>
public record PlaylistSittingPost(Guid ItemId, Guid? PlaylistId, OwnState? Before);

/// <summary>
/// Each person's playlist records (see <see cref="PlaylistRecordBook"/>) and their playlist sittings
/// (<see cref="PlaylistSittings"/>); only ever the caller's own.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Playlists")]
[Produces(MediaTypeNames.Application.Json)]
public class PlaylistsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly PlaylistRecordBook _book;
    private readonly PlaylistSittings _sittings;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="PlaylistsController"/> class.</summary>
    public PlaylistsController(PlaylistRecordBook book, PlaylistSittings sittings, IUserManager users)
    {
        _book = book;
        _sittings = sittings;
        _users = users;
    }

    /// <summary>Every one of the caller's playlist records, most recently sat in last.</summary>
    [HttpGet("Records")]
    public ActionResult<IReadOnlyList<PlaylistRecord>> Records() => Me() is { } me ? Ok(_book.All(me)) : Unauthorized();

    /// <summary>The caller's record of one playlist.</summary>
    [HttpGet("Records/{id}")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<PlaylistRecord> Record([FromRoute] Guid id) =>
        Me() is not { } me ? Unauthorized() : _book.Get(me, id) is { } r ? r : NotFound();

    /// <summary>
    /// Keeps the caller's record of one playlist, and gives back what's kept; [onlyIfNew]: only when there's
    /// none yet (one started from the caller's own progress never replaces one kept).
    /// </summary>
    [HttpPut("Records/{id}")]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public ActionResult<PlaylistRecord> Put([FromRoute] Guid id, [FromBody] PlaylistRecordPut body, [FromQuery] bool onlyIfNew = false)
    {
        if (Me() is not { } me)
        {
            return Unauthorized();
        }

        try
        {
            return _book.Put(me, new PlaylistRecord(id, body.Watched ?? Array.Empty<Guid>(), body.Resume ?? new Dictionary<Guid, long>(), body.LastId, body.LastAtMs), onlyIfNew);
        }
        catch (ArgumentException e)
        {
            return BadRequest(e.Message);
        }
    }

    /// <summary>
    /// A playback the caller is watching as a sitting of a playlist (said as it starts): when it stops, the
    /// playlist's record moves on and the caller's own watched state, resume point, play count and
    /// last-played date for it are put back as they were. No playlist: it's their own after all.
    /// </summary>
    [HttpPost("Sitting")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public ActionResult Sitting([FromBody] PlaylistSittingPost body)
    {
        if (Me() is not { } me)
        {
            return Unauthorized();
        }

        if (body.PlaylistId is not { } playlist || playlist == Guid.Empty)
        {
            _sittings.NotInPlaylist(me, body.ItemId);
            return NoContent();
        }

        if (body.Before is null)
        {
            return BadRequest("Your own state before the playback is needed");
        }

        _sittings.Playing(me, body.ItemId, playlist, body.Before);
        return NoContent();
    }

    private Guid? Me()
    {
        var claim = User.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        return Guid.TryParse(claim, out var id) && _users.GetUserById(id) is not null ? id : null;
    }
}
