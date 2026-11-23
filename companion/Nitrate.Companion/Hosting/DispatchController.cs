using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Authorization.Policy;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;

namespace Nitrate.Companion.Hosting;

/// <summary>
/// Every <c>/Nitrate/…</c> request (but <c>/Nitrate/Updates</c>, the shell's own): asks the running
/// logic which of its routes it is, enforces that route's auth the way Jellyfin's authorization
/// middleware would for an <c>[Authorize]</c> action, and returns the logic's result for MVC to
/// write, so answers are what the logic's controllers gave when Jellyfin ran them itself.
/// </summary>
[ApiController]
[AllowAnonymous]
[Route("Nitrate")]
[ApiExplorerSettings(IgnoreApi = true)]
public sealed class DispatchController : ControllerBase
{
    private readonly LogicHost _host;

    /// <summary>Initializes a new instance of the <see cref="DispatchController"/> class.</summary>
    public DispatchController(LogicHost host) => _host = host;

    /// <summary>Hands the request to the logic.</summary>
    [AcceptVerbs("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", Route = "{**path}")]
    public async Task<IActionResult> Dispatch()
    {
        LogicLease? lease;
        try
        {
            lease = await _host.AcquireAsync(HttpContext.RequestAborted).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return new EmptyResult();
        }

        if (lease is null)
        {
            Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return new EmptyResult();
        }

        var keep = false;
        try
        {
            var match = lease.Logic.Match(Request.Method, Request.Path.Value ?? string.Empty);
            if (match.Kind != RouteMatchKind.Found)
            {
                // As with no endpoint at all: a bare status, no body.
                Response.StatusCode = match.Kind == RouteMatchKind.NotFound ? StatusCodes.Status404NotFound : StatusCodes.Status405MethodNotAllowed;
                return new EmptyResult();
            }

            if (!match.AllowAnonymous && !await AuthorizedAsync(match).ConfigureAwait(false))
            {
                return new EmptyResult();
            }

            var result = await lease.Logic.InvokeAsync(match, ControllerContext).ConfigureAwait(false);

            // A stream of the logic's own is read after the action returns: keep the logic until the answer is sent.
            if (result is FileStreamResult { FileStream: { } stream } && stream.GetType().Assembly.IsCollectible)
            {
                Response.RegisterForDispose(lease);
                keep = true;
            }

            return result;
        }
        finally
        {
            if (!keep)
            {
                lease.Dispose();
            }
        }
    }

    // What AuthorizationMiddleware does for an endpoint's [Authorize] data: the policy evaluator, then
    // the server's result handler (Jellyfin's 401/403) when it fails.
    private async Task<bool> AuthorizedAsync(RouteMatch match)
    {
        var services = HttpContext.RequestServices;
        var policy = await AuthorizationPolicy.CombineAsync(
            services.GetRequiredService<IAuthorizationPolicyProvider>(),
            match.Policies.Select(p => new AuthorizeAttribute { Policy = p })).ConfigureAwait(false);
        if (policy is null)
        {
            return true;
        }

        var evaluator = services.GetRequiredService<IPolicyEvaluator>();
        var authentication = await evaluator.AuthenticateAsync(policy, HttpContext).ConfigureAwait(false);
        var authorization = await evaluator.AuthorizeAsync(policy, authentication, HttpContext, HttpContext).ConfigureAwait(false);
        if (authorization.Succeeded)
        {
            return true;
        }

        if (services.GetService<IAuthorizationMiddlewareResultHandler>() is { } handler)
        {
            await handler.HandleAsync(_ => Task.CompletedTask, HttpContext, policy, authorization).ConfigureAwait(false);
        }
        else if (authorization.Challenged)
        {
            await HttpContext.ChallengeAsync().ConfigureAwait(false);
        }
        else
        {
            await HttpContext.ForbidAsync().ConfigureAwait(false);
        }

        return false;
    }
}
