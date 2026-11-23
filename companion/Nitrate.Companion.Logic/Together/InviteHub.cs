namespace Nitrate.Companion.Together;

/// <summary>An invite to join someone's watch-together (SyncPlay) group.</summary>
/// <param name="Id">The invite's id.</param>
/// <param name="FromUserId">Who sent it.</param>
/// <param name="FromName">Their name.</param>
/// <param name="ToUserId">Who it's for.</param>
/// <param name="GroupId">The SyncPlay group to join.</param>
/// <param name="ItemId">What's playing (or about to), if known.</param>
/// <param name="ItemName">Its name.</param>
/// <param name="At">When it was sent.</param>
public record WatchInvite(string Id, Guid FromUserId, string FromName, Guid ToUserId, string GroupId, string? ItemId, string? ItemName, DateTimeOffset At);

/// <summary>
/// Watch-together invites, kept in memory for a few hours (an invite is for now, not for later).
/// Invites to people who can't receive them (private accounts, unknown ids) are dropped without a
/// word, so the sender can never tell whether an account exists or is private.
/// </summary>
public class InviteHub
{
    /// <summary>How long an invite stays open.</summary>
    public static readonly TimeSpan Keep = TimeSpan.FromHours(3);

    /// <summary>At most this many people per invite.</summary>
    public const int MaxRecipients = 20;

    private const int MaxStored = 500;
    private readonly TimeProvider _time;
    private readonly List<WatchInvite> _invites = new();
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="InviteHub"/> class.</summary>
    public InviteHub(TimeProvider time) => _time = time;

    /// <summary>Sends an invite to each of [to] who [canReceive] allows (never the sender).</summary>
    public void Send(Guid from, string fromName, IEnumerable<Guid> to, string groupId, string? itemId, string? itemName, Func<Guid, bool> canReceive)
    {
        if (string.IsNullOrWhiteSpace(groupId))
        {
            throw new ArgumentException("A group is needed.", nameof(groupId));
        }

        var now = _time.GetUtcNow();
        lock (_lock)
        {
            Prune(now);
            foreach (var user in to.Distinct().Take(MaxRecipients).Where(u => u != from && canReceive(u)))
            {
                // A newer invite to the same group replaces the older one.
                _invites.RemoveAll(i => i.ToUserId == user && i.GroupId == groupId);
                _invites.Add(new WatchInvite(Guid.NewGuid().ToString("N"), from, fromName, user, groupId, itemId, itemName, now));
            }

            if (_invites.Count > MaxStored)
            {
                _invites.RemoveRange(0, _invites.Count - MaxStored);
            }
        }
    }

    /// <summary>The open invites for [user], newest first.</summary>
    public IReadOnlyList<WatchInvite> For(Guid user)
    {
        lock (_lock)
        {
            Prune(_time.GetUtcNow());
            return _invites.Where(i => i.ToUserId == user).OrderByDescending(i => i.At).ToList();
        }
    }

    /// <summary>Removes one of [user]'s invites (joined or turned down); false when there was none.</summary>
    public bool Dismiss(Guid user, string id)
    {
        lock (_lock)
        {
            return _invites.RemoveAll(i => i.Id == id && i.ToUserId == user) > 0;
        }
    }

    /// <summary>The invites, for the next logic.</summary>
    public IReadOnlyList<WatchInvite> ExportState()
    {
        lock (_lock)
        {
            return _invites.ToList();
        }
    }

    /// <summary>Takes the invites the previous logic had.</summary>
    public void ImportState(IEnumerable<WatchInvite> invites)
    {
        lock (_lock)
        {
            _invites.AddRange(invites);
            Prune(_time.GetUtcNow());
        }
    }

    private void Prune(DateTimeOffset now) => _invites.RemoveAll(i => now - i.At > Keep);
}
