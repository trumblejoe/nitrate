using System.Text.Json;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>One kind of request on one day: how many, and their times as a histogram (bucket n holds 2^(n-1)…2^n ms).</summary>
public record QueryDay(string Day, string Shape, long Count, long TotalMs, long[] Buckets)
{
    /// <summary>Buckets kept (up to ~9 minutes).</summary>
    public const int Size = 20;

    /// <summary>The bucket a time falls in.</summary>
    public static int Bucket(long ms) => ms <= 0 ? 0 : Math.Min(Size - 1, 64 - (int)ulong.LeadingZeroCount((ulong)ms));

    /// <summary>The time [share] of requests came under (the bucket's upper edge).</summary>
    public long Under(double share)
    {
        var need = Math.Max(1, (long)Math.Ceiling(Count * share));
        long seen = 0;
        for (var i = 0; i < Buckets.Length; i++)
        {
            seen += Buckets[i];
            if (seen >= need)
            {
                return i == 0 ? 1 : 1L << i;
            }
        }

        return 1L << (Buckets.Length - 1);
    }

    /// <summary>This day's numbers with more requests added.</summary>
    public QueryDay With(IEnumerable<long> times)
    {
        var buckets = (long[])Buckets.Clone();
        long count = Count, total = TotalMs;
        foreach (var t in times)
        {
            buckets[Bucket(t)]++;
            count++;
            total += t;
        }

        return this with { Count = count, TotalMs = total, Buckets = buckets };
    }
}

/// <summary>A kind of request over the days asked, for admins: typical and slow times by day.</summary>
public record QueryTrend(string Shape, IReadOnlyList<QueryTrendDay> Days);

/// <summary>One day of a <see cref="QueryTrend"/>.</summary>
public record QueryTrendDay(string Day, long Count, long MedianMs, long SlowMs);

/// <summary>
/// Every request kind's times by day, kept 90 days, rolled up hourly from the in-memory timings
/// (<see cref="QueryTimes.QueryTimeStore"/>), so the query analyzer can show whether things got
/// faster. Request kinds only: never who asked, what was watched or searched.
/// </summary>
public class QueryTrendStore
{
    /// <summary>How long days are kept.</summary>
    public const int KeepDays = 90;

    private const string Kind = "query-day";
    private const string Owner = "all";
    private readonly CompanionDb _db;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="QueryTrendStore"/> class.</summary>
    public QueryTrendStore(CompanionDb db) => _db = db;

    /// <summary>Adds requests timed (shape, ms, when) to their days.</summary>
    public void Add(IEnumerable<(string Shape, long Ms, DateTimeOffset At)> timed)
    {
        lock (_lock)
        {
            foreach (var group in timed.GroupBy(t => (Day: t.At.UtcDateTime.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture), t.Shape)))
            {
                var key = group.Key.Day + "|" + group.Key.Shape;
                var before = _db.Record(Kind, Owner, key) is { } json ? Read(json) : null;
                var day = (before ?? new QueryDay(group.Key.Day, group.Key.Shape, 0, 0, new long[QueryDay.Size])).With(group.Select(t => t.Ms));
                var at = DateTimeOffset.Parse(group.Key.Day + "T00:00:00Z", System.Globalization.CultureInfo.InvariantCulture).ToUnixTimeMilliseconds();
                _db.PutRecord(Kind, Owner, key, JsonSerializer.Serialize(day), at);
            }
        }
    }

    /// <summary>The last [days] days, kinds with the most time spent first.</summary>
    public IReadOnlyList<QueryTrend> Trends(int days, DateTimeOffset now)
    {
        var from = now.UtcDateTime.Date.AddDays(-Math.Clamp(days, 1, KeepDays));
        var all = _db.Records(Kind, Owner).Select(r => Read(r.Value)).OfType<QueryDay>()
            .Where(d => DateTime.Parse(d.Day, System.Globalization.CultureInfo.InvariantCulture) >= from);
        return all.GroupBy(d => d.Shape)
            .OrderByDescending(g => g.Sum(d => d.TotalMs))
            .Select(g => new QueryTrend(g.Key, g.OrderBy(d => d.Day, StringComparer.Ordinal).Select(d => new QueryTrendDay(d.Day, d.Count, d.Under(0.5), d.Under(0.9))).ToList()))
            .ToList();
    }

    /// <summary>Forgets days past <see cref="KeepDays"/>.</summary>
    public void Prune(DateTimeOffset now) => _db.DeleteOlder(Kind, now.AddDays(-KeepDays).ToUnixTimeMilliseconds());

    private static QueryDay? Read(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<QueryDay>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/// <summary>Rolls the last hour's request timings into their days (hourly).</summary>
public class QueryTrendRecorder : Microsoft.Extensions.Hosting.BackgroundService
{
    private readonly QueryTimes.QueryTimeStore _times;
    private readonly QueryTrendStore _trends;
    private DateTimeOffset _since = DateTimeOffset.UtcNow;

    /// <summary>Initializes a new instance of the <see cref="QueryTrendRecorder"/> class.</summary>
    public QueryTrendRecorder(QueryTimes.QueryTimeStore times, QueryTrendStore trends)
    {
        _times = times;
        _trends = trends;
    }

    /// <summary>Rolls up what was timed since last time.</summary>
    public void RollUp(DateTimeOffset now)
    {
        _trends.Add(_times.Since(_since, now));
        _since = now;
        _trends.Prune(now);
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromHours(1), stoppingToken).ConfigureAwait(false);
                RollUp(DateTimeOffset.UtcNow);
            }
        }
        catch (OperationCanceledException)
        {
            // The server is stopping: what's left of the hour goes now.
            RollUp(DateTimeOffset.UtcNow);
        }
    }
}
