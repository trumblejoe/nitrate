namespace Nitrate.Companion.Metadata;

/// <summary>
/// Per-editor limits (admins aren't limited): at most <see cref="ChangesPerMinute"/> changes in
/// any minute (a sliding window) and <see cref="RefreshesAtOnce"/> refreshes running at once.
/// Kept in memory only and started afresh by a new logic after a swap (as other rate limits are:
/// see companion-hotswap.md), so a swap at worst lets an editor start one more minute's worth.
/// </summary>
public sealed class MetadataLimiter
{
    /// <summary>Changes an editor may make in any minute.</summary>
    public const int ChangesPerMinute = 30;

    /// <summary>Refreshes (including matches being applied) an editor may have running at once.</summary>
    public const int RefreshesAtOnce = 4;

    // A queued refresh counts as running this long even before the server shows it queued or running.
    private const long Grace = 10_000;

    // A refresh still counted after this long is taken as finished whatever the server says.
    private const long Longest = 6 * 3_600_000;

    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, Queue<long>> _changes = new();
    private readonly Dictionary<Guid, List<(Guid Item, long At, bool Inline)>> _refreshes = new();

    /// <summary>Initializes a new instance of the <see cref="MetadataLimiter"/> class.</summary>
    public MetadataLimiter(TimeProvider time) => _time = time;

    /// <summary>Takes one change from [user]'s minute; false when they've used them all.</summary>
    public bool TryChange(Guid user)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
            if (!HasChange(user, now))
            {
                return false;
            }

            _changes[user].Enqueue(now);
            return true;
        }
    }

    /// <summary>
    /// Takes one change and one refresh slot for [item]; false (nothing taken) when either is used
    /// up. A queued refresh counts until [running] says the server no longer has it queued or
    /// running (after a short grace); an inline one (a match applied while the request waits)
    /// until it's handed back with <see cref="Finished"/>.
    /// </summary>
    public bool TryRefresh(Guid user, Guid item, Func<Guid, bool> running, bool inline = false)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
            var list = _refreshes.TryGetValue(user, out var l) ? l : _refreshes[user] = new();
            list.RemoveAll(r => now - r.At > Longest || (!r.Inline && now - r.At > Grace && !running(r.Item)));
            if (list.Count >= RefreshesAtOnce || !HasChange(user, now))
            {
                return false;
            }

            _changes[user].Enqueue(now);
            list.Add((item, now, inline));
            return true;
        }
    }

    /// <summary>An inline refresh of [item] is done.</summary>
    public void Finished(Guid user, Guid item)
    {
        lock (_lock)
        {
            if (_refreshes.TryGetValue(user, out var list))
            {
                var i = list.FindIndex(r => r.Inline && r.Item == item);
                if (i >= 0)
                {
                    list.RemoveAt(i);
                }
            }
        }
    }

    private bool HasChange(Guid user, long now)
    {
        var window = _changes.TryGetValue(user, out var q) ? q : _changes[user] = new();
        while (window.Count > 0 && now - window.Peek() >= 60_000)
        {
            window.Dequeue();
        }

        return window.Count < ChangesPerMinute;
    }
}
