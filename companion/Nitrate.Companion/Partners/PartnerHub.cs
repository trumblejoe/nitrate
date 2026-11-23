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

/// <summary>A change to a group's schedule waiting for votes, as one member sees it.</summary>
public record ScheduleChangeView(Guid Id, string FromName, GroupSchedule Proposed, IReadOnlyList<string> Yes, IReadOnlyList<string> No, bool Voted, DateTimeOffset At);

/// <summary>
/// A watch group, as one member sees it: the others in it, its schedule, a change waiting for
/// votes, and what's locked now (<paramref name="LockedFromId"/> and everything after it in the
/// title; it opens at <paramref name="OpensAt"/>, or once someone watches the part before it),
/// and for a together-only group the first part nobody in it has seen (<paramref name="TogetherFromId"/>).
/// A together-only group keeps its own watched record (<paramref name="Record"/>): everything about
/// it then goes by what was watched with the group, and <paramref name="Mine"/> is what the caller
/// watched with it (null for groups that watch separately, which go by Jellyfin's watched state).
/// </summary>
public record GroupView(
    Guid Id, Guid SeriesId, string SeriesName, IReadOnlyList<MemberView> Others, DateTimeOffset Since,
    GroupSchedule? Schedule = null, ScheduleChangeView? Pending = null, Guid? LockedFromId = null, DateTimeOffset? OpensAt = null, int? OpensDay = null,
    Guid? TogetherFromId = null, bool Record = false, IReadOnlyList<Guid>? Mine = null, PartyView? Party = null);

