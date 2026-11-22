using System.Text.RegularExpressions;

namespace Nitrate.Companion.QueryTimes;

/// <summary>The server's time for one request the app tagged.</summary>
/// <param name="Tag">The app's tag ("run-id").</param>
/// <param name="Ms">Milliseconds from the request arriving to its answer being sent.</param>
public record QueryTime(string Tag, long Ms);

/// <summary>One kind of request across the household: how often and how long the server took.</summary>
/// <param name="Shape">Method, path with ids as {id}, and the names (never the values) of its parameters.</param>
/// <param name="Count">How many times it was asked.</param>
/// <param name="People">How many different people asked it.</param>
/// <param name="TotalMs">The server's time for them all.</param>
/// <param name="MedianMs">The middle time.</param>
/// <param name="SlowMs">The time nine in ten came under.</param>
/// <param name="MaxMs">The slowest.</param>
public record HouseholdQuery(string Shape, int Count, int People, long TotalMs, long MedianMs, long SlowMs, long MaxMs);

/// <summary>
/// The server's own times for requests Nitrate tagged (header <c>X-Nitrate-Query</c>), kept in
/// memory per user for a while, so each app can set them beside what it waited (the query
/// analyzer). A user only ever reads their own.
/// </summary>
public partial class QueryTimeStore
{
    /// <summary>The request header carrying the app's tag.</summary>
    public const string Header = "X-Nitrate-Query";

    /// <summary>Times kept per user.</summary>
    public const int PerUser = 3000;

    /// <summary>Users kept at once (the least recently timed go first).</summary>
    public const int Users = 100;

    /// <summary>How long a time is kept.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromHours(2);

    private readonly object _lock = new();
    private readonly Dictionary<Guid, LinkedList<(string Tag, long Ms, DateTimeOffset At, string Shape)>> _byUser = new();
    private readonly TimeProvider _time;

    /// <summary>Initializes a new instance of the <see cref="QueryTimeStore"/> class.</summary>
    public QueryTimeStore(TimeProvider time) => _time = time;

    /// <summary>Gets a value indicating whether a request has been timed since the server started (the middleware is in place).</summary>
    public bool Active { get; private set; }

    /// <summary>Notes that the timing middleware is running.</summary>
    public void MarkActive() => Active = true;

    /// <summary>Whether a tag is one the apps send: short, lower-case letters, digits and dashes.</summary>
    public static bool ValidTag(string? tag) => tag != null && tag.Length is > 0 and <= 40 && TagPattern().IsMatch(tag);

    /// <summary>Keeps the server's time for a tagged request.</summary>
    public void Record(Guid user, string tag, long ms, string shape = "")
    {
        if (user == Guid.Empty || !ValidTag(tag))
        {
            return;
        }

        var now = _time.GetUtcNow();
        lock (_lock)
        {
            if (!_byUser.TryGetValue(user, out var list))
            {
                if (_byUser.Count >= Users)
                {
                    var oldest = _byUser.MinBy(kv => kv.Value.Last?.Value.At ?? DateTimeOffset.MinValue).Key;
                    _byUser.Remove(oldest);
                }

                list = new LinkedList<(string, long, DateTimeOffset, string)>();
                _byUser[user] = list;
            }

            list.AddLast((tag, ms, now, shape));
            while (list.Count > PerUser || (list.First != null && now - list.First.Value.At > Keep))
            {
                list.RemoveFirst();
            }
        }
    }

    /// <summary>The user's own times for one app run (tags starting "run-").</summary>
    public IReadOnlyList<QueryTime> For(Guid user, string run)
    {
        var prefix = run + "-";
        var now = _time.GetUtcNow();
        lock (_lock)
        {
            if (!_byUser.TryGetValue(user, out var list))
            {
                return Array.Empty<QueryTime>();
            }

            return list.Where(t => t.Tag.StartsWith(prefix, StringComparison.Ordinal) && now - t.At <= Keep)
                .Select(t => new QueryTime(t.Tag, t.Ms)).ToList();
        }
    }

    /// <summary>
    /// Every person's requests of the last <see cref="Keep"/>, by kind, the costliest first: no names,
    /// no titles, no search words (shapes carry parameter names only). For admins.
    /// </summary>
    public IReadOnlyList<HouseholdQuery> Household()
    {
        var now = _time.GetUtcNow();
        List<(Guid User, long Ms, string Shape)> all;
        lock (_lock)
        {
            all = _byUser.SelectMany(kv => kv.Value.Where(t => now - t.At <= Keep && t.Shape.Length > 0).Select(t => (kv.Key, t.Ms, t.Shape))).ToList();
        }

        return all.GroupBy(t => t.Shape).Select(g =>
        {
            var times = g.Select(t => t.Ms).OrderBy(m => m).ToList();
            return new HouseholdQuery(
                g.Key,
                times.Count,
                g.Select(t => t.User).Distinct().Count(),
                times.Sum(),
                times[times.Count / 2],
                times[Math.Min(times.Count - 1, (int)Math.Ceiling(times.Count * 0.9) - 1)],
                times[^1]);
        }).OrderByDescending(h => h.TotalMs).ToList();
    }

    /// <summary>A request's shape: method, path with ids as {id}, sorted parameter names (never their values).</summary>
    public static string ShapeOf(string method, string path, IEnumerable<string> parameterNames)
    {
        var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Select(s => Guid.TryParse(s, out _) ? "{id}" : s);
        var names = parameterNames.Where(n => !Paging.Contains(n)).Select(n => n.ToLowerInvariant()).Distinct().OrderBy(n => n, StringComparer.Ordinal).ToList();
        var shape = (method == "GET" ? string.Empty : method + " ") + "/" + string.Join('/', segments);
        return names.Count == 0 ? shape : shape + "?" + string.Join('&', names);
    }

    private static readonly HashSet<string> Paging = new(StringComparer.OrdinalIgnoreCase) { "startIndex", "limit", "api_key", "_" };

    [GeneratedRegex("^[a-z0-9-]+$")]
    private static partial Regex TagPattern();
}
