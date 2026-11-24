using System.Net.Mime;
using System.Text.Json;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Insights;

namespace Nitrate.Companion.Api;

/// <summary>One setting the logic keeps itself, as the dashboard page draws it.</summary>
/// <param name="Key">What it's saved under (<see cref="LogicSettingsPost.Values"/>).</param>
/// <param name="Label">Its name on the page.</param>
/// <param name="Description">A line under it.</param>
/// <param name="Type"><c>number</c> (a whole number, at least 0), <c>check</c> (on/off) or <c>users</c> (any of the server's users, private accounts included: admins only).</param>
/// <param name="Value">What it is now: a number, true/false, or a list of user ids.</param>
public record LogicSetting(string Key, string Label, string Description, string Type, JsonElement Value);

/// <summary>A group of the logic's settings, under its own heading on the dashboard page.</summary>
public record LogicSettingsSection(string Title, string Description, IReadOnlyList<LogicSetting> Fields);

/// <summary>The logic's own settings (admins).</summary>
public record LogicSettingsPage(IReadOnlyList<LogicSettingsSection> Sections);

/// <summary>Changed settings, by <see cref="LogicSetting.Key"/>; keys left out stay as they are.</summary>
public record LogicSettingsPost(IReadOnlyDictionary<string, JsonElement> Values);

/// <summary>
/// Settings the logic keeps in its own database rather than the shell's configuration, so new ones
/// need no plugin update (restart): the dashboard page asks for them and draws each by its type
/// (a shell from before this simply doesn't show them). Admins only.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Settings")]
[Produces(MediaTypeNames.Application.Json)]
public class SettingsController : ControllerBase
{
    /// <summary>"Delete chats after" (days; 0: never deleted).</summary>
    public const string ChatDays = "Chats.DeleteAfterDays";

    /// <summary>"Never delete chats from these users".</summary>
    public const string ChatKeepFrom = "Chats.KeepFrom";

    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly IUserManager _users;
    private readonly ChatKeeping _chats;

    /// <summary>Initializes a new instance of the <see cref="SettingsController"/> class.</summary>
    public SettingsController(IUserManager users, ChatKeeping chats)
    {
        _users = users;
        _chats = chats;
    }

    /// <summary>The settings as they are now, by section.</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<LogicSettingsPage> Get() => Admin() is { } refused ? refused : Page(_chats.Settings);

    /// <summary>Saves changed settings (unknown keys are ignored); answers with them as saved.</summary>
    [HttpPost]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<LogicSettingsPage> Save([FromBody] LogicSettingsPost post)
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        var settings = _chats.Settings;
        try
        {
            if (post.Values.TryGetValue(ChatDays, out var days))
            {
                settings = settings with { DeleteAfterDays = days.ValueKind == JsonValueKind.String ? (int.TryParse(days.GetString(), out var d) ? d : 0) : days.ValueKind == JsonValueKind.Number ? days.GetInt32() : 0 };
            }

            if (post.Values.TryGetValue(ChatKeepFrom, out var people))
            {
                settings = settings with { KeepFrom = people.ValueKind == JsonValueKind.Array ? people.EnumerateArray().Select(p => Guid.Parse(p.GetString() ?? string.Empty)).ToList() : new List<Guid>() };
            }
        }
        catch (Exception e) when (e is FormatException or InvalidOperationException)
        {
            return BadRequest("A setting has a value of the wrong kind.");
        }

        _chats.Save(settings);
        return Page(_chats.Settings);
    }

    /// <summary>The settings page for these chat settings.</summary>
    public static LogicSettingsPage Page(ChatKeepingSettings chats) => new(new[]
    {
        new LogicSettingsSection(
            "Chat histories",
            "Chats written while watching are kept for the people who were there. A chat someone there made a favourite (the heart in the apps' Chats list) is never deleted by these rules, nor are the chats it builds on.",
            new[]
            {
                new LogicSetting(ChatDays, "Delete chats after (days)", "0 or empty: never deleted.", "number", JsonSerializer.SerializeToElement(chats.DeleteAfterDays)),
                new LogicSetting(ChatKeepFrom, "Never delete chats from these users", "A chat with a message from anyone ticked here is kept.", "users", JsonSerializer.SerializeToElement(chats.Kept.Select(g => g.ToString("D")).ToList())),
            }),
    });

    private ActionResult? Admin()
    {
        var user = Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;
        if (user is null)
        {
            return Unauthorized();
        }

        return user.HasPermission(PermissionKind.IsAdministrator) ? null : StatusCode(StatusCodes.Status403Forbidden);
    }
}
