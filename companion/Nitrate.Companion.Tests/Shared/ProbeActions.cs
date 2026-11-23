using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

// Compiled into the tests (run by MVC itself, under /Direct/...) and into the fake logic (run by
// the shell's dispatcher and ApiRouter, under /Nitrate/...): the same actions both ways, so the
// tests can check the answers are the same.
namespace Nitrate.Companion.Probe;

/// <summary>A kind.</summary>
public enum ProbeKind
{
    /// <summary>One.</summary>
    One,

    /// <summary>Two.</summary>
    Two,
}

/// <summary>An answer of the logic's own type.</summary>
public record ProbeOut(string Name, int Count, string? Missing, IReadOnlyList<int> Numbers, ProbeKind Kind = ProbeKind.Two, Guid? Id = null, DateTimeOffset? At = null);

/// <summary>A body.</summary>
public record ProbeIn(string Name, int? Count, List<ProbeItem>? Items);

/// <summary>Part of a body.</summary>
public record ProbeItem(string Label);

/// <summary>The probe's actions, with [Produces] like the Companion's controllers.</summary>
public abstract class ProbeActions : ControllerBase
{
    /// <summary>The logic's own type.</summary>
    [HttpGet("Object")]
    public ActionResult<ProbeOut> Object() => new ProbeOut("x", 3, null, [1, 2], At: DateTimeOffset.FromUnixTimeSeconds(1_700_000_000));

    /// <summary>A list of them.</summary>
    [HttpGet("List")]
    public ActionResult<IReadOnlyList<ProbeOut>> List() => new List<ProbeOut> { new("a", 1, null, []), new("b", 2, "m", [3]) };

    /// <summary>A framework type.</summary>
    [HttpGet("Dict")]
    public ActionResult<Dictionary<string, string>> Dict() => new Dictionary<string, string> { ["A"] = "b" };

    /// <summary>Anonymous type.</summary>
    [HttpGet("Anonymous")]
    public IActionResult Anonymous() => Ok(new { Hello = "there", Count = 2 });

    /// <summary>Not found.</summary>
    [HttpGet("Missing")]
    public ActionResult<ProbeOut> Missing() => NotFound();

    /// <summary>A problem.</summary>
    [HttpGet("Problem")]
    public ActionResult<ProbeOut> Trouble() => Problem("It went wrong.", statusCode: StatusCodes.Status409Conflict);

    /// <summary>Forbidden by the action.</summary>
    [HttpGet("Forbid")]
    public ActionResult<ProbeOut> No() => Forbid();

    /// <summary>A bare status.</summary>
    [HttpGet("Status")]
    public ActionResult Status403() => StatusCode(StatusCodes.Status403Forbidden);

    /// <summary>Unauthorized by the action.</summary>
    [HttpGet("Unauthorized")]
    public ActionResult NotYou() => Unauthorized();

    /// <summary>Accepted.</summary>
    [HttpPost("Accepted")]
    public ActionResult Accept() => Accepted();

    /// <summary>A conflict with words.</summary>
    [HttpPost("Conflict")]
    public ActionResult Clash() => Conflict("Something is playing.");

    /// <summary>No content.</summary>
    [HttpDelete("Item/{id}")]
    public ActionResult Delete([FromRoute] Guid id) => id == Guid.Empty ? NotFound() : NoContent();

    /// <summary>A literal beside a parameter.</summary>
    [HttpGet("Item/Literal")]
    public ActionResult<string> Literal() => "literal";

    /// <summary>A parameter beside a literal.</summary>
    [HttpGet("Item/{id}")]
    public ActionResult<string> Item([FromRoute] string id) => "item " + id;

    /// <summary>Query binding.</summary>
    [HttpGet("Query")]
    public ActionResult<ProbeOut> Query([FromQuery] string name, [FromQuery] long since, [FromQuery] int count = 2, [FromQuery] Guid? id = null, [FromQuery] bool flag = false, [FromQuery(Name = "other_name")] string? other = null)
        => new ProbeOut(name + (flag ? "!" : string.Empty) + other, count, since.ToString(System.Globalization.CultureInfo.InvariantCulture), [], Id: id);

    /// <summary>Route binding.</summary>
    [HttpGet("Route/{id}/{index}")]
    public ActionResult<ProbeOut> ByRoute([FromRoute] Guid id, [FromRoute] int index) => new ProbeOut("route", index, null, [], Id: id);

    /// <summary>A required body.</summary>
    [HttpPost("Body")]
    public ActionResult<ProbeOut> Body([FromBody] ProbeIn body) => new ProbeOut(body.Name, body.Count ?? -1, null, body.Items?.Select(i => i.Label.Length).ToList() ?? []);

    /// <summary>An optional body.</summary>
    [HttpPost("Optional")]
    public ActionResult<string> Optional([FromBody] ProbeIn? body) => body?.Name ?? "none";

    /// <summary>A dictionary body.</summary>
    [HttpPut("Dict")]
    public ActionResult<int> PutDict([FromBody] Dictionary<string, string?> changes) => changes.Count;

    /// <summary>Null.</summary>
    [HttpGet("Null")]
    public ActionResult<ProbeOut?> Null() => Ok(null);

    /// <summary>Async.</summary>
    [HttpGet("Async")]
    public async Task<ActionResult<ProbeOut>> Later(CancellationToken cancellationToken)
    {
        await Task.Delay(1, cancellationToken).ConfigureAwait(false);
        return new ProbeOut("later", 1, null, []);
    }

    /// <summary>A file, seekable.</summary>
    [HttpGet("File")]
    public IActionResult Bytes() => File(new MemoryStream(Enumerable.Range(0, 1000).Select(i => (byte)i).ToArray()), "application/octet-stream", enableRangeProcessing: true);

    /// <summary>Bytes in memory.</summary>
    [HttpGet("Picture")]
    public IActionResult Picture()
    {
        Response.Headers.CacheControl = "private, max-age=86400";
        return File(new byte[] { 1, 2, 3 }, "image/png");
    }

    /// <summary>A page.</summary>
    [HttpGet("Page")]
    public ContentResult Page() => new() { ContentType = "text/html; charset=utf-8", Content = "<p>Hi</p>" };

    /// <summary>Elsewhere.</summary>
    [HttpGet("Redirect")]
    public ActionResult Away() => Redirect("https://example.com/back");

    /// <summary>Empty, as a cancelled long poll answers.</summary>
    [HttpGet("Empty")]
    public ActionResult<ProbeOut> Nothing() => new EmptyResult();

    /// <summary>Open to anyone.</summary>
    [AllowAnonymous]
    [HttpGet("Open")]
    public ActionResult<ProbeOut> Open() => new ProbeOut("open", 0, null, []);

    /// <summary>Admins only, by policy.</summary>
    [Authorize(Policy = "Admin")]
    [HttpGet("Admin")]
    public ActionResult<ProbeOut> Admin() => new ProbeOut("admin", 0, null, []);

    /// <summary>Who's asking.</summary>
    [HttpGet("Me")]
    public ActionResult<string> Me() => User.FindFirst("Jellyfin-UserId")?.Value ?? "nobody";
}

/// <summary>Actions without [Produces], like the test clips' file and the sign-in pages.</summary>
public abstract class BareActions : ControllerBase
{
    /// <summary>Not found.</summary>
    [HttpGet("Missing")]
    public IActionResult Missing() => NotFound();

    /// <summary>A list, negotiated.</summary>
    [HttpGet("List")]
    [Produces("application/json")]
    public ActionResult<IReadOnlyList<ProbeOut>> List() => Ok(new List<ProbeOut> { new("a", 1, null, []) });
}
