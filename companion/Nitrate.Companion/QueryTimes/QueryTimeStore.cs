using System.Text.RegularExpressions;

namespace Nitrate.Companion.QueryTimes;

/// <summary>The server's time for one request the app tagged.</summary>
/// <param name="Tag">The app's tag ("run-id").</param>
/// <param name="Ms">Milliseconds from the request arriving to its answer being sent.</param>
public record QueryTime(string Tag, long Ms);

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
    private readonly Dictionary<Guid, LinkedList<(string Tag, long Ms, DateTimeOffset At)>> _byUser = new();
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
    public void Record(Guid user, string tag, long ms)
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

                list = new LinkedList<(string, long, DateTimeOffset)>();
                _byUser[user] = list;
            }

            list.AddLast((tag, ms, now));
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

    [GeneratedRegex("^[a-z0-9-]+$")]
    private static partial Regex TagPattern();
}
