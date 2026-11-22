using System.Net.Mime;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Controller.SyncPlay;
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

    private readonly ChatHub _chat;
    private readonly ISyncPlayManager _syncPlay;
    private readonly ISessionManager _sessions;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="CompanionController"/> class.</summary>
    public CompanionController(ChatHub chat, ISyncPlayManager syncPlay, ISessionManager sessions, IUserManager users, Nitrate.Companion.QueryTimes.QueryTimeStore queryTimes)
    {
        _queryTimes = queryTimes;
        _chat = chat;
        _syncPlay = syncPlay;
        _sessions = sessions;
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
        features.Add("together");
        features.Add("credits");
        features.Add("timing");
        features.Add("intros");
        features.Add("because");
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
    /// ones). With none yet, waits up to <paramref name="waitMs"/> for one.
    /// </summary>
    [HttpGet("Chat/{groupId}/Messages")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<ChatPage>> Messages([FromRoute] Guid groupId, [FromQuery] long after = -1, [FromQuery] int waitMs = 0)
    {
        if (!ChatEnabled || Caller() is not { } user || !InGroup(user.Id, user.Name, groupId))
        {
            return Forbid();
        }

        var wait = TimeSpan.FromMilliseconds(Math.Clamp(waitMs, 0, MaxWaitMs));
        try
        {
            return await _chat.WaitAsync(Room(groupId), after, wait, HttpContext.RequestAborted).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return new EmptyResult();
        }
    }

    /// <summary>Sends a message to a watch-together group's chat.</summary>
    [HttpPost("Chat/{groupId}/Messages")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public ActionResult<ChatMessage> Send([FromRoute] Guid groupId, [FromBody] ChatPost post)
    {
        if (!ChatEnabled || Caller() is not { } user || !InGroup(user.Id, user.Name, groupId))
        {
            return Forbid();
        }

        try
        {
            return _chat.Post(Room(groupId), user.Id, user.Name, post.Kind, post.Text, post.PositionMs);
        }
        catch (ChatRejectedException e)
        {
            return Problem(e.Message, statusCode: e.TooFast ? StatusCodes.Status429TooManyRequests : StatusCodes.Status400BadRequest);
        }
    }

    private static string Room(Guid groupId) => "syncplay:" + groupId.ToString("N");

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

    /// <summary>
    /// True when the user is watching in the group on any of their devices, so a phone can chat
    /// for the TV it belongs with without playing along itself.
    /// </summary>
    private bool InGroup(Guid userId, string userName, Guid groupId)
    {
        var session = _sessions.Sessions.FirstOrDefault(s => s.UserId.Equals(userId));
        if (session is null)
        {
            return false;
        }

        var group = _syncPlay.GetGroup(session, groupId);
        return group?.Participants?.Contains(userName, StringComparer.OrdinalIgnoreCase) == true;
    }
}
