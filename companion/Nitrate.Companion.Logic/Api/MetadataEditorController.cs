using System.Net;
using System.Net.Mime;
using System.Net.Sockets;
using System.Text.Json;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Providers;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Metadata;

namespace Nitrate.Companion.Api;

/// <summary>The caller's own role.</summary>
/// <param name="IsEditor">A metadata editor (admins: true).</param>
/// <param name="AllLibraries">Every library (admins: true).</param>
/// <param name="LibraryIds">Otherwise these libraries (admins: none listed).</param>
public record EditorMe(bool IsEditor, bool AllLibraries, IReadOnlyList<Guid> LibraryIds);

/// <summary>Whether the caller may edit one title.</summary>
public record EditAccess(bool CanEdit);

/// <summary>One metadata editor (admins).</summary>
public record EditorView(Guid UserId, string UserName, bool AllLibraries, IReadOnlyList<Guid> LibraryIds);

/// <summary>Makes someone an editor (with a scope) or not.</summary>
/// <param name="IsEditor">False: no longer an editor.</param>
/// <param name="AllLibraries">Every library.</param>
/// <param name="LibraryIds">Otherwise these libraries (ids dashed or dashless).</param>
public record EditorPost(bool IsEditor, bool AllLibraries, IReadOnlyList<string>? LibraryIds);

/// <summary>One line of the edit log (admins).</summary>
public record EditView(long Id, long AtMs, Guid UserId, string UserName, Guid ItemId, string ItemName, string Kind, string Summary, bool CanUndo);

/// <summary>
/// Metadata editors (Companion feature <c>metadata</c>): chosen non-admin accounts may save
/// details, apply matches, refresh and change images of titles in chosen libraries, which
/// Jellyfin keeps for administrators. Every request is checked here (the title visible to the
/// caller under Jellyfin's own access, then admin or an editor whose scope holds its library),
/// limited per editor, made through the server's own services as its admin endpoints do, and
/// logged. Contract: docs/features/admin.md, "Metadata editors".
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/Metadata")]
[Produces(MediaTypeNames.Application.Json)]
public class MetadataEditorController : ControllerBase
{
    /// <summary>The largest image an editor may upload (decoded).</summary>
    public const int MaxImageBytes = 20 * 1024 * 1024;

    /// <summary>Refused: not an editor, or the title's library is outside their scope.</summary>
    public const string CantEdit = "You can't edit this title";

    /// <summary>Refused: over the editor's limits.</summary>
    public const string TooMany = "Too many changes: wait a minute";

    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly IUserManager _users;
    private readonly MetadataEditorStore _store;
    private readonly MetadataLimiter _limits;
    private readonly IMetadataServer _server;

    /// <summary>Initializes a new instance of the <see cref="MetadataEditorController"/> class.</summary>
    public MetadataEditorController(IUserManager users, MetadataEditorStore store, MetadataLimiter limits, IMetadataServer server)
    {
        _users = users;
        _store = store;
        _limits = limits;
        _server = server;
    }

