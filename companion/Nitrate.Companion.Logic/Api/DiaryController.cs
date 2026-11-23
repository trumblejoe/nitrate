using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Diary;

namespace Nitrate.Companion.Api;

/// <summary>A watch to add by hand.</summary>
public record DiaryPost(Guid ItemId, long? WatchedAt, int? Rating, string? Note);

/// <summary>The caller's own watch diary (see <see cref="DiaryHub"/>); nobody else's.</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Diary")]
[Produces(MediaTypeNames.Application.Json)]
public class DiaryController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly DiaryHub _diary;
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="DiaryController"/> class.</summary>
    public DiaryController(DiaryHub diary, ILibraryManager library, IUserManager users)
    {
        _diary = diary;
        _library = library;
        _users = users;
    }

    /// <summary>The caller's watches between two moments (milliseconds; either left out), newest first.</summary>
    [HttpGet]
    public ActionResult<IReadOnlyList<DiaryEntry>> List([FromQuery] long? from, [FromQuery] long? to) =>
        Me() is { } me ? Ok(_diary.List(me, from, to)) : Forbid();

    /// <summary>Adds a watch by hand.</summary>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<DiaryEntry> Log([FromBody] DiaryPost body)
    {
        if (Me() is not { } me || _users.GetUserById(me) is not { } user)
        {
            return Forbid();
        }

        var item = _library.GetItemById(body.ItemId);
        if (item is null || !item.IsVisibleStandalone(user) || DiaryRecorder.Describe(item) is not { } title)
        {
            return NotFound();
        }

        return _diary.Log(me, title, body.WatchedAt, body.Rating, body.Note);
    }

    /// <summary>Changes an entry's rating, note or date.</summary>
    [HttpPut("{id}")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<DiaryEntry> Edit([FromRoute] Guid id, [FromBody] DiaryEdit edit) =>
        Me() is not { } me ? Forbid() : _diary.Edit(me, id, edit) is { } e ? e : NotFound();

    /// <summary>Removes an entry.</summary>
    [HttpDelete("{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult Delete([FromRoute] Guid id) =>
        Me() is not { } me ? Forbid() : _diary.Delete(me, id) ? NoContent() : NotFound();

    private Guid? Me() => Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) && (Plugin.Instance?.Configuration.EnableDiary ?? true) ? id : null;
}
