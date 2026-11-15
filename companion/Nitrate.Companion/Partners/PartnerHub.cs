namespace Nitrate.Companion.Partners;

/// <summary>Why a watch-group request was refused.</summary>
public enum PartnerError
{
    /// <summary>Nothing of the caller's by that id.</summary>
    NotFound,

    /// <summary>The request can't be made as sent (the message says why).</summary>
    Invalid,
}

/// <summary>A refused watch-group request.</summary>
public class PartnerException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="PartnerException"/> class.</summary>
    public PartnerException(PartnerError error, string message)
        : base(message) => Error = error;

    /// <summary>Gets why.</summary>
    public PartnerError Error { get; }
}

/// <summary>Someone in a group, as another member sees them.</summary>
public record MemberView(Guid Id, string Name);

/// <summary>A watch group, as one member sees it: the others in it.</summary>
public record GroupView(Guid Id, Guid SeriesId, string SeriesName, IReadOnlyList<MemberView> Others, DateTimeOffset Since);

/// <summary>
/// An invite someone sent the caller. <paramref name="WillSee"/> is everyone who learns the
/// caller's name and progress by accepting (the inviter and the group's other members).
/// </summary>
public record IncomingView(Guid Id, Guid FromId, string FromName, Guid SeriesId, string SeriesName, Guid? GroupId, IReadOnlyList<string> WillSee, DateTimeOffset At);

/// <summary>An invite the caller sent: always shown as waiting until it's accepted or lapses.</summary>
public record OutgoingView(Guid Id, string To, Guid SeriesId, string SeriesName, Guid? GroupId, DateTimeOffset At);

/// <summary>Where another member of one of the caller's groups is in the group's show.</summary>
public record PartnerProgressView(Guid GroupId, Guid UserId, string UserName, IReadOnlyList<Guid> Watched, string? Last, Guid? NextId);

/// <summary>
/// Watch groups: people who watch a show together and see each other's progress in it. A pair
/// is a group of two; the same show can have several separate groups. Any member can invite
/// someone; the invited person accepts. Private accounts (marked by an admin, or by themselves)
/// never appear in any list and can't be found by name; an invite to a typed name always reads
/// "sent" and stays waiting until it's accepted or lapses, whether or not that account exists,
/// was declined, or is private. Before accepting, a person sees exactly who will learn their name.
/// </summary>
public class PartnerHub
{
    /// <summary>How long a request waits.</summary>
    public static readonly TimeSpan RequestLifetime = TimeSpan.FromDays(14);

    private const int MaxOutgoing = 20;
    private readonly IPartnerStorage _storage;
    private readonly IPartnerDirectory _directory;
    private readonly IWatchState _watch;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly PartnerState _state;

    /// <summary>Initializes a new instance of the <see cref="PartnerHub"/> class.</summary>
    public PartnerHub(IPartnerStorage storage, IPartnerDirectory directory, IWatchState watch, TimeProvider time)
    {
        _storage = storage;
        _directory = directory;
        _watch = watch;
        _time = time;
        _state = storage.Load();
    }

    /// <summary>Whether the account is private: by an admin or by its owner.</summary>
    public bool IsPrivate(Guid userId)
    {
        lock (_lock)
        {
            return _directory.AdminPrivate(userId) || _state.SelfPrivate.Contains(userId);
        }
    }

    /// <summary>Whether the caller made themselves private, and whether an admin did.</summary>
    public (bool Self, bool ByAdmin) Privacy(Guid userId)
    {
        lock (_lock)
        {
            return (_state.SelfPrivate.Contains(userId), _directory.AdminPrivate(userId));
        }
    }

    /// <summary>Makes the caller private (or not).</summary>
    public void SetPrivate(Guid userId, bool on)
    {
        lock (_lock)
        {
            _state.SelfPrivate.Remove(userId);
            if (on)
            {
                _state.SelfPrivate.Add(userId);
            }

            _storage.Save(_state);
        }
    }

    /// <summary>The people the caller can see: enabled, not private, not themselves.</summary>
    public IReadOnlyList<(Guid Id, string Name)> VisibleTo(Guid caller) =>
        _directory.Users().Where(u => u.Id != caller && !IsPrivate(u.Id)).OrderBy(u => u.Name, StringComparer.OrdinalIgnoreCase).ToList();

    /// <summary>The visible account named exactly <paramref name="name"/>, or null (also for private ones).</summary>
    public (Guid Id, string Name)? FindVisible(string name) =>
        _directory.Find(name) is { } u && !IsPrivate(u.Id) ? u : null;

