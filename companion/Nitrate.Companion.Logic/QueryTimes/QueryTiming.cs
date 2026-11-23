using Microsoft.AspNetCore.Http;

namespace Nitrate.Companion.QueryTimes;

/// <summary>
/// Keeps the server's time for every request Nitrate tagged, for the user who made it. The shell's
/// middleware (first in the server's pipeline) times every request and hands it here; requests
/// without the tag aren't kept.
/// </summary>
public static class QueryTiming
{
    private const string UserIdClaim = "Jellyfin-UserId";

    /// <summary>A request arrived: the timing is in place.</summary>
    public static void Starting(QueryTimeStore store) => store.MarkActive();

    /// <summary>A request was answered after <paramref name="elapsed"/>.</summary>
    public static void Finished(QueryTimeStore store, HttpContext context, TimeSpan elapsed)
    {
        var tag = context.Request.Headers[QueryTimeStore.Header].ToString();
        if (!QueryTimeStore.ValidTag(tag))
        {
            return;
        }

        // Signed in by the time the answer is made; anyone else's request isn't kept.
        var id = context.User?.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        if (Guid.TryParse(id, out var user))
        {
            var shape = QueryTimeStore.ShapeOf(context.Request.Method, context.Request.Path.Value ?? string.Empty, context.Request.Query.Keys);
            store.Record(user, tag, (long)elapsed.TotalMilliseconds, shape);
        }
    }
}
