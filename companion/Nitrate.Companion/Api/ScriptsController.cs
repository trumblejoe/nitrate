using System.Net.Mime;
using System.Text.Json;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Scripts;

namespace Nitrate.Companion.Api;

/// <summary>A user, as shown when sharing.</summary>
/// <param name="Id">Their id.</param>
/// <param name="Name">Their name.</param>
public record UserRef(Guid Id, string Name);

/// <summary>A shared script without its text.</summary>
public record ScriptInfo(Guid Id, string Title, UserRef Owner, IReadOnlyList<UserRef> Members, long Version, DateTimeOffset UpdatedAt);

/// <summary>A piece of story data, with who made and last changed it.</summary>
public record RecordOut(string Id, string Type, Dictionary<string, string> Fields, bool Deleted, Guid CreatedBy, string CreatedByName, DateTimeOffset CreatedAt, Guid UpdatedBy, string UpdatedByName, DateTimeOffset UpdatedAt);

/// <summary>A shared script with its text and story data.</summary>
public record ScriptDoc(ScriptInfo Info, string Text, long Version, IReadOnlyList<RecordOut> Records);

/// <summary>A new shared script, with any story data it already has.</summary>
public record ScriptCreate(string? Text, IReadOnlyList<Guid>? Members, IReadOnlyList<RecordChange>? Records = null);

/// <summary>A script as it was at a version.</summary>
public record ScriptAt(long Version, string Text, IReadOnlyList<RecordOut> Records);

/// <summary>Who a script is shared with.</summary>
public record ScriptMembers(IReadOnlyList<Guid> Members);

/// <summary>A change to a script.</summary>
/// <param name="EditId">The app's id for this change (resending the same id is safe).</param>
/// <param name="BaseVersion">The version it was made on.</param>
/// <param name="Ops">A text change (see <see cref="TextOps"/>), or null.</param>
/// <param name="Cursor">Where the sender's cursor is after it.</param>
/// <param name="Records">Story data changes, or null.</param>
public record EditPost(string EditId, long BaseVersion, JsonElement? Ops, int? Cursor, IReadOnlyList<RecordChange>? Records = null);

/// <summary>What happened to a change: taken (it made <paramref name="Version"/>), or made on an old version.</summary>
public record EditResult(bool Accepted, long Version);

/// <summary>An accepted change, for the others (and the history).</summary>
public record EditOut(long Version, string EditId, Guid UserId, string UserName, JsonElement? Ops, IReadOnlyList<RecordChange>? Records, DateTimeOffset At, string? SceneId);

/// <summary>Someone else's cursor.</summary>
public record PresenceOut(Guid UserId, string UserName, int Cursor, long Version);

/// <summary>Changes after a version, who's here, and the script's version and stamp now.</summary>
public record EditsOut(IReadOnlyList<EditOut> Edits, long Version, IReadOnlyList<PresenceOut> Presence, long Stamp);

/// <summary>A cursor position.</summary>
public record PresencePost(int Cursor, long Version);

