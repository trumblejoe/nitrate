using System.Net.Mime;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaSegments;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Credits;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Intros;
using Nitrate.Companion.Partners;

namespace Nitrate.Companion.Api;

/// <summary>A say that a found part was wrong.</summary>
public record SegmentFeedbackPost(Guid ItemId, SegmentPart Part);

/// <summary>A device's trouble with a title.</summary>
public record ProblemPost(Guid ItemId, string DeviceId, string DeviceName, string Kind, string? Reason);

/// <summary>A message or reaction to pin at a point in a title.</summary>
/// <param name="ItemId">The film or episode.</param>
/// <param name="PositionMs">Where in it.</param>
/// <param name="Text">What was said.</param>
/// <param name="Kind">Text or a reaction.</param>
/// <param name="SessionId">The live (SyncPlay) session it's sent in, if any: its participants get it at once and may read it later.</param>
/// <param name="GroupIds">Only these of the caller's watch groups on the title (the group the playback counts for); null: all of them (feature <c>notegroups</c>).</param>
public record NotePost(Guid ItemId, long PositionMs, string? Text, Nitrate.Companion.Chat.ChatKind Kind = Nitrate.Companion.Chat.ChatKind.Text, Guid? SessionId = null, IReadOnlyList<Guid>? GroupIds = null);

/// <summary>Which watch group's earlier notes to bring into a live session (null: stop).</summary>
public record BringPost(Guid? GroupId);

/// <summary>A moment to keep.</summary>
public record MomentPost(Guid ItemId, long PositionMs, string? Note, Guid? GroupId);