/// <summary>
/// An invite someone sent the caller. <paramref name="WillSee"/> is everyone who learns the
/// caller's name and progress by accepting (the inviter and the group's other members).
/// </summary>
public record IncomingView(Guid Id, Guid FromId, string FromName, Guid SeriesId, string SeriesName, Guid? GroupId, IReadOnlyList<string> WillSee, DateTimeOffset At, GroupSchedule? Schedule = null);

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
public partial class PartnerHub
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
    public OutgoingView Request(Guid from, Guid? toId, string? toName, Guid seriesId, string seriesName, Guid? groupId = null, GroupSchedule? schedule = null)
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
                    // A new group's schedule is the inviter's choice; joining a group takes the group's.
                    Schedule = group is null && schedule is not null && (schedule.Active || schedule.Party is not null) ? Valid(schedule) : null,
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
                    return new IncomingView(r.Id, r.From, _directory.NameOf(r.From) ?? "Someone", r.SeriesId, r.SeriesName, group?.Id, willSee, r.At, group?.Schedule ?? r.Schedule);
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
                var schedule = r.Schedule?.Copy();
                if (schedule is not null)
                {
                    schedule.Since = _time.GetUtcNow();
                }

                group = new WatchGroup { Id = Guid.NewGuid(), SeriesId = r.SeriesId, SeriesName = r.SeriesName, Members = new() { r.From, user }, Since = _time.GetUtcNow(), Schedule = schedule };
                if (KeepsRecord(group))
                {
                    // A new together-only group's record starts from what both have watched already.
                    StartRecord(group);
                }

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
            return View(group, user, Array.Empty<ShowProgress>(), group.Watched.ToList());
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

    /// <summary>The caller's groups, each with what its schedule has locked now.</summary>
    public IReadOnlyList<GroupView> Groups(Guid user)
    {
        List<(WatchGroup Group, List<GroupWatch> Record, List<Guid> Members, PartyView? Party)> mine;
        var said = new List<PartyAnnouncement>();
        lock (_lock)
        {
            PruneChanges();
            var now = _time.GetUtcNow();
            mine = _state.Groups.Where(g => g.Members.Contains(user)).Select(g => (g, g.Watched.ToList(), g.Members.ToList(), PartyStub(g, user, now, said))).ToList();
        }

        Tell(said);

        // Where everyone is, for groups that ration their title, watch it together only or meet for a
        // watch party (outside the lock: it reads the library); a together-only group by its own record.
        return mine.Select(x =>
            {
                var g = x.Group;
                if (g.Schedule is not { Active: true } && x.Party is null)
                {
                    return View(g, user, Array.Empty<ShowProgress>(), x.Record);
                }

                var everyone = x.Members.Select(m => KeepsRecord(g) ? FromRecord(_watch.Progress(m, g.SeriesId), x.Record, m) : _watch.Progress(m, g.SeriesId)).ToList();
                return View(g, user, everyone, x.Record) with { Party = x.Party is { } p ? WithPart(p, KeepsRecord(g), everyone) : null };
            })
            .OrderBy(g => g.SeriesName, StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>
    /// Asks the group to change its schedule ([schedule] Binge: let it all out). The asker counts
    /// as a yes; it takes effect once more than half the group agrees (both, in a pair), and
    /// lapses after <see cref="ScheduleRules.ChangeLifetime"/>. A new ask replaces the caller's
    /// own earlier one; someone else's waiting change must be settled first.
    /// </summary>
    public void ProposeSchedule(Guid user, Guid groupId, GroupSchedule schedule)
    {
        lock (_lock)
        {
            PruneChanges();
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            if (g.Pending is { } p && p.From != user)
            {
                throw new PartnerException(PartnerError.Invalid, "Another change is waiting for votes: answer that one first.");
            }

            g.Pending = new ScheduleChange { Id = Guid.NewGuid(), From = user, Proposed = Valid(schedule), Yes = new() { user }, At = _time.GetUtcNow() };
            Settle(g);
            _storage.Save(_state);
        }
    }

    /// <summary>A member's yes or no to the group's waiting change.</summary>
    public void Vote(Guid user, Guid groupId, Guid changeId, bool yes)
    {
        lock (_lock)
        {
            PruneChanges();
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            var p = g.Pending is { } c && c.Id == changeId ? c : throw new PartnerException(PartnerError.NotFound, "That change has been settled or has lapsed.");
            p.Yes.Remove(user);
            p.No.Remove(user);
            (yes ? p.Yes : p.No).Add(user);
            Settle(g);
            _storage.Save(_state);
        }
    }

    /// <summary>Applies the waiting change once it carries; drops it once it can't.</summary>
    private void Settle(WatchGroup g)
    {
        if (g.Pending is not { } p)
        {
            return;
        }

        var members = g.Members.Count;
        if (ScheduleRules.Passes(p.Yes.Count(g.Members.Contains), members))
        {
            var s = p.Proposed.Copy();
            s.Since = _time.GetUtcNow();
            var wasTogether = KeepsRecord(g);
            g.Schedule = s.Cadence == GroupSchedule.Binge && !s.SoftLock && !s.Together && s.Party is null ? null : s;
            g.Pending = null;
            if (KeepsRecord(g) && !wasTogether)
            {
                // Switched to together only: the record starts from what every member has watched now.
                StartRecord(g);
            }
            else if (!KeepsRecord(g))
            {
                // Separately again: Jellyfin's watched state is the group's progress; a later switch starts afresh.
                g.Watched.Clear();
            }
        }
        else if (ScheduleRules.Fails(p.No.Count(g.Members.Contains), members))
        {
            g.Pending = null;
        }
    }

    private void PruneChanges()
    {
        var cutoff = _time.GetUtcNow() - ScheduleRules.ChangeLifetime;
        var changed = false;
        foreach (var g in _state.Groups.Where(g => g.Pending is { } p && p.At < cutoff))
        {
            g.Pending = null;
            changed = true;
        }

        if (changed)
        {
            _storage.Save(_state);
        }
    }

    /// <summary>A schedule as sent, made sensible: a known cadence, a real time, a known time zone.</summary>
    private static GroupSchedule Valid(GroupSchedule s)
    {
        var v = s.Copy();
        if (v.Cadence is not (GroupSchedule.Binge or GroupSchedule.Days2 or GroupSchedule.Days3 or GroupSchedule.Weekly))
        {
            throw new PartnerException(PartnerError.Invalid, "Choose binge, every 2 days, every 3 days or weekly.");
        }

        v.Hour = Math.Clamp(v.Hour, 0, 23);
        v.Minute = Math.Clamp(v.Minute, 0, 59);
        v.TimeZone = string.IsNullOrWhiteSpace(v.TimeZone) ? string.Empty : ScheduleRules.Zone(v).Id;
        v.Party = PartyRules.Valid(v.Party);
        return v;
    }

    /// <summary>Leaves a group; a group left with one person ends.</summary>
    public void Leave(Guid user, Guid groupId)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            g.Members.Remove(user);
            g.Watched.RemoveAll(w => w.Member == user);
            ForgetInParties(groupId, user);
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
        List<(Guid Group, Guid Member, List<GroupWatch>? Record)> members;
        lock (_lock)
        {
            // A together-only group's members by its own record (what they watched with it), the others by Jellyfin's watched state.
            members = _state.Groups.Where(g => g.SeriesId == seriesId && g.Members.Contains(user))
                .SelectMany(g =>
                {
                    var record = KeepsRecord(g) ? g.Watched.ToList() : null;
                    return g.Members.Where(m => m != user).Select(m => (g.Id, m, record));
                }).ToList();
        }

        var cache = new Dictionary<Guid, ShowProgress>();
        return members.Where(x => _directory.NameOf(x.Member) is not null).Select(x =>
        {
            if (!cache.TryGetValue(x.Member, out var p))
            {
                p = _watch.Progress(x.Member, seriesId);
                cache[x.Member] = p;
            }

            if (x.Record is { } record)
            {
                p = FromRecord(p, record, x.Member);
            }

            return new PartnerProgressView(x.Group, x.Member, _directory.NameOf(x.Member)!, p.Watched, p.Last, p.NextId);
        }).ToList();
    }

    /// <summary>
    /// Watched together with a group: marks the item watched for the group's other members. Only
    /// for part of the group's title (an episode of its show, an item of its list, the film), and
    /// only by a member. Returns who it was marked for.
    /// </summary>
    public IReadOnlyList<Guid> MarkTogether(Guid user, Guid itemId, Guid groupId)
    {
        List<Guid> others;
        Guid title;
        bool record;
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user));
            title = g?.SeriesId ?? Guid.Empty;
            record = g is not null && KeepsRecord(g);
            others = g?.Members.Where(m => m != user && _directory.NameOf(m) is not null).ToList() ?? new List<Guid>();
        }

        if (title == Guid.Empty)
        {
            // Not a member of that group: nobody to mark it for.
            return Array.Empty<Guid>();
        }

        if (!_watch.Belongs(title, itemId, user))
        {
            throw new PartnerException(PartnerError.Invalid, "Only what's in a group's title can be marked together.");
        }

        foreach (var id in others)
        {
            _watch.MarkWatched(id, itemId);
        }

        if (record)
        {
            // Together only: it counts in the group's own record, for the caller and everyone it was marked for.
            Count(groupId, others.Append(user).ToList(), new[] { itemId });
        }

        return others;
    }

    /// <summary>
    /// Watched to the end in a live (SyncPlay) session whose participants are
    /// <paramref name="present"/>: for each together-only group whose title holds
    /// <paramref name="itemId"/> and with at least two of its members there, the part counts in
    /// the group's record for each member who was there; whoever wasn't simply falls behind.
    /// Returns the groups it counted for.
    /// </summary>
    public IReadOnlyList<Guid> RecordLive(Guid itemId, IReadOnlyCollection<Guid> present)
    {
        List<(Guid Id, Guid Title, List<Guid> Here)> candidates;
        lock (_lock)
        {
            candidates = _state.Groups.Where(KeepsRecord)
                .Select(g => (g.Id, g.SeriesId, g.Members.Where(present.Contains).ToList()))
                .Where(x => x.Item3.Count >= 2).ToList();
        }

        // Outside the lock: it reads the library.
        var counted = candidates.Where(c => _watch.Belongs(c.Title, itemId, c.Here[0])).ToList();
        foreach (var c in counted)
        {
            Count(c.Id, c.Here, new[] { itemId });
        }

        return counted.Select(c => c.Id).ToList();
    }

    /// <summary>
    /// Watched to the end outside a live session with the group: it counts for
    /// <paramref name="user"/> in a together-only group's record only as catching up, when someone
    /// else in that group has already watched it with the group. Returns the groups it counted for.
    /// </summary>
    public IReadOnlyList<Guid> RecordCatchUp(Guid user, Guid itemId)
    {
        lock (_lock)
        {
            var groups = _state.Groups.Where(g => KeepsRecord(g) && g.Members.Contains(user)
                && !g.Watched.Any(w => w.Member == user && w.ItemId == itemId)
                && g.Watched.Any(w => w.Member != user && w.ItemId == itemId && g.Members.Contains(w.Member))).ToList();
            foreach (var g in groups)
            {
                g.Watched.Add(new GroupWatch { Member = user, ItemId = itemId, At = _time.GetUtcNow() });
            }

            if (groups.Count > 0)
            {
                _storage.Save(_state);
            }

            return groups.Select(g => g.Id).ToList();
        }
    }

    /// <summary>
    /// "We're in the same room": the caller watched <paramref name="itemIds"/> (parts of the
    /// group's title) with <paramref name="present"/> (members of the group; the caller always
    /// counts as there). In a together-only group it counts in the group's record for everyone
    /// there; in any group it's marked watched for the others there, as "watched it with me" does.
    /// With nobody else there it counts only as catching up (what someone else in a together-only
    /// group already watched with it). Returns who it counted for, the caller included.
    /// </summary>
    public IReadOnlyList<Guid> WatchedWith(Guid user, Guid groupId, IReadOnlyList<Guid> itemIds, IReadOnlyCollection<Guid> present)
    {
        Guid title;
        bool record;
        List<Guid> here;
        HashSet<Guid> groupSeen;
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            if (itemIds.Count == 0)
            {
                throw new PartnerException(PartnerError.Invalid, "Choose what you watched.");
            }

            here = present.Append(user).Distinct().ToList();
            if (here.Any(m => !g.Members.Contains(m)))
            {
                throw new PartnerException(PartnerError.Invalid, "Only people in the group can be counted as there.");
            }

            title = g.SeriesId;
            record = KeepsRecord(g);
            groupSeen = g.Watched.Where(w => w.Member != user && g.Members.Contains(w.Member)).Select(w => w.ItemId).ToHashSet();
        }

        // Outside the lock: it reads the library.
        if (itemIds.Any(i => !_watch.Belongs(title, i, user)))
        {
            throw new PartnerException(PartnerError.Invalid, "Only what's in a group's title can be marked together.");
        }

        if (here.Count < 2)
        {
            if (!record)
            {
                throw new PartnerException(PartnerError.Invalid, "Choose who watched it with you.");
            }

            if (itemIds.Any(i => !groupSeen.Contains(i)))
            {
                throw new PartnerException(PartnerError.Invalid, "On your own, only what the group has already watched together counts.");
            }
        }

        foreach (var m in here.Where(m => m != user))
        {
            foreach (var i in itemIds)
            {
                _watch.MarkWatched(m, i);
            }
        }

        if (record)
        {
            Count(groupId, here, itemIds);
        }

        return here;
    }

    /// <summary>Adds <paramref name="items"/> to a together-only group's record for those of <paramref name="members"/> still in it.</summary>
    private void Count(Guid groupId, IReadOnlyList<Guid> members, IReadOnlyList<Guid> items)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId);
            if (g is null || !KeepsRecord(g))
            {
                return;
            }

            var now = _time.GetUtcNow();
            var changed = false;
            foreach (var m in members.Where(g.Members.Contains))
            {
                foreach (var i in items)
                {
                    if (!g.Watched.Any(w => w.Member == m && w.ItemId == i))
                    {
                        g.Watched.Add(new GroupWatch { Member = m, ItemId = i, At = now });
                        changed = true;
                    }
                }
            }

            if (changed)
            {
                _storage.Save(_state);
            }
        }
    }

    /// <summary>Whether the group keeps its own watched record: it watches together only.</summary>
    private static bool KeepsRecord(WatchGroup g) => g.Schedule is { Together: true };

    /// <summary>
    /// Starts a together-only group's record from what every current member has watched (by
    /// Jellyfin's watched state) now; those parts count as watched before the schedule.
    /// </summary>
    private void StartRecord(WatchGroup g)
    {
        HashSet<Guid>? all = null;
        foreach (var m in g.Members)
        {
            var seen = _watch.Progress(m, g.SeriesId).Watched.ToHashSet();
            if (all is null)
            {
                all = seen;
            }
            else
            {
                all.IntersectWith(seen);
            }
        }

        g.Watched = g.Members.SelectMany(m => (all ?? new HashSet<Guid>()).Select(i => new GroupWatch { Member = m, ItemId = i })).ToList();
    }

    /// <summary>A member's place in a together-only group's title by the group's record (<paramref name="jellyfin"/> gives the order).</summary>
    private ShowProgress FromRecord(ShowProgress jellyfin, IReadOnlyList<GroupWatch> record, Guid member)
    {
        var order = jellyfin.Order ?? Array.Empty<Guid>();
        var entries = record.Where(w => w.Member == member).ToList();
        var ids = entries.Select(w => w.ItemId).ToHashSet();
        var watched = order.Count > 0 ? order.Where(ids.Contains).ToList() : ids.ToList();
        if (watched.Count == 0)
        {
            return new ShowProgress(watched, null, order.Count > 0 ? order[0] : null, order, null);
        }

        var last = watched[^1];
        var next = order.SkipWhile(id => id != last).Skip(1).Where(id => !ids.Contains(id)).Select(id => (Guid?)id).FirstOrDefault();
        var at = entries.First(w => w.ItemId == last).At;
        return new ShowProgress(watched, _watch.Label(last), next, order, at);
    }

    /// <summary>
    /// The caller's groups whose title holds <paramref name="itemId"/> (the title itself, an episode
    /// of its show, an item of its list), each with everyone in it now: who a note pinned there
    /// goes to. Empty for anyone not in such a group.
    /// </summary>
    public IReadOnlyList<Nitrate.Companion.Insights.GroupMembers> GroupsHolding(Guid user, Guid itemId)
    {
        List<(Guid Id, Guid Title, string Name, List<Guid> Members)> mine;
        lock (_lock)
        {
            mine = _state.Groups.Where(g => g.Members.Contains(user)).Select(g => (g.Id, g.SeriesId, g.SeriesName, g.Members.ToList())).ToList();
        }

        // Outside the lock: it reads the library (once per title).
        var holds = new Dictionary<Guid, bool>();
        return mine.Where(g => g.Title == itemId || (holds.TryGetValue(g.Title, out var h) ? h : holds[g.Title] = _watch.Belongs(g.Title, itemId, user)))
            .Select(g => new Nitrate.Companion.Insights.GroupMembers(g.Id, g.Name, g.Members.Where(m => _directory.NameOf(m) is not null).ToList()))
            .ToList();
    }

    private bool SharesGroup(Guid a, Guid b) => _state.Groups.Any(g => g.Members.Contains(a) && g.Members.Contains(b));

    private GroupView View(WatchGroup g, Guid user, IReadOnlyList<ShowProgress> everyone, IReadOnlyList<GroupWatch> record)
    {
        string Name(Guid m) => _directory.NameOf(m) ?? "Former account";
        var pending = g.Pending is { } p
            ? new ScheduleChangeView(p.Id, Name(p.From), p.Proposed, p.Yes.Select(Name).ToList(), p.No.Select(Name).ToList(), p.Yes.Contains(user) || p.No.Contains(user), p.At)
            : null;
        var lockNow = new ScheduleLock(null, null);
        var order = everyone.Select(x => x.Order).FirstOrDefault(o => o is { Count: > 0 });
        if (g.Schedule is { Active: true } && order is not null)
        {
            // The group's frontier: the furthest part anyone has seen, and when it was first watched.
            var index = order.Select((id, i) => (id, i)).ToDictionary(x => x.id, x => x.i);
            var reach = everyone.Select(x => (Furthest: x.Watched.Where(index.ContainsKey).Select(id => index[id]).DefaultIfEmpty(-1).Max(), x.FurthestAt)).ToList();
            var frontier = reach.Max(r => r.Furthest);
            var at = reach.Where(r => r.Furthest == frontier && frontier >= 0).Select(r => r.FurthestAt).Where(t => t != null).DefaultIfEmpty(null).Min();
            if (KeepsRecord(g) && reach.Any(r => r.Furthest == frontier && frontier >= 0 && r.FurthestAt is null))
            {
                // The record started there (watched before the schedule): the next part is open now.
                at = null;
            }
            lockNow = ScheduleRules.Of(g.Schedule, order, frontier, at, _time.GetUtcNow());
        }

        return new GroupView(
            g.Id, g.SeriesId, g.SeriesName,
            g.Members.Where(m => m != user).Select(m => new MemberView(m, Name(m))).ToList(),
            g.Since, g.Schedule, pending, lockNow.LockedFromId, lockNow.OpensAt, g.Schedule is { } sc ? lockNow.OpensDay(sc) : null, lockNow.TogetherFromId,
            KeepsRecord(g), KeepsRecord(g) ? record.Where(w => w.Member == user).Select(w => w.ItemId).ToList() : null);
    }

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