/// <summary>
/// Shared screenplays for the Nitrate apps: only the owner and the people they invite can open
/// one. Changes are taken only on the latest version; apps rebase and resend (see <see cref="ScriptHub"/>).
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate")]
[Produces(MediaTypeNames.Application.Json)]
public class ScriptsController : ControllerBase
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly ScriptHub _scripts;
    private readonly IUserManager _users;
    private readonly Partners.PartnerHub _partners;

    /// <summary>Initializes a new instance of the <see cref="ScriptsController"/> class.</summary>
    public ScriptsController(ScriptHub scripts, IUserManager users, Partners.PartnerHub partners)
    {
        _scripts = scripts;
        _users = users;
        _partners = partners;
    }

    /// <summary>
    /// People on this server the caller can see (enabled, not private), or the one named exactly.
    /// Private accounts are never listed or found, so they look the same as no account at all.
    /// </summary>
    [HttpGet("Users")]
    public ActionResult<IReadOnlyList<UserRef>> Users([FromQuery] string? name = null)
    {
        if (Me() is not { } me)
        {
            return Forbid();
        }

        if (!string.IsNullOrWhiteSpace(name))
        {
            return _partners.FindVisible(name) is { } u && u.Id != me
                ? new List<UserRef> { new(u.Id, u.Name) }
                : new List<UserRef>();
        }

        // Names only (Jellyfin hides most accounts from its login screen by default, so that can't
        // be the test). Admins can turn the list off: invite by name then.
        if (Plugin.Instance?.Configuration.ListUsersForSharing == false)
        {
            return new List<UserRef>();
        }

        return _partners.VisibleTo(me).Select(u => new UserRef(u.Id, u.Name)).ToList();
    }

    /// <summary>Scripts shared with or by the caller.</summary>
    [HttpGet("Scripts")]
    public ActionResult<IReadOnlyList<ScriptInfo>> List()
    {
        if (Me() is not { } me)
        {
            return Forbid();
        }

        return _scripts.List(me).Select(Info).ToList();
    }

    /// <summary>Shares a script: the caller owns it and invites <see cref="ScriptCreate.Members"/>.</summary>
    [HttpPost("Scripts")]
    public ActionResult<ScriptDoc> Create([FromBody] ScriptCreate body) => Run(me =>
    {
        var script = _scripts.Create(me, body.Text, body.Records);
        if (body.Members is { Count: > 0 })
        {
            script = _scripts.SetMembers(script.Id, me, ValidUsers(body.Members));
        }

        return Doc(script);
    });

    /// <summary>A script's text and version.</summary>
    [HttpGet("Scripts/{id}")]
    public ActionResult<ScriptDoc> Get([FromRoute] Guid id) => Run(me => Doc(_scripts.Get(id, me)));

    /// <summary>Changes who a script is shared with (the owner only).</summary>
    [HttpPut("Scripts/{id}/Members")]
    public ActionResult<ScriptInfo> Members([FromRoute] Guid id, [FromBody] ScriptMembers body) =>
        Run(me => Info(_scripts.SetMembers(id, me, ValidUsers(body.Members))));

    /// <summary>Stops sharing a script with the caller (members).</summary>
    [HttpPost("Scripts/{id}/Leave")]
    public ActionResult Leave([FromRoute] Guid id) => Run(me =>
    {
        _scripts.Leave(id, me);
        return true;
    }) is { Result: { } r } ? r : NoContent();

    /// <summary>Deletes a shared script for everyone (the owner only); everyone keeps their own copy in the app.</summary>
    [HttpDelete("Scripts/{id}")]
    public ActionResult Delete([FromRoute] Guid id) => Run(me =>
    {
        _scripts.Delete(id, me);
        return true;
    }) is { Result: { } r } ? r : NoContent();

    /// <summary>Sends a change made on <see cref="EditPost.BaseVersion"/>.</summary>
    [HttpPost("Scripts/{id}/Edits")]
    public ActionResult<EditResult> Edit([FromRoute] Guid id, [FromBody] EditPost body) => Run(me =>
    {
        var (outcome, version) = _scripts.Edit(id, me, body.EditId, body.BaseVersion, body.Ops, body.Records, body.Cursor);
        return new EditResult(outcome == EditOutcome.Accepted, version);
    });

    /// <summary>
    /// Changes after <paramref name="after"/> and who's here. With nothing new (nothing since
    /// <paramref name="seen"/>), waits up to <paramref name="waitMs"/>. 410 when those changes are gone.
    /// </summary>
    [HttpGet("Scripts/{id}/Edits")]
    public async Task<ActionResult<EditsOut>> Edits([FromRoute] Guid id, [FromQuery] long after, [FromQuery] long? seen = null, [FromQuery] int waitMs = 0)
    {
        if (Me() is not { } me)
        {
            return Forbid();
        }

        try
        {
            var wait = TimeSpan.FromMilliseconds(Math.Clamp(waitMs, 0, CompanionController.MaxWaitMs));
            var page = await _scripts.WaitAsync(id, me, after, seen, wait, HttpContext.RequestAborted).ConfigureAwait(false);
            return new EditsOut(
                page.Edits.Select(Out).ToList(),
                page.Version,
                page.Presence.Select(p => new PresenceOut(p.UserId, NameOf(p.UserId), p.Cursor, p.Version)).ToList(),
                page.Stamp);
        }
        catch (ScriptException e)
        {
            return Refuse(e);
        }
        catch (OperationCanceledException)
        {
            return new EmptyResult();
        }
    }

    /// <summary>The full history of changes from <paramref name="from"/> on (for who-wrote-what and the activity list).</summary>
    [HttpGet("Scripts/{id}/Log")]
    public ActionResult<IReadOnlyList<EditOut>> Log([FromRoute] Guid id, [FromQuery] long from = 1, [FromQuery] int limit = 2_000) =>
        Run(me => (IReadOnlyList<EditOut>)_scripts.Log(id, me, from, limit).Select(Out).ToList());

    /// <summary>The script as it was at <paramref name="version"/>, to look back at or restore.</summary>
    [HttpGet("Scripts/{id}/At")]
    public ActionResult<ScriptAt> At([FromRoute] Guid id, [FromQuery] long version) => Run(me =>
    {
        var (text, records) = _scripts.At(id, me, version);
        return new ScriptAt(version, text, records.Values.Select(RecordOutOf).ToList());
    });

    /// <summary>Says where the caller's cursor is.</summary>
    [HttpPost("Scripts/{id}/Presence")]
    public ActionResult Presence([FromRoute] Guid id, [FromBody] PresencePost body) => Run(me =>
    {
        _scripts.SetPresence(id, me, body.Cursor, body.Version);
        return true;
    }) is { Result: { } r } ? r : NoContent();

    private ActionResult<T> Run<T>(Func<Guid, T> action)
    {
        if (Me() is not { } me)
        {
            return Forbid();
        }

        try
        {
            return action(me);
        }
        catch (ScriptException e)
        {
            return Refuse(e);
        }
    }

    private ObjectResult Refuse(ScriptException e) => Problem(e.Message, statusCode: e.Error switch
    {
        ScriptError.NotFound => StatusCodes.Status404NotFound,
        ScriptError.NotOwner => StatusCodes.Status403Forbidden,
        ScriptError.Gone => StatusCodes.Status410Gone,
        _ => StatusCodes.Status400BadRequest,
    });

    private Guid? Me()
    {
        var claim = User.FindFirst(UserIdClaim)?.Value;
        return Guid.TryParse(claim, out var id) && _users.GetUserById(id) is not null ? id : null;
    }

    /// <summary>Only real, enabled users can be invited.</summary>
    private IEnumerable<Guid> ValidUsers(IEnumerable<Guid> ids) =>
        ids.Where(id => _users.GetUserById(id) is { } u && !u.HasPermission(PermissionKind.IsDisabled));

    private string NameOf(Guid userId) => _users.GetUserById(userId)?.Username ?? "Someone";

    private ScriptInfo Info(StoredScript s) => new(
        s.Id,
        TextOps.TitleOf(s.Text),
        new UserRef(s.OwnerId, NameOf(s.OwnerId)),
        s.Members.Select(m => new UserRef(m, NameOf(m))).ToList(),
        s.Version,
        s.UpdatedAt);

    private ScriptDoc Doc(StoredScript s) => new(Info(s), s.Text, s.Version, s.Records.Values.Select(RecordOutOf).ToList());

    private EditOut Out(StoredEdit e) => new(e.Version, e.EditId, e.UserId, NameOf(e.UserId), e.Ops, e.Records, e.At, e.SceneId);

    private RecordOut RecordOutOf(StoredRecord r) =>
        new(r.Id, r.Type, r.Fields, r.Deleted, r.CreatedBy, NameOf(r.CreatedBy), r.CreatedAt, r.UpdatedBy, NameOf(r.UpdatedBy), r.UpdatedAt);
}