/// <summary>
/// What the plugin's database keeps for people: corrections to found intros and credits, real
/// watch time (one's own only), playback trouble across the household (admins read), saved
/// moments (one's own, and those shared with one's watch groups) and the chat of viewings one was
/// part of.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class InsightsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly IMediaSegmentManager _segments;
    private readonly IntroStore _intros;
    private readonly CreditStore _credits;
    private readonly SegmentVotes _votes;
    private readonly PeopleRecords _records;
    private readonly PartnerHub _partners;
    private readonly TimelineNoteBook _notes;
    private readonly GroupNotes _groupNotes;

    /// <summary>Initializes a new instance of the <see cref="InsightsController"/> class.</summary>
    public InsightsController(IUserManager users, ILibraryManager library, IMediaSegmentManager segments, IntroStore intros, CreditStore credits, SegmentVotes votes, PeopleRecords records, PartnerHub partners, TimelineNoteBook notes, GroupNotes groupNotes)
    {
        _notes = notes;
        _groupNotes = groupNotes;
        _users = users;
        _library = library;
        _segments = segments;
        _intros = intros;
        _credits = credits;
        _votes = votes;
        _records = records;
        _partners = partners;
    }

    /// <summary>"That part was wrong" (from going straight back after a skip, or "Watch credits").</summary>
    [HttpPost("Segments/Feedback")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<ActionResult> Feedback([FromBody] SegmentFeedbackPost post, CancellationToken cancellationToken)
    {
        if (Me() is not { } user || _library.GetItemById(post.ItemId) is not { } item)
        {
            return NotFound();
        }

        var admin = user.HasPermission(PermissionKind.IsAdministrator);
        if (!_votes.Add(post.ItemId, new SegmentVote(user.Id, post.Part, admin, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())))
        {
            return NoContent();
        }

        var changed = false;
        if (post.Part == SegmentPart.Credits && _credits.Get(post.ItemId) is { OutroRejected: false } c)
        {
            _credits.Put(post.ItemId, c with { OutroRejected = true });
            changed = true;
        }
        else if (post.Part is SegmentPart.Intro or SegmentPart.Recap && _intros.Get(post.ItemId) is { } i)
        {
            var now = post.Part == SegmentPart.Intro ? i with { IntroRejected = true } : i with { RecapRejected = true };
            changed = now != i;
            _intros.Put(post.ItemId, now);
        }

        if (changed)
        {
            // Jellyfin asks the segment providers again, so every app stops skipping it.
            await _segments.RunSegmentPluginProviders(item, _library.GetLibraryOptions(item), true, cancellationToken).ConfigureAwait(false);
        }

        return NoContent();
    }

    /// <summary>The caller's own sittings between two moments (ms).</summary>
    [HttpGet("Watching")]
    public ActionResult<IReadOnlyList<Sitting>> Watching([FromQuery] long from = 0, [FromQuery] long? to = null) =>
        Me() is { } user ? Ok(_records.Sittings(user.Id, from, to ?? long.MaxValue)) : Unauthorized();

    /// <summary>A device's trouble with a title (it failed, or the server had to convert it).</summary>
    [HttpPost("Problems")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult Report([FromBody] ProblemPost post)
    {
        if (Me() is null || _library.GetItemById(post.ItemId) is not { } item || post.Kind is not ("Failed" or "Converted"))
        {
            return BadRequest();
        }

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        string Short(string? s, int max) => s is null ? string.Empty : s.Length > max ? s[..max] : s;
        _records.AddProblem(new PlaybackProblem(post.ItemId, item.Name, Short(post.DeviceId, 100), Short(post.DeviceName, 100), post.Kind, post.Reason is null ? null : Short(post.Reason, 200), 1, now, now));
        return NoContent();
    }

    /// <summary>Titles with trouble across the household, on the most devices first (admins).</summary>
    [HttpGet("Problems")]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<ProblemTitle>> Problems() =>
        Me() is not { } user ? Unauthorized() : user.HasPermission(PermissionKind.IsAdministrator) ? Ok(_records.Problems()) : StatusCode(StatusCodes.Status403Forbidden);

    /// <summary>The caller's moments (in [itemId], else all), and moments shared with their watch groups.</summary>
    [HttpGet("Moments")]
    public ActionResult<IReadOnlyList<Moment>> Moments([FromQuery] Guid? itemId)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        var groups = _partners.Groups(user.Id).Select(g => g.Id).ToHashSet();
        var mine = _records.Moments(user.Id);
        var shared = groups.Count == 0 ? Array.Empty<Moment>() : _partners.Groups(user.Id).SelectMany(g => g.Others).Select(o => o.Id).Distinct()
            .SelectMany(other => _records.Moments(other)).Where(m => m.GroupId is { } g && groups.Contains(g));
        return Ok(mine.Concat(shared).Where(m => itemId is null || m.ItemId == itemId).OrderBy(m => m.PositionMs).ToList());
    }

    /// <summary>Keeps a moment (shown to a watch group the caller is in, when given).</summary>
    [HttpPost("Moments")]
    public ActionResult<Moment> AddMoment([FromBody] MomentPost post)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        if (post.GroupId is { } g && _partners.Groups(user.Id).All(x => x.Id != g))
        {
            return BadRequest();
        }

        var note = string.IsNullOrWhiteSpace(post.Note) ? null : post.Note.Trim()[..Math.Min(post.Note.Trim().Length, 300)];
        var moment = new Moment(Guid.NewGuid(), user.Id, user.Username, post.ItemId, Math.Max(0, post.PositionMs), note, post.GroupId, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        _records.PutMoment(moment);
        return moment;
    }

    /// <summary>Forgets one of the caller's moments.</summary>
    [HttpDelete("Moments/{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult DeleteMoment([FromRoute] Guid id)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        _records.DeleteMoment(user.Id, id);
        return NoContent();
    }

    /// <summary>
    /// A title's pinned notes the caller may read (those whose audience they were in: theirs, those
    /// written to their watch groups while they were in them, and those sent in live sessions they
    /// were in; older notes by the older rule), plus older moments read as notes, and the caller's
    /// groups on the title. Never notes merely brought into a session they were in. The apps load this once as playback
    /// starts and show each note only once the viewer reaches its point.
    /// </summary>
    [HttpGet("Notes")]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<NotesPage> Notes([FromQuery] Guid itemId)
    {
        if (!ChatEnabled || Me() is not { } user)
        {
            return Forbid();
        }

        var page = _groupNotes.Page(user.Id, itemId);
        // Older moments, by the rules they were kept under: one's own, and those shared with a group one is in now.
        var mine = _partners.Groups(user.Id);
        var groupIds = mine.Select(g => g.Id).ToHashSet();
        var moments = _records.Moments(user.Id)
            .Concat(mine.SelectMany(g => g.Others).Select(o => o.Id).Distinct().SelectMany(other => _records.Moments(other)).Where(m => m.GroupId is { } g && groupIds.Contains(g)))
            .Where(m => m.ItemId == itemId)
            .Select(TimelineNoteBook.FromMoment);
        return page with { Notes = page.Notes.Concat(moments).OrderBy(n => n.PositionMs).ThenBy(n => n.At).ToList() };
    }

    /// <summary>
    /// Pins a message or reaction at a point in a title, once: for the caller's watch groups on it,
    /// or with <see cref="NotePost.SessionId"/> for the live session the caller is in (its
    /// participants get it at once through the session's chat, and keep it).
    /// </summary>
    [HttpPost("Notes")]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public ActionResult<NoteView> PinNote([FromBody] NotePost post)
    {
        if (!ChatEnabled || Me() is not { } user || _library.GetItemById(post.ItemId) is null)
        {
            return Forbid();
        }

        // Only members of a watch group on this title, or someone in the live session, can write here.
        try
        {
            return _groupNotes.Pin(user.Id, user.Username, post.ItemId, post.PositionMs, post.Kind, post.Text, post.SessionId, post.GroupIds);
        }
        catch (NoteForbiddenException)
        {
            return Forbid();
        }
        catch (Nitrate.Companion.Chat.ChatRejectedException e)
        {
            return Problem(e.Message, statusCode: e.TooFast ? StatusCodes.Status429TooManyRequests : StatusCodes.Status400BadRequest);
        }
    }

    /// <summary>Forgets one of the caller's own notes on a title (or an older moment of theirs).</summary>
    [HttpDelete("Notes/{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public ActionResult DeleteNote([FromRoute] Guid id, [FromQuery] Guid itemId)
    {
        if (Me() is not { } user)
        {
            return Unauthorized();
        }

        if (!_notes.Delete(user.Id, itemId, id))
        {
            _records.DeleteMoment(user.Id, id);
        }

        return NoContent();
    }

    /// <summary>
    /// "Bring our earlier notes" into a live session the caller is in: while it lasts, everyone in it
    /// gets the caller's readable notes that went to that watch group as playback passes them (never
    /// kept for them). <see cref="BringPost.GroupId"/> null stops it.
    /// </summary>
    [HttpPut("Notes/Brought/{sessionId}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult Bring([FromRoute] Guid sessionId, [FromBody] BringPost post)
    {
        if (!ChatEnabled || Me() is not { } user)
        {
            return Forbid();
        }

        try
        {
            _groupNotes.Bring(user.Id, sessionId, post.GroupId);
            return NoContent();
        }
        catch (NoteForbiddenException)
        {
            return Forbid();
        }
    }

    /// <summary>The notes brought into a live session the caller is in, on one title (403 once they're no longer in it).</summary>
    [HttpGet("Notes/Brought/{sessionId}")]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<BroughtNotes> Brought([FromRoute] Guid sessionId, [FromQuery] Guid itemId)
    {
        if (!ChatEnabled || Me() is not { } user)
        {
            return Forbid();
        }

        try
        {
            return _groupNotes.Brought(user.Id, sessionId, itemId);
        }
        catch (NoteForbiddenException)
        {
            return Forbid();
        }
    }

    /// <summary>The kept chat of a title's viewings the caller was part of (oldest first; no longer written: live messages are notes now).</summary>
    [HttpGet("Chat/History")]
    public ActionResult<IReadOnlyList<ChatRecord>> ChatHistory([FromQuery] Guid itemId) =>
        Me() is { } user ? Ok(_records.Chat(itemId, user.Id)) : Unauthorized();

    private static bool ChatEnabled => Plugin.Instance?.Configuration.EnableChat ?? true;

    private Jellyfin.Database.Implementations.Entities.User? Me() =>
        Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;
}
