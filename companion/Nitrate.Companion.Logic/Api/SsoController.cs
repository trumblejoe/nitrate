using System.Net;
using System.Net.Mime;
using MediaBrowser.Controller;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Nitrate.Companion.Sso;

namespace Nitrate.Companion.Api;

/// <summary>Whether this server offers single sign-on, and the button's name.</summary>
public record SsoInfo(bool Enabled, string Name);

/// <summary>A device asking to sign in.</summary>
public record SsoStartPost(string DeviceId, string DeviceName, string App, string AppVersion);

/// <summary>A started sign-in: show <paramref name="Code"/> and <paramref name="Url"/> (or open it); keep <paramref name="Secret"/>.</summary>
public record SsoStarted(string Code, string Secret, string Url, long ExpiresInMs);

/// <summary>A device collecting its sign-in.</summary>
public record SsoPollPost(string Code, string Secret);

/// <summary>
/// Signing in with the server's OpenID Connect provider (see <see cref="SsoService"/>). These are
/// used before anyone is signed in, so they're open; the browser pages are plain HTML.
/// </summary>
[ApiController]
[AllowAnonymous]
[Route("Nitrate/Sso")]
public class SsoController : ControllerBase
{
    private readonly SsoService _sso;
    private readonly IServerApplicationHost _host;

    /// <summary>Initializes a new instance of the <see cref="SsoController"/> class.</summary>
    public SsoController(SsoService sso, IServerApplicationHost host)
    {
        _sso = sso;
        _host = host;
    }

    /// <summary>Whether sign-in with the provider is offered.</summary>
    [HttpGet]
    [Produces(MediaTypeNames.Application.Json)]
    public ActionResult<SsoInfo> Info()
    {
        var s = _sso.Settings;
        return new SsoInfo(s.Ready, s.ProviderName);
    }

    /// <summary>Starts a sign-in for a device.</summary>
    [HttpPost("Start")]
    [Produces(MediaTypeNames.Application.Json)]
    public ActionResult<SsoStarted> Start([FromBody] SsoStartPost body)
    {
        if (string.IsNullOrWhiteSpace(body.DeviceId))
        {
            return BadRequest("A device id is needed.");
        }

        try
        {
            var p = _sso.Start(new SsoDevice(body.DeviceId.Trim(), Clip(body.DeviceName, "Device"), Clip(body.App, "Nitrate"), Clip(body.AppVersion, "0")));
            return new SsoStarted(Pretty(p.Code), p.Secret, BaseUrl() + "/Nitrate/Sso/Login/" + p.Code, (long)SsoService.Lifetime.TotalMilliseconds);
        }
        catch (InvalidOperationException e)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, e.Message);
        }
    }

    /// <summary>The device collects its sign-in.</summary>
    [HttpPost("Poll")]
    [Produces(MediaTypeNames.Application.Json)]
    public async Task<ActionResult<SsoPoll>> Poll([FromBody] SsoPollPost body)
    {
        try
        {
            return await _sso.Poll(body.Code ?? string.Empty, body.Secret ?? string.Empty).ConfigureAwait(false);
        }
        catch (InvalidOperationException e)
        {
            return new SsoPoll(SsoState.Failed, e.Message);
        }
    }

    /// <summary>The page a person opens (from the QR code or the app): checks the code, then goes to the provider.</summary>
    [HttpGet("Login/{code}")]
    public ContentResult Login([FromRoute] string code)
    {
        var p = _sso.Find(code);
        if (p is null)
        {
            return Page("Sign-in lapsed", "<p>This sign-in has lapsed or was already used. Start again on your device.</p>");
        }

        var name = WebUtility.HtmlEncode(_sso.Settings.ProviderName);
        return Page(
            "Sign in to Jellyfin",
            $"<p>Signing in <b>{WebUtility.HtmlEncode(p.Device.DeviceName)}</b> ({WebUtility.HtmlEncode(p.Device.App)}) to {WebUtility.HtmlEncode(_host.FriendlyName)}.</p>"
            + $"<p>Make sure your device shows this code:</p><p class=code>{Pretty(p.Code)}</p>"
            + $"<p><a class=button href=\"{BaseUrl()}/Nitrate/Sso/Go/{p.Code}\">Continue with {name}</a></p>"
            + "<p class=small>If you didn't start this, close this page.</p>");
    }

    /// <summary>Sends the browser to the provider.</summary>
    [HttpGet("Go/{code}")]
    public async Task<ActionResult> Go([FromRoute] string code)
    {
        try
        {
            return Redirect(await _sso.AuthorizeUrl(code, RedirectUri(), HttpContext.RequestAborted).ConfigureAwait(false));
        }
        catch (Exception e) when (e is InvalidOperationException or HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            return Page("Couldn't sign in", $"<p>{WebUtility.HtmlEncode(e.Message)}</p>");
        }
    }

    /// <summary>Where the provider sends the browser back.</summary>
    [HttpGet("Callback")]
    public async Task<ContentResult> Callback(
        [FromQuery] string? state,
        [FromQuery] string? code,
        [FromQuery] string? error,
        [FromQuery(Name = "error_description")] string? errorDescription)
    {
        var p = await _sso.Callback(state, code, error, errorDescription, HttpContext.RequestAborted).ConfigureAwait(false);
        if (p is null)
        {
            return Page("Sign-in lapsed", "<p>This sign-in has lapsed or was already used. Start again on your device.</p>");
        }

        return p.User is { } user
            ? Page("Signed in", $"<p>You're signed in as <b>{WebUtility.HtmlEncode(user.Name)}</b> on {WebUtility.HtmlEncode(p.Device.DeviceName)}.</p><p>You can close this page and go back to Nitrate.</p>")
            : Page("Couldn't sign in", $"<p>{WebUtility.HtmlEncode(p.Error ?? "Something went wrong.")}</p><p>Start again on your device.</p>");
    }

    private static string Clip(string? s, string fallback) =>
        string.IsNullOrWhiteSpace(s) ? fallback : s.Trim().Length > 64 ? s.Trim()[..64] : s.Trim();

    private static string Pretty(string code) => code.Length == 8 ? code[..4] + "-" + code[4..] : code;

    private string BaseUrl() => _sso.Settings.PublicUrl.Length > 0
        ? _sso.Settings.PublicUrl
        : $"{Request.Scheme}://{Request.Host}{Request.PathBase}";

    private string RedirectUri() => BaseUrl() + "/Nitrate/Sso/Callback";

    private static ContentResult Page(string title, string body) => new()
    {
        ContentType = "text/html; charset=utf-8",
        Content = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
            + $"<title>{WebUtility.HtmlEncode(title)}</title><style>"
            + "body{font-family:system-ui,sans-serif;background:#101418;color:#e8eaed;margin:0;padding:32px 20px;line-height:1.5}"
            + "main{max-width:440px;margin:0 auto}h1{font-size:1.5rem}.code{font-size:2rem;letter-spacing:.15em;font-weight:700;font-family:ui-monospace,monospace}"
            + ".button{display:inline-block;background:#5b8def;color:#fff;padding:12px 20px;border-radius:999px;text-decoration:none;font-weight:600}"
            + ".small{color:#9aa0a6;font-size:.9rem}</style></head>"
            + $"<body><main><h1>{WebUtility.HtmlEncode(title)}</h1>{body}</main></body></html>",
    };
}
