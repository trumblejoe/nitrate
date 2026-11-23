using System.Net.Mime;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Chat;

namespace Nitrate.Companion.Api;

/// <summary>What the plugin offers, so apps can tell it's installed and which features it has.</summary>
/// <param name="Version">The plugin's version.</param>
/// <param name="Features">Feature names, e.g. "chat".</param>
public record CompanionInfo(string Version, IReadOnlyList<string> Features);

/// <summary>A message to send.</summary>
/// <param name="Text">What to say (a single emoji for a reaction).</param>
/// <param name="Kind">Text or a reaction.</param>
/// <param name="PositionMs">Where the sender is in what's playing.</param>
public record ChatPost(string? Text, ChatKind Kind = ChatKind.Text, long? PositionMs = null);

/// <summary>The Nitrate Companion API, for signed-in users.</summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class CompanionController : ControllerBase
{
    /// <summary>Longest a request waits for new messages.</summary>
    public const int MaxWaitMs = 30_000;

    private const string UserIdClaim = "Jellyfin-UserId";

    private readonly Nitrate.Companion.Insights.GroupNotes _notes;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="CompanionController"/> class.</summary>
    public CompanionController(Nitrate.Companion.Insights.GroupNotes notes, IUserManager users, Nitrate.Companion.QueryTimes.QueryTimeStore queryTimes)
    {
        _queryTimes = queryTimes;
        _notes = notes;
        _users = users;
    }

    private readonly Nitrate.Companion.QueryTimes.QueryTimeStore _queryTimes;

    private static bool ChatEnabled => Plugin.Instance?.Configuration.EnableChat ?? true;

    /// <summary>The plugin's version and features.</summary>
    [HttpGet("Companion")]
    public ActionResult<CompanionInfo> Info()
    {
        var features = new List<string>();
        if (ChatEnabled)
        {
            features.Add("chat");
        }

        features.Add("scripts");
        features.Add("devices");
        if (TestClipsCode.TestClipCatalog.All().Count > 0)
        {
            features.Add("testclips");
        }

        features.Add("channels");
        features.Add("partners");
        // Together-only groups keep their own watched record; "We're in the same room" (POST /Nitrate/Partners/Groups/{id}/Watched).
        features.Add("grouprecord");
        features.Add("party");
        features.Add("together");
        features.Add("credits");
        features.Add("timing");
        features.Add("intros");
        features.Add("because");
        features.Add("segmentfeedback");
        features.Add("watching");
        features.Add("problems");
        features.Add("moments");
        // Chat pinned to the timeline (the moments' successor; "moments" stays for older apps).
        if (ChatEnabled)
        {
            features.Add("notes");
            // One note per message, live sessions included (POST /Nitrate/Notes with a SessionId), and "Bring our earlier notes".
            features.Add("livenotes");
            // A note can go to just the watch group a playback counts for (NotePost.GroupIds).
            features.Add("notegroups");
        }

        if (ChatEnabled && (Plugin.Instance?.Configuration.KeepChatHistory ?? true))
        {
            features.Add("chathistory");
        }

        features.Add("home");
        features.Add("formats");
        // Only once the timing middleware is known to be in the server's pipeline.
        if (_queryTimes.Active)
        {
            features.Add("querytimes");
        }
        if (Plugin.Instance?.Configuration.EnableDiary ?? true)
        {
            features.Add("diary");
        }

        if (Plugin.Instance?.Configuration.EnableComics ?? true)
        {
            features.Add("comics");
            if (Catalogs.CatalogAccess.Usable(Plugin.Instance?.Configuration.ComicCatalogs).Count > 0)
            {
                features.Add("catalogs");
            }
        }

        if (Plugin.Instance?.Configuration.EnableLines ?? true)
        {
            features.Add("lines");
        }
        if (Sso.SsoSettings.From(Plugin.Instance?.Configuration).Ready)
        {
            features.Add("sso");
        }

        return new CompanionInfo(typeof(Plugin).Assembly.GetName().Version?.ToString(3) ?? "0", features);
    }

    /// <summary>
    /// Messages in a watch-together group's chat after <paramref name="after"/> (-1: the recent
    /// ones), only those the caller was there for. With none yet, waits up to
    /// <paramref name="waitMs"/> for one. A message carrying a <c>NoteId</c> is that note, kept.
    /// </summary>
    [HttpGet("Chat/{groupId}/Messages")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<ChatPage>> Messages([FromRoute] Guid groupId, [FromQuery] long after = -1, [FromQuery] int waitMs = 0)
    {
        if (!ChatEnabled || Caller() is not { } user)
        {
            return Forbid();
        }

        var wait = TimeSpan.FromMilliseconds(Math.Clamp(waitMs, 0, MaxWaitMs));
        try
        {
            return await _notes.Live(user.Id, groupId, after, wait, HttpContext.RequestAborted).ConfigureAwait(false);
        }
        catch (Nitrate.Companion.Insights.NoteForbiddenException)
        {
            return Forbid();
        }
        catch (OperationCanceledException)
        {
            return new EmptyResult();
        }
    }

    /// <summary>
    /// Sends a message to a watch-together group's chat (the older way: the apps now pin it with
    /// <c>POST /Nitrate/Notes</c> and the session). It is one note on what the group is playing,
    /// for the people there (and the watch groups it's for), delivered to them at once.
    /// </summary>
    [HttpPost("Chat/{groupId}/Messages")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public ActionResult<ChatMessage> Send([FromRoute] Guid groupId, [FromBody] ChatPost post)
    {
        if (!ChatEnabled || Caller() is not { } user)
        {
            return Forbid();
        }

        try
        {
            return _notes.SendLive(user.Id, user.Name, groupId, post.Kind, post.Text, post.PositionMs);
        }
        catch (Nitrate.Companion.Insights.NoteForbiddenException)
        {
            return Forbid();
        }
        catch (ChatRejectedException e)
        {
            return Problem(e.Message, statusCode: e.TooFast ? StatusCodes.Status429TooManyRequests : StatusCodes.Status400BadRequest);
        }
    }

    private (Guid Id, string Name)? Caller()
    {
        var claim = User.FindFirst(UserIdClaim)?.Value;
        if (!Guid.TryParse(claim, out var id))
        {
            return null;
        }

        var user = _users.GetUserById(id);
        return user is null ? null : (id, user.Username);
    }
}
