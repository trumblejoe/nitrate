using System.Diagnostics;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;

namespace Nitrate.Companion.QueryTimes;

/// <summary>
/// Times every request Nitrate tagged, from arriving to its answer being sent, and keeps it for
/// the user who made it. Requests without the tag pass straight through.
/// </summary>
public class QueryTimingMiddleware
{
    private const string UserIdClaim = "Jellyfin-UserId";
    private readonly RequestDelegate _next;
    private readonly QueryTimeStore _store;

    /// <summary>Initializes a new instance of the <see cref="QueryTimingMiddleware"/> class.</summary>
    public QueryTimingMiddleware(RequestDelegate next, QueryTimeStore store)
    {
        _next = next;
        _store = store;
    }

    /// <summary>Handles one request.</summary>
    public async Task InvokeAsync(HttpContext context)
    {
        _store.MarkActive();
        var tag = context.Request.Headers[QueryTimeStore.Header].ToString();
        if (!QueryTimeStore.ValidTag(tag))
        {
            await _next(context).ConfigureAwait(false);
            return;
        }

        var watch = Stopwatch.StartNew();
        try
        {
            await _next(context).ConfigureAwait(false);
        }
        finally
        {
            watch.Stop();
            // Signed in by the time the answer is made; anyone else's request isn't kept.
            var id = context.User?.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
            if (Guid.TryParse(id, out var user))
            {
                _store.Record(user, tag, watch.ElapsedMilliseconds);
            }
        }
    }
}

/// <summary>Puts <see cref="QueryTimingMiddleware"/> first in the server's request pipeline.</summary>
public class QueryTimingStartupFilter : IStartupFilter
{
    /// <inheritdoc />
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
    {
        app.UseMiddleware<QueryTimingMiddleware>();
        next(app);
    };
}