    /// <summary>
    /// Invites someone to watch a show together: into one of the caller's groups for it
    /// (<paramref name="groupId"/>), or as a new group with the caller. By id (someone the caller
    /// can see, or already shares a group with) or by exact name (anyone, private too). Always
    /// "sent": nothing tells the caller whether the account exists.
    /// </summary>
    public OutgoingView Request(Guid from, Guid? toId, string? toName, Guid seriesId, string seriesName, Guid? groupId = null)
    {
        lock (_lock)
        {
            Prune();
            WatchGroup? group = null;
            if (groupId is { } gid && gid != Guid.Empty)
            {
                group = _state.Groups.FirstOrDefault(g => g.Id == gid && g.Members.Contains(from))
                    ?? throw new PartnerException(PartnerError.NotFound, "You're not in that group any more.");
                seriesId = group.SeriesId;
                seriesName = group.SeriesName;
            }

            if (seriesId == Guid.Empty)
            {
                throw new PartnerException(PartnerError.Invalid, "Choose a show to watch together.");
            }

            if (_state.Requests.Count(r => r.From == from) >= MaxOutgoing)
            {
                throw new PartnerException(PartnerError.Invalid, "You have too many invites waiting. Cancel some first.");
            }

            Guid to;
            string label;
            if (toId is { } id)
            {
                // By id only for people the caller can see or already knows; anything else is treated as nobody.
                var name = _directory.NameOf(id);
                var known = name is not null && (!IsPrivate(id) || SharesGroup(from, id));
                to = known && id != from ? id : Guid.Empty;
                label = known ? name! : "Someone";
            }
            else if (!string.IsNullOrWhiteSpace(toName))
            {
                label = toName.Trim();
                var found = _directory.Find(label);
                to = found is { } f && f.Id != from ? f.Id : Guid.Empty;
            }
            else
            {
                throw new PartnerException(PartnerError.Invalid, "Choose who to watch with, or type their user name.");
            }

            // Already in that group: say so only when the caller knows them anyway (they're a member).
            if (group is not null && to != Guid.Empty && group.Members.Contains(to))
            {
                throw new PartnerException(PartnerError.Invalid, $"{label} is already in this group.");
            }

            var gidOrEmpty = group?.Id ?? Guid.Empty;
            var existing = _state.Requests.FirstOrDefault(r => r.From == from && r.SeriesId == seriesId && r.GroupId == gidOrEmpty
                && (to != Guid.Empty ? r.To == to : string.Equals(r.ToLabel, label, StringComparison.OrdinalIgnoreCase)));
            if (existing is null)
            {
                existing = new PartnerRequest
                {
                    Id = Guid.NewGuid(), From = from, To = to, ToLabel = label, GroupId = gidOrEmpty,
                    SeriesId = seriesId, SeriesName = seriesName.Trim(), At = _time.GetUtcNow(),
                };
                _state.Requests.Add(existing);
                _storage.Save(_state);
            }

            return Outgoing(existing);
        }
    }

    /// <summary>Invites waiting for the caller's answer, each with who'd learn their name by accepting.</summary>
    public IReadOnlyList<IncomingView> Incoming(Guid user)
    {
        lock (_lock)
        {
            Prune();
            return _state.Requests.Where(r => r.To == user && !r.Declined)
                .Select(r =>
                {
                    var group = r.GroupId == Guid.Empty ? null : _state.Groups.FirstOrDefault(g => g.Id == r.GroupId);
                    var willSee = (group?.Members ?? new List<Guid> { r.From }).Append(r.From).Distinct()
                        .Where(id => id != user).Select(id => _directory.NameOf(id)).OfType<string>().ToList();
                    return new IncomingView(r.Id, r.From, _directory.NameOf(r.From) ?? "Someone", r.SeriesId, r.SeriesName, group?.Id, willSee, r.At);
                })
                .OrderBy(r => r.At).ToList();
        }
    }

    /// <summary>The caller's own invites, all shown as waiting.</summary>
    public IReadOnlyList<OutgoingView> Outgoing(Guid user)
    {
        lock (_lock)
        {
            Prune();
            return _state.Requests.Where(r => r.From == user).Select(Outgoing).OrderBy(r => r.At).ToList();
        }
    }

