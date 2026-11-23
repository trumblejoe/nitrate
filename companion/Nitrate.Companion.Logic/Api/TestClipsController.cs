using System.Net.Mime;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.TestClipsCode;

namespace Nitrate.Companion.Api;

/// <summary>
/// The TV and receiver check clips: short HDR10, Dolby Vision and HLG pictures and surround sound
/// formats, for checking that a TV shows its badges and a receiver its formats.
/// </summary>
[ApiController]
[Authorize]
[Route("Nitrate/TestClips")]
public class TestClipsController : ControllerBase
{
    /// <summary>Every clip, with what to look for.</summary>
    [HttpGet]
    [Produces(MediaTypeNames.Application.Json)]
    public ActionResult<IReadOnlyList<TestClip>> List() => Ok(TestClipCatalog.All());

    /// <summary>One clip's file (seekable).</summary>
    [HttpGet("{id}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult Get([FromRoute] string id)
    {
        var clip = TestClipCatalog.Open(id);
        if (clip is null)
        {
            return NotFound();
        }

        return File(clip.Value.Stream, clip.Value.ContentType, enableRangeProcessing: true);
    }
}
