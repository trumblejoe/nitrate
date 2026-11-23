using System.Net.Mime;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Credits;

namespace Nitrate.Companion.Api;

/// <summary>What the server found in a title's credits.</summary>
/// <param name="Skips">The scenes found (empty: checked, none there).</param>
/// <param name="Checked">When it was checked.</param>
public record CreditsOut(IReadOnlyList<CreditSkip> Skips, DateTimeOffset Checked);

/// <summary>Scenes in the credits, found once on the server for every device ("Find scenes in the credits").</summary>
[ApiController]
[Authorize]
[Route("Nitrate/Credits")]
[Produces(MediaTypeNames.Application.Json)]
public class CreditsController : ControllerBase
{
    private readonly CreditStore _store;

    /// <summary>Initializes a new instance of the <see cref="CreditsController"/> class.</summary>
    public CreditsController(CreditStore store) => _store = store;

    /// <summary>The title's scenes in the credits; 404 when it hasn't been checked yet.</summary>
    [HttpGet("{itemId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<CreditsOut> Get([FromRoute] Guid itemId) =>
        _store.Get(itemId) is { } r ? Ok(new CreditsOut(r.Skips, r.Checked)) : NotFound();
}