    /// <summary>The caller's role: editor or not, and where.</summary>
    [HttpGet("Me")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public ActionResult<EditorMe> Me()
    {
        if (Caller() is not { } user)
        {
            return Unauthorized();
        }

        if (IsAdmin(user))
        {
            return new EditorMe(true, true, []);
        }

        return _store.Editor(user.Id) is { } scope ? new EditorMe(true, scope.AllLibraries, scope.LibraryIds) : new EditorMe(false, false, []);
    }

    /// <summary>Whether the caller may edit this title (404 when they can't see it).</summary>
    [HttpGet("Items/{id}/Access")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<EditAccess> Access([FromRoute] string id)
    {
        var (_, refused) = Check(id);
        return refused switch
        {
            null => new EditAccess(true),
            ObjectResult { StatusCode: StatusCodes.Status403Forbidden } => new EditAccess(false),
            _ => (ActionResult)refused,
        };
    }

    /// <summary>Saves edited details: the item JSON as <c>GET /Items/{id}</c> gave it; only the editable fields are used.</summary>
    [HttpPost("Items/{id}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> SaveDetails([FromRoute] string id, [FromBody] JsonElement body)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        if (Limit() is { } limited)
        {
            return limited;
        }

        var user = Caller()!;
        try
        {
            var changes = await SaveAsync(item!, body).ConfigureAwait(false);
            if (changes.Changed.Count > 0)
            {
                _store.Log(user.Id, item!.Id, item.Name ?? string.Empty, "details", "Changed " + string.Join(", ", changes.Changed), changes.Old.ToJsonString());
            }
        }
        catch (Exception e) when (e is FormatException or InvalidOperationException or OverflowException)
        {
            return Message(StatusCodes.Status400BadRequest, e.Message);
        }

        return NoContent();
    }

    /// <summary>The external-id fields for this title, as Jellyfin's <c>/Items/{id}/ExternalIdInfos</c>.</summary>
    [HttpGet("Items/{id}/ExternalIdInfos")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<IEnumerable<ExternalIdInfo>> ExternalIdInfos([FromRoute] string id)
    {
        var (item, refused) = Check(id);
        return refused is not null ? refused : Ok(_server.ExternalIds(item!).ToList());
    }

    /// <summary>Applies a search result (from <c>POST /Items/RemoteSearch/{type}</c>) to the title.</summary>
    [HttpPost("Items/{id}/Apply")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> Apply([FromRoute] string id, [FromBody] RemoteSearchResult result, [FromQuery] bool replaceAllImages = true)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        var user = Caller()!;
        var limited = !IsAdmin(user);
        if (limited && !_limits.TryRefresh(user.Id, item!.Id, _server.Refreshing, inline: true))
        {
            return Message(StatusCodes.Status429TooManyRequests, TooMany);
        }

        try
        {
            await _server.ApplyAsync(item!, result, replaceAllImages).ConfigureAwait(false);
        }
        finally
        {
            if (limited)
            {
                _limits.Finished(user.Id, item!.Id);
            }
        }

        var ids = string.Join(", ", (result.ProviderIds ?? new()).Select(p => $"{p.Key} {p.Value}"));
        var summary = $"Matched {result.Name}" + (ids.Length > 0 ? $" ({ids})" : string.Empty) + (replaceAllImages ? ", images replaced" : string.Empty);
        _store.Log(user.Id, item!.Id, item.Name ?? string.Empty, "match", summary, null);
        return NoContent();
    }

    /// <summary>Queues a refresh, with the query of Jellyfin's <c>/Items/{id}/Refresh</c>.</summary>
    [HttpPost("Items/{id}/Refresh")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public ActionResult Refresh(
        [FromRoute] string id,
        [FromQuery] MetadataRefreshMode metadataRefreshMode = MetadataRefreshMode.None,
        [FromQuery] MetadataRefreshMode imageRefreshMode = MetadataRefreshMode.None,
        [FromQuery] bool replaceAllMetadata = false,
        [FromQuery] bool replaceAllImages = false,
        [FromQuery] bool regenerateTrickplay = false)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        var user = Caller()!;
        if (!IsAdmin(user) && !_limits.TryRefresh(user.Id, item!.Id, _server.Refreshing))
        {
            return Message(StatusCodes.Status429TooManyRequests, TooMany);
        }

        _server.QueueRefresh(item!, new RefreshRequest(metadataRefreshMode, imageRefreshMode, replaceAllMetadata, replaceAllImages, regenerateTrickplay));
        var words = new List<string> { $"Metadata {metadataRefreshMode}", $"images {imageRefreshMode}" };
        if (replaceAllMetadata)
        {
            words.Add("replace all metadata");
        }

        if (replaceAllImages)
        {
            words.Add("replace all images");
        }

        if (regenerateTrickplay)
        {
            words.Add("trickplay");
        }

        _store.Log(user.Id, item!.Id, item.Name ?? string.Empty, "refresh", string.Join(", ", words), null);
        return NoContent();
    }

    /// <summary>Downloads a remote image (from <c>/Items/{id}/RemoteImages</c>) for the title; web addresses only.</summary>
    [HttpPost("Items/{id}/RemoteImages/Download")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> DownloadImage([FromRoute] string id, [FromQuery] ImageType type, [FromQuery] string? imageUrl)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        if (type == ImageType.Chapter || !WebAddress(imageUrl, out var url))
        {
            return Message(StatusCodes.Status400BadRequest, "Only images from the web can be added");
        }

        if (Limit() is { } limited)
        {
            return limited;
        }

        await _server.DownloadImageAsync(item!, url, type).ConfigureAwait(false);
        _store.Log(Caller()!.Id, item!.Id, item.Name ?? string.Empty, "image", $"{type} downloaded from {url.Host}", null);
        return NoContent();
    }

    /// <summary>Moves an image within its type.</summary>
    [HttpPost("Items/{id}/Images/{type}/{index}/Index")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> MoveImage([FromRoute] string id, [FromRoute] ImageType type, [FromRoute] int index, [FromQuery] int newIndex)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        if (type == ImageType.Chapter || index < 0 || newIndex < 0)
        {
            return Message(StatusCodes.Status400BadRequest, "Not an image that can be moved");
        }

        if (Limit() is { } limited)
        {
            return limited;
        }

        try
        {
            await _server.MoveImageAsync(item!, type, index, newIndex).ConfigureAwait(false);
        }
        catch (ArgumentException e)
        {
            return Message(StatusCodes.Status400BadRequest, e.Message);
        }

        _store.Log(Caller()!.Id, item!.Id, item.Name ?? string.Empty, "image", $"{type} {index} moved to {newIndex}", null);
        return NoContent();
    }

    /// <summary>Removes an image (the first of its type without an index).</summary>
    [HttpDelete("Items/{id}/Images/{type}")]
    [HttpDelete("Items/{id}/Images/{type}/{index}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> DeleteImage([FromRoute] string id, [FromRoute] ImageType type, [FromRoute] int? index)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        if (type == ImageType.Chapter || index < 0)
        {
            return Message(StatusCodes.Status400BadRequest, "Not an image that can be removed");
        }

        if (Limit() is { } limited)
        {
            return limited;
        }

        await _server.DeleteImageAsync(item!, type, index ?? 0).ConfigureAwait(false);
        _store.Log(Caller()!.Id, item!.Id, item.Name ?? string.Empty, "image", $"{type} {index ?? 0} removed", null);
        return NoContent();
    }

    /// <summary>Uploads an image: the body is the picture in base64, <c>Content-Type</c> image/jpeg, image/png or image/webp, at most 20 MB.</summary>
    [HttpPost("Items/{id}/Images/{type}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status413PayloadTooLarge)]
    [ProducesResponseType(StatusCodes.Status415UnsupportedMediaType)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult> UploadImage([FromRoute] string id, [FromRoute] ImageType type, CancellationToken cancellationToken)
    {
        var (item, refused) = Check(id);
        if (refused is not null)
        {
            return refused;
        }

        if (type == ImageType.Chapter)
        {
            return Message(StatusCodes.Status400BadRequest, "Not an image that can be added");
        }

        var mime = ImageKind(Request.ContentType);
        if (mime is null)
        {
            return Message(StatusCodes.Status415UnsupportedMediaType, "Only JPEG, PNG or WebP images");
        }

        var (bytes, problem) = await ReadImageAsync(Request.Body, mime, cancellationToken).ConfigureAwait(false);
        if (problem is not null)
        {
            return problem;
        }

        if (Limit() is { } limited)
        {
            return limited;
        }

        using (var data = new MemoryStream(bytes!, writable: false))
        {
            await _server.SaveImageAsync(item!, data, mime, type).ConfigureAwait(false);
        }

        _store.Log(Caller()!.Id, item!.Id, item.Name ?? string.Empty, "image", $"{type} uploaded", null);
        return NoContent();
    }

    /// <summary>Every metadata editor (admins).</summary>
    [HttpGet("Editors")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<EditorView>> Editors()
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        return _store.Editors()
            .Select(e => _users.GetUserById(e.UserId) is { } u ? new EditorView(e.UserId, u.Username, e.AllLibraries, e.LibraryIds) : null)
            .OfType<EditorView>()
            .ToList();
    }

    /// <summary>Makes a user an editor with this scope, or not (admins).</summary>
    [HttpPost("Editors/{userId}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult SetEditor([FromRoute] string userId, [FromBody] EditorPost post)
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        if (!Guid.TryParse(userId, out var id) || _users.GetUserById(id) is null)
        {
            return NotFound();
        }

        if (!post.IsEditor)
        {
            _store.Remove(id);
            return NoContent();
        }

        var libraries = new List<Guid>();
        foreach (var l in post.LibraryIds ?? [])
        {
            if (!Guid.TryParse(l, out var library))
            {
                return Message(StatusCodes.Status400BadRequest, "A library id isn't valid");
            }

            libraries.Add(library);
        }

        _store.Put(new EditorScope(id, post.AllLibraries, libraries));
        return NoContent();
    }

    /// <summary>The edit log, newest first (admins); <paramref name="before"/> is the last id of the page before.</summary>
    [HttpGet("Edits")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public ActionResult<IReadOnlyList<EditView>> Edits([FromQuery] int limit = 50, [FromQuery] long? before = null)
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        var names = new Dictionary<Guid, string>();
        return _store.Edits(limit, before).Select(e => new EditView(
            e.Id,
            e.AtMs,
            e.UserId,
            names.TryGetValue(e.UserId, out var n) ? n : names[e.UserId] = _users.GetUserById(e.UserId)?.Username ?? string.Empty,
            e.ItemId,
            e.ItemName,
            e.Kind,
            e.Summary,
            CanUndo(e))).ToList();
    }

    /// <summary>Puts back the old values of a details edit (admins); the undo is logged too.</summary>
    [HttpPost("Edits/{id}/Undo")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult> Undo([FromRoute] long id)
    {
        if (Admin() is { } refused)
        {
            return refused;
        }

        if (_store.Edit(id) is not { } edit)
        {
            return NotFound();
        }

        if (!CanUndo(edit))
        {
            return Message(StatusCodes.Status400BadRequest, "Only changed details can be undone, once");
        }

        if (_server.Find(edit.ItemId, null) is not { } item)
        {
            return NotFound();
        }

        using var old = JsonDocument.Parse(edit.Old!);
        var changes = await SaveAsync(item, old.RootElement).ConfigureAwait(false);
        _store.MarkUndone(edit.Id);
        _store.Log(
            Caller()!.Id,
            item.Id,
            item.Name ?? string.Empty,
            "details",
            $"Undid change {edit.Id}" + (changes.Changed.Count > 0 ? ": " + string.Join(", ", changes.Changed) : string.Empty),
            changes.Changed.Count > 0 ? changes.Old.ToJsonString() : null);
        return NoContent();
    }

    /// <summary>A JPEG, PNG or WebP content type as the server's saver takes it, or null.</summary>
    public static string? ImageKind(string? contentType)
    {
        var mime = contentType?.Split(';')[0].Trim().ToLowerInvariant();
        return mime switch
        {
            "image/jpeg" or "image/jpg" => "image/jpeg",
            "image/png" => "image/png",
            "image/webp" => "image/webp",
            _ => null,
        };
    }

    /// <summary>An absolute http(s) address that isn't this machine or the local network (no server paths, no local services).</summary>
    public static bool WebAddress(string? text, out Uri url)
    {
        url = null!;
        if (!Uri.TryCreate(text, UriKind.Absolute, out var u) || (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps) || u.IsLoopback || u.IsUnc
            || u.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase) || u.Host.EndsWith(".localhost", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        if (IPAddress.TryParse(u.Host.Trim('[', ']'), out var ip) && Local(ip))
        {
            return false;
        }

        url = u;
        return true;
    }

    private static bool Local(IPAddress ip)
    {
        if (ip.IsIPv4MappedToIPv6)
        {
            ip = ip.MapToIPv4();
        }

        if (IPAddress.IsLoopback(ip) || ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any))
        {
            return true;
        }

        if (ip.AddressFamily == AddressFamily.InterNetworkV6)
        {
            return ip.IsIPv6LinkLocal || ip.IsIPv6SiteLocal || ip.IsIPv6UniqueLocal;
        }

        var b = ip.GetAddressBytes();
        return b[0] == 10 || b[0] == 0 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254) || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
    }

    /// <summary>Reads a base64 image body: 413 past 20 MB, 415 when it isn't the declared kind, 400 when it isn't base64.</summary>
    private static async Task<(byte[]? Bytes, ActionResult? Problem)> ReadImageAsync(Stream body, string mime, CancellationToken cancellationToken)
    {
        // Base64 takes 4 characters per 3 bytes; leave room for line breaks and a data: prefix.
        var most = (MaxImageBytes / 3 * 4) + (MaxImageBytes / 38) + 1024;
        using var text = new MemoryStream();
        var buffer = new byte[81920];
        int read;
        while ((read = await body.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) > 0)
        {
            if (text.Length + read > most)
            {
                return (null, Message(StatusCodes.Status413PayloadTooLarge, "Images can be at most 20 MB"));
            }

            text.Write(buffer, 0, read);
        }

        var base64 = System.Text.Encoding.ASCII.GetString(text.GetBuffer(), 0, (int)text.Length).Trim();
        if (base64.StartsWith("data:", StringComparison.OrdinalIgnoreCase) && base64.IndexOf(',', StringComparison.Ordinal) is var comma and > 0)
        {
            base64 = base64[(comma + 1)..];
        }

        byte[] bytes;
        try
        {
            bytes = Convert.FromBase64String(base64);
        }
        catch (FormatException)
        {
            return (null, Message(StatusCodes.Status400BadRequest, "The image isn't base64"));
        }

        if (bytes.Length > MaxImageBytes)
        {
            return (null, Message(StatusCodes.Status413PayloadTooLarge, "Images can be at most 20 MB"));
        }

        return Looks(bytes, mime) ? (bytes, null) : (null, Message(StatusCodes.Status415UnsupportedMediaType, "Only JPEG, PNG or WebP images"));
    }

    // The picture is what it says it is (its first bytes).
    private static bool Looks(byte[] b, string mime) => mime switch
    {
        "image/jpeg" => b.Length > 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF,
        "image/png" => b.Length > 8 && b.AsSpan(0, 8).SequenceEqual(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }),
        "image/webp" => b.Length > 12 && b.AsSpan(0, 4).SequenceEqual("RIFF"u8) && b.AsSpan(8, 4).SequenceEqual("WEBP"u8),
        _ => false,
    };

    private static ObjectResult Message(int status, string message) =>
        new(JsonSerializer.SerializeToElement(new Dictionary<string, string> { ["message"] = message })) { StatusCode = status };

    private static bool IsAdmin(User user) => user.HasPermission(PermissionKind.IsAdministrator);

    private static bool CanUndo(MetadataEdit e) => e.Kind == "details" && !e.Undone && e.Old is not null;

    private async Task<FieldChanges> SaveAsync(BaseItem item, JsonElement body)
    {
        var changes = MetadataFields.Apply(item, body, _server.People(item));
        if (changes.Changed.Count > 0)
        {
            await _server.SaveDetailsAsync(item, changes.People, changes.LockChanged).ConfigureAwait(false);
        }

        return changes;
    }

    private User? Caller() => Guid.TryParse(User.FindFirst(UserIdClaim)?.Value, out var id) ? _users.GetUserById(id) : null;

    private ActionResult? Admin()
    {
        if (Caller() is not { } user)
        {
            return Unauthorized();
        }

        return IsAdmin(user) ? null : StatusCode(StatusCodes.Status403Forbidden);
    }

    // One change from a (non-admin) editor's minute.
    private ActionResult? Limit()
    {
        var user = Caller()!;
        return IsAdmin(user) || _limits.TryChange(user.Id) ? null : Message(StatusCodes.Status429TooManyRequests, TooMany);
    }

    /// <summary>
    /// The title, when the caller may edit it: visible to them under Jellyfin's own access (else
    /// 404, as if missing), then an admin, or an editor whose scope is every library or holds one
    /// of the title's libraries (else 403). Read afresh on every request.
    /// </summary>
    private (BaseItem? Item, ActionResult? Refused) Check(string id)
    {
        if (Caller() is not { } user)
        {
            return (null, Unauthorized());
        }

        if (!Guid.TryParse(id, out var itemId) || _server.Find(itemId, user) is not { } item)
        {
            return (null, NotFound());
        }

        if (IsAdmin(user))
        {
            return (item, null);
        }

        if (_store.Editor(user.Id) is { } scope && (scope.AllLibraries || _server.LibrariesOf(item).Any(scope.LibraryIds.Contains)))
        {
            return (item, null);
        }

        return (null, Message(StatusCodes.Status403Forbidden, CantEdit));
    }
}