    /// <summary>Says yes: joins the group (or makes a new one with the inviter).</summary>
    public GroupView Accept(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            Prune();
            var r = _state.Requests.FirstOrDefault(x => x.Id == requestId && x.To == user && !x.Declined)
                ?? throw new PartnerException(PartnerError.NotFound, "That invite has lapsed.");
            _state.Requests.Remove(r);
            WatchGroup group;
            if (r.GroupId == Guid.Empty)
            {
                group = new WatchGroup { Id = Guid.NewGuid(), SeriesId = r.SeriesId, SeriesName = r.SeriesName, Members = new() { r.From, user }, Since = _time.GetUtcNow() };
                _state.Groups.Add(group);
            }
            else
            {
                // The inviter must still be in it, or the invite no longer stands.
                group = _state.Groups.FirstOrDefault(g => g.Id == r.GroupId && g.Members.Contains(r.From))
                    ?? throw new PartnerException(PartnerError.NotFound, "That group no longer exists.");
                if (!group.Members.Contains(user))
                {
                    group.Members.Add(user);
                }
            }

            _storage.Save(_state);
            return View(group, user);
        }
    }

    /// <summary>Says no. The inviter isn't told: their invite waits until it lapses.</summary>
    public void Decline(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            var r = _state.Requests.FirstOrDefault(x => x.Id == requestId && x.To == user && !x.Declined)
                ?? throw new PartnerException(PartnerError.NotFound, "That invite has lapsed.");
            r.Declined = true;
            _storage.Save(_state);
        }
    }

    /// <summary>The inviter withdraws an invite.</summary>
    public void Cancel(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            if (_state.Requests.RemoveAll(x => x.Id == requestId && x.From == user) == 0)
            {
                throw new PartnerException(PartnerError.NotFound, "No such invite.");
            }

            _storage.Save(_state);
        }
    }

    /// <summary>The caller's groups.</summary>
    public IReadOnlyList<GroupView> Groups(Guid user)
    {
        lock (_lock)
        {
            return _state.Groups.Where(g => g.Members.Contains(user)).Select(g => View(g, user))
                .OrderBy(g => g.SeriesName, StringComparer.OrdinalIgnoreCase).ToList();
        }
    }

    /// <summary>Leaves a group; a group left with one person ends.</summary>
    public void Leave(Guid user, Guid groupId)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            g.Members.Remove(user);
            if (g.Members.Count < 2)
            {
                _state.Groups.Remove(g);
            }

            // Invites the leaver sent into it no longer stand.
            _state.Requests.RemoveAll(r => r.From == user && r.GroupId == groupId);
            _storage.Save(_state);
        }
    }

    /// <summary>Where the other members of each of the caller's groups on a show are.</summary>
    public IReadOnlyList<PartnerProgressView> Progress(Guid user, Guid seriesId)
    {
        List<(Guid Group, Guid Member)> members;
        lock (_lock)
        {
            members = _state.Groups.Where(g => g.SeriesId == seriesId && g.Members.Contains(user))
                .SelectMany(g => g.Members.Where(m => m != user).Select(m => (g.Id, m))).ToList();
        }

        var cache = new Dictionary<Guid, ShowProgress>();
        return members.Where(x => _directory.NameOf(x.Member) is not null).Select(x =>
        {
            if (!cache.TryGetValue(x.Member, out var p))
            {
                p = _watch.Progress(x.Member, seriesId);
                cache[x.Member] = p;
            }

            return new PartnerProgressView(x.Group, x.Member, _directory.NameOf(x.Member)!, p.Watched, p.Last, p.NextId);
        }).ToList();
    }

    /// <summary>
    /// Watched together with a group: marks the episode watched for the group's other members.
    /// Only for an episode of the group's show, and only by a member. Returns who it was marked for.
    /// </summary>
    public IReadOnlyList<Guid> MarkTogether(Guid user, Guid itemId, Guid groupId)
    {
        var series = _watch.SeriesOf(itemId) ?? throw new PartnerException(PartnerError.Invalid, "Only episodes of a group's show can be marked together.");
        List<Guid> others;
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user) && x.SeriesId == series);
            others = g?.Members.Where(m => m != user && _directory.NameOf(m) is not null).ToList() ?? new List<Guid>();
        }

        foreach (var id in others)
        {
            _watch.MarkWatched(id, itemId);
        }

        return others;
    }

    private bool SharesGroup(Guid a, Guid b) => _state.Groups.Any(g => g.Members.Contains(a) && g.Members.Contains(b));

    private GroupView View(WatchGroup g, Guid user) => new(
        g.Id, g.SeriesId, g.SeriesName,
        g.Members.Where(m => m != user).Select(m => new MemberView(m, _directory.NameOf(m) ?? "Former account")).ToList(),
        g.Since);

    private static OutgoingView Outgoing(PartnerRequest r) => new(r.Id, r.ToLabel, r.SeriesId, r.SeriesName, r.GroupId == Guid.Empty ? null : r.GroupId, r.At);

    private void Prune()
    {
        var cutoff = _time.GetUtcNow() - RequestLifetime;
        if (_state.Requests.RemoveAll(r => r.At < cutoff) > 0)
        {
            _storage.Save(_state);
        }
    }
}
