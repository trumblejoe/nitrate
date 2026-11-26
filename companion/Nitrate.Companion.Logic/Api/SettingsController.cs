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

    /// <summary>"Metadata editors" (users who may edit titles; ticked here: every library).</summary>
    public const string MetadataEditors = "Metadata.Editors";

    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly IUserManager _users;
    private readonly ChatKeeping _chats;
    private readonly Nitrate.Companion.Metadata.MetadataEditorStore _editors;

    /// <summary>Initializes a new instance of the <see cref="SettingsController"/> class.</summary>
    public SettingsController(IUserManager users, ChatKeeping chats, Nitrate.Companion.Metadata.MetadataEditorStore editors)
    {
        _editors = editors;
        _users = users;
        _chats = chats;
    }

    /// <summary>The settings as they are now, by section.</summary>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<LogicSettingsPage> Get() => Admin() is { } refused ? refused : Page(_chats.Settings, _editors.Editors());

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
        List<Guid>? editors = null;
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

            if (post.Values.TryGetValue(MetadataEditors, out var chosen))
            {
                editors = chosen.ValueKind == JsonValueKind.Array ? chosen.EnumerateArray().Select(p => Guid.Parse(p.GetString() ?? string.Empty)).ToList() : new List<Guid>();
            }
        }
        catch (Exception e) when (e is FormatException or InvalidOperationException)
        {
            return BadRequest("A setting has a value of the wrong kind.");
        }

        _chats.Save(settings);
        if (editors is not null)
        {
            SaveEditors(editors);
        }

        return Page(_chats.Settings, _editors.Editors());
    }

    /// <summary>The settings page for these chat settings and metadata editors.</summary>
    public static LogicSettingsPage Page(ChatKeepingSettings chats, IReadOnlyList<Nitrate.Companion.Metadata.EditorScope> editors) => new(new[]
    {
        new LogicSettingsSection(
            "Group chat",
            "Each watch group has one conversation per title, kept for the people who were there. A conversation is deleted once its newest message is older than this; one someone there made a favourite (the heart in the apps) is never deleted.",
            new[]
            {
                new LogicSetting(ChatDays, "Delete conversations after (days)", "Counted from the newest message. 3650 (ten years) unless changed; 0: never deleted.", "number", JsonSerializer.SerializeToElement(chats.DeleteAfterDays)),
                new LogicSetting(ChatKeepFrom, "Always keep conversations with these people", "To remember someone: a conversation with a message from anyone ticked here is never deleted.", "users", JsonSerializer.SerializeToElement(chats.Kept.Select(g => g.ToString("D")).ToList())),
            }),
        new LogicSettingsSection(
            "Metadata editors",
            "People ticked here can edit titles' details, matches and images, and refresh them, in Nitrate's apps, as an administrator can (never files or paths). Every change is logged, and an administrator can undo detail changes from the log.",
            new[]
            {
                new LogicSetting(MetadataEditors, "Metadata editors", "Someone ticked here edits every library; to limit someone to some libraries, choose them in a Nitrate app (Settings, Metadata editors).", "users", JsonSerializer.SerializeToElement(editors.Select(e => e.UserId.ToString("D")).ToList())),
            }),
    });

    // Ticked: an editor (a new one edits every library; one already an editor keeps their libraries). Unticked: not an editor.
    private void SaveEditors(IReadOnlyCollection<Guid> chosen)
    {
        var now = _editors.Editors();
        foreach (var editor in now.Where(e => !chosen.Contains(e.UserId)))
        {
            _editors.Remove(editor.UserId);
        }

        foreach (var user in chosen.Distinct().Where(u => now.All(e => e.UserId != u) && _users.GetUserById(u) is not null))
        {
            _editors.Put(new Nitrate.Companion.Metadata.EditorScope(user, true, []));
        }
    }

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
