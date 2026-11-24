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
/// One of a group's titles as a member sees it: what isn't out yet by the group's schedule
/// (<paramref name="LockedFromId"/> and everything after it; it opens at <paramref name="OpensAt"/>,
/// or once someone watches the part before it), and with Together on the first part nobody in the
/// group has watched with it (<paramref name="TogetherFromId"/>). Nothing is ever refused: the
/// apps say when it opens and ask first.
/// </summary>
public record TitleView(Guid Id, string Name, Guid? LockedFromId = null, DateTimeOffset? OpensAt = null, int? OpensDay = null, Guid? TogetherFromId = null)
{
    /// <summary>Gets the other members who can't open all of it on this server (library access or parental rating); empty when everyone can.</summary>
    public IReadOnlyList<MemberReach> Reach { get; init; } = Array.Empty<MemberReach>();
}

/// <summary>A member who can open only <paramref name="Parts"/> of a title's <paramref name="Of"/> parts on this server (0: none of it).</summary>
public record MemberReach(string Name, int Parts, int Of);

/// <summary>
/// A watch group, as one member sees it: the others in it, its titles (<paramref name="Titles"/>,
/// each with what its schedule holds back now), its schedule and a change waiting for votes. Every
/// group keeps its own watched record (<paramref name="Record"/> always true now), and
/// <paramref name="Mine"/> is what the caller watched as far as this group goes.
/// <paramref name="SeriesId"/>/<paramref name="SeriesName"/> and the lock fields are the first
/// title's, for apps from before groups had several titles.
/// </summary>
public record GroupView(
    Guid Id, Guid SeriesId, string SeriesName, IReadOnlyList<MemberView> Others, DateTimeOffset Since,
    GroupSchedule? Schedule = null, ScheduleChangeView? Pending = null, Guid? LockedFromId = null, DateTimeOffset? OpensAt = null, int? OpensDay = null,
    Guid? TogetherFromId = null, bool Record = false, IReadOnlyList<Guid>? Mine = null, PartyView? Party = null,
    IReadOnlyList<TitleView>? Titles = null);

/// <summary>
/// An invite someone sent the caller. <paramref name="WillSee"/> is everyone who learns the
/// caller's name and progress by accepting (the inviter and the group's other members);
/// <paramref name="Titles"/> what the group watches (<paramref name="SeriesId"/> is the first).
/// </summary>
public record IncomingView(Guid Id, Guid FromId, string FromName, Guid SeriesId, string SeriesName, Guid? GroupId, IReadOnlyList<string> WillSee, DateTimeOffset At, GroupSchedule? Schedule = null, IReadOnlyList<GroupTitle>? Titles = null);

/// <summary>An invite the caller sent: always shown as waiting until it's accepted or lapses.</summary>
public record OutgoingView(Guid Id, string To, Guid SeriesId, string SeriesName, Guid? GroupId, DateTimeOffset At, IReadOnlyList<GroupTitle>? Titles = null);

/// <summary>Where another member of one of the caller's groups is in one title, by the group's record.</summary>
public record PartnerProgressView(Guid GroupId, Guid UserId, string UserName, IReadOnlyList<Guid> Watched, string? Last, Guid? NextId);

/// <summary>
/// Watch groups: people who watch one or more titles together and see each other's progress in
/// them. A pair is a group of two; the same title can be in several groups. Any member can invite
/// someone; the invited person accepts. Private accounts (marked by an admin, or by themselves)
/// never appear in any list and can't be found by name; an invite to a typed name always reads
/// "sent" and stays waiting until it's accepted or lapses, whether or not that account exists,
/// was declined, or is private. Before accepting, a person sees exactly who will learn their name.
/// Every group keeps its own watched record per member (<see cref="WatchGroup.Watched"/>): marks
/// for a group never change anyone's Jellyfin watched state, nor another group's record.
/// </summary>
public partial class PartnerHub
{
    /// <summary>How long a request waits.</summary>
    public static readonly TimeSpan RequestLifetime = TimeSpan.FromDays(14);

    /// <summary>How long the app's word that a live session is for a group holds.</summary>
    private static readonly TimeSpan SessionLifetime = TimeSpan.FromHours(12);

    private const int MaxOutgoing = 20;
    private readonly IPartnerStorage _storage;
    private readonly IPartnerDirectory _directory;
    private readonly IWatchState _watch;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly PartnerState _state;

    /// <summary>Whether the groups' playlists may need sharing (or a share taking back): at start, and after a group's members or titles change.</summary>
    private bool _sharesDue = true;

    /// <summary>Live (SyncPlay) sessions started for groups ("Watch now together"), by session key: in memory only.</summary>
    private readonly Dictionary<string, (HashSet<Guid> Groups, DateTimeOffset At)> _sessions = new();

    /// <summary>Initializes a new instance of the <see cref="PartnerHub"/> class.</summary>
    public PartnerHub(IPartnerStorage storage, IPartnerDirectory directory, IWatchState watch, TimeProvider time)
    {
        _storage = storage;
        _directory = directory;
        _watch = watch;
        _time = time;
        _state = storage.Load();
        if (MoveInTitles(_state))
        {
            _storage.Save(_state);
        }
    }

    /// <summary>
    /// Groups and invites stored with one title (<c>SeriesId</c>/<c>SeriesName</c>) read as a
    /// one-title list. Returns whether anything changed.
    /// </summary>
    private static bool MoveInTitles(PartnerState state)
    {
        var changed = false;
        foreach (var g in state.Groups.Where(g => g.Titles.Count == 0 && g.SeriesId != Guid.Empty))
        {
            g.Titles.Add(new GroupTitle { Id = g.SeriesId, Name = g.SeriesName });
            changed = true;
        }

        foreach (var r in state.Requests.Where(r => r.Titles.Count == 0 && r.SeriesId != Guid.Empty))
        {
            r.Titles.Add(new GroupTitle { Id = r.SeriesId, Name = r.SeriesName });
            changed = true;
        }

        return changed;
    }

    /// <summary>Keeps <c>SeriesId</c>/<c>SeriesName</c> the first title (what older plugins and apps read).</summary>
    private static void FirstTitle(WatchGroup g)
    {
        g.SeriesId = g.Titles.FirstOrDefault()?.Id ?? Guid.Empty;
        g.SeriesName = g.Titles.FirstOrDefault()?.Name ?? string.Empty;
    }

    /// <summary>
    /// Groups stored before every group kept its own record: a together-only one keeps the record
    /// it has; any other is seeded once from each member's own (Jellyfin) watched parts of its
    /// titles (the furthest timed by when it was played, so the schedule carries on as it was).
    /// A title the library doesn't hold (yet) leaves the group for a later try.
    /// </summary>
    /// <summary>
    /// Lets every member see the group's playlists: Jellyfin keeps a playlist to its owner, so one
    /// in a group is shared (to watch, not change) with each member who couldn't see it, and those
    /// shares (only those the Companion added) are taken back once no group of theirs holds it.
    /// Jellyfin is asked outside the lock.
    /// </summary>
    private void SharePlaylists()
    {
        HashSet<string> want;
        List<string> had;
        lock (_lock)
        {
            if (!_sharesDue)
            {
                return;
            }

            _sharesDue = false;
            want = _state.Groups.SelectMany(g => g.Titles.SelectMany(t => g.Members.Select(m => Share(t.Id, m)))).ToHashSet();
            had = _state.PlaylistShares.ToList();
        }

        var added = new List<string>();
        var taken = new List<string>();
        foreach (var key in want.Except(had))
        {
            var (title, member) = Unpack(key);
            try
            {
                if (_watch.HiddenFrom(title, member))
                {
                    _watch.ShareWith(title, member);
                    added.Add(key);
                }
            }
            catch (Exception)
            {
                // Tried again after the next change.
            }
        }

        foreach (var key in had.Except(want))
        {
            var (title, member) = Unpack(key);
            try
            {
                _watch.Unshare(title, member);
            }
            catch (Exception)
            {
                // Gone already (the playlist deleted, or its owner took the share back).
            }

            taken.Add(key);
        }

        if (added.Count == 0 && taken.Count == 0)
        {
            return;
        }

        lock (_lock)
        {
            _state.PlaylistShares.RemoveAll(taken.Contains);
            _state.PlaylistShares.AddRange(added.Where(k => !_state.PlaylistShares.Contains(k)));
            _storage.Save(_state);
        }
    }

    private static string Share(Guid title, Guid member) => title.ToString("N") + ":" + member.ToString("N");

    private static (Guid Title, Guid Member) Unpack(string key) => (Guid.Parse(key[..32]), Guid.Parse(key[33..]));

    private void SeedRecords()
    {
        List<(Guid Id, List<Guid> Members, List<Guid> Titles)> todo;
        lock (_lock)
        {
            var changed = false;
            foreach (var g in _state.Groups.Where(g => !g.RecordKept && g.Schedule is { Together: true }))
            {
                g.RecordKept = true;
                changed = true;
            }

            if (changed)
            {
                _storage.Save(_state);
            }

            todo = _state.Groups.Where(g => !g.RecordKept).Select(g => (g.Id, g.Members.ToList(), g.Titles.Select(t => t.Id).ToList())).ToList();
        }

        if (todo.Count == 0)
        {
            return;
        }

        // Outside the lock: it reads the library.
        var seeded = new Dictionary<Guid, List<GroupWatch>>();
        foreach (var (id, members, titles) in todo)
        {
            var entries = new List<GroupWatch>();
            var complete = true;
            foreach (var title in titles)
            {
                foreach (var m in members)
                {
                    var p = _watch.Progress(m, title);
                    if (p.Order is not { Count: > 0 } order)
                    {
                        complete = false;
                        break;
                    }

                    var furthest = p.Watched.OrderBy(i => IndexIn(order, i)).LastOrDefault();
                    entries.AddRange(p.Watched.Select(i => new GroupWatch { Member = m, ItemId = i, At = i == furthest ? p.FurthestAt : null }));
                }
            }

            if (complete)
            {
                seeded[id] = entries;
            }
        }

        lock (_lock)
        {
            foreach (var g in _state.Groups.Where(g => !g.RecordKept && seeded.ContainsKey(g.Id)))
            {
                // Anything counted meanwhile stays; the seed fills in the rest.
                g.Watched = g.Watched.Concat(seeded[g.Id]).DistinctBy(w => (w.Member, w.ItemId)).ToList();
                g.RecordKept = true;
            }

            if (seeded.Count > 0)
            {
                _storage.Save(_state);
            }
        }
    }

    private static int IndexIn(IReadOnlyList<Guid> order, Guid id)
    {
        for (var i = 0; i < order.Count; i++)
        {
            if (order[i] == id)
            {
                return i;
            }
        }

        return -1;
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
    /// Invites someone to watch together: into one of the caller's groups (<paramref name="groupId"/>,
    /// which carries the group's titles), or as a new group with the caller for <paramref name="titles"/>
    /// (else the one title <paramref name="seriesId"/>). By id (someone the caller can see, or
    /// already shares a group with) or by exact name (anyone, private too). Always "sent": nothing
    /// tells the caller whether the account exists.
    /// </summary>
    public OutgoingView Request(Guid from, Guid? toId, string? toName, Guid seriesId, string seriesName, Guid? groupId = null, GroupSchedule? schedule = null, IReadOnlyList<GroupTitle>? titles = null, Guid? batch = null)
    {
        lock (_lock)
        {
            Prune();
            WatchGroup? group = null;
            var wanted = (titles ?? Array.Empty<GroupTitle>()).Where(t => t.Id != Guid.Empty)
                .Select(t => new GroupTitle { Id = t.Id, Name = (t.Name ?? string.Empty).Trim() }).DistinctBy(t => t.Id).ToList();
            if (wanted.Count == 0 && seriesId != Guid.Empty)
            {
                wanted.Add(new GroupTitle { Id = seriesId, Name = (seriesName ?? string.Empty).Trim() });
            }

            if (groupId is { } gid && gid != Guid.Empty)
            {
                group = _state.Groups.FirstOrDefault(g => g.Id == gid && g.Members.Contains(from))
                    ?? throw new PartnerException(PartnerError.NotFound, "You're not in that group any more.");
                wanted = group.Titles.Select(t => new GroupTitle { Id = t.Id, Name = t.Name }).ToList();
            }

            if (wanted.Count == 0)
            {
                throw new PartnerException(PartnerError.Invalid, "Choose something to watch together.");
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
            var first = wanted[0].Id;
            // Invites sent together for one new group carry the same batch (never for an existing group).
            var batchOrEmpty = group is null ? batch ?? Guid.Empty : Guid.Empty;
            var existing = _state.Requests.FirstOrDefault(r => r.From == from && r.SeriesId == first && r.GroupId == gidOrEmpty && r.Batch == batchOrEmpty
                && (to != Guid.Empty ? r.To == to : string.Equals(r.ToLabel, label, StringComparison.OrdinalIgnoreCase)));
            if (existing is null)
            {
                existing = new PartnerRequest
                {
                    Id = Guid.NewGuid(), From = from, To = to, ToLabel = label, GroupId = gidOrEmpty, Batch = batchOrEmpty,
                    SeriesId = first, SeriesName = wanted[0].Name, Titles = wanted, At = _time.GetUtcNow(),
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
                    var group = r.GroupId == Guid.Empty ? BatchGroup(r) : _state.Groups.FirstOrDefault(g => g.Id == r.GroupId);
                    var willSee = (group?.Members ?? new List<Guid> { r.From }).Append(r.From).Distinct()
                        .Where(id => id != user).Select(id => _directory.NameOf(id)).OfType<string>().ToList();
                    var titles = (group?.Titles ?? r.Titles).Select(t => new GroupTitle { Id = t.Id, Name = t.Name }).ToList();
                    return new IncomingView(
                        r.Id, r.From, _directory.NameOf(r.From) ?? "Someone", titles.FirstOrDefault()?.Id ?? r.SeriesId, titles.FirstOrDefault()?.Name ?? r.SeriesName,
                        group?.Id, willSee, r.At, group?.Schedule ?? r.Schedule, titles);
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

    /// <summary>Says yes: joins the group (or makes a new one with the inviter, its record empty).</summary>
    public GroupView Accept(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            Prune();
            var r = _state.Requests.FirstOrDefault(x => x.Id == requestId && x.To == user && !x.Declined)
                ?? throw new PartnerException(PartnerError.NotFound, "That invite has lapsed.");
            _state.Requests.Remove(r);
            WatchGroup group;
            if (r.GroupId == Guid.Empty && BatchGroup(r) is { } made)
            {
                // Invited together with others: someone already accepted, so join the group they made.
                group = made;
                if (!group.Members.Contains(user))
                {
                    group.Members.Add(user);
                }
            }
            else if (r.GroupId == Guid.Empty)
            {
                var schedule = r.Schedule?.Copy();
                if (schedule is not null)
                {
                    schedule.Since = _time.GetUtcNow();
                }

                group = new WatchGroup
                {
                    Id = Guid.NewGuid(), Titles = r.Titles.Select(t => new GroupTitle { Id = t.Id, Name = t.Name }).ToList(),
                    Members = new() { r.From, user }, Since = _time.GetUtcNow(), Schedule = schedule, RecordKept = true, FromBatch = r.Batch,
                };
                FirstTitle(group);
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
            _sharesDue = true;
            return View(group, user, group.Watched.ToList(), null);
        }
    }

    /// <summary>The group an invite's batch already made (someone invited with it accepted), while the inviter is still in it.</summary>
    private WatchGroup? BatchGroup(PartnerRequest r) =>
        r.Batch == Guid.Empty ? null : _state.Groups.FirstOrDefault(g => g.FromBatch == r.Batch && g.Members.Contains(r.From));

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

    /// <summary>The caller's groups, each with what its schedule holds back now in each title.</summary>
    public IReadOnlyList<GroupView> Groups(Guid user)
    {
        SharePlaylists();
        SeedRecords();
        List<(WatchGroup Group, List<GroupWatch> Record, PartyView? Party)> mine;
        var said = new List<PartyAnnouncement>();
        lock (_lock)
        {
            PruneChanges();
            var now = _time.GetUtcNow();
            mine = _state.Groups.Where(g => g.Members.Contains(user)).Select(g => (g, g.Watched.ToList(), PartyStub(g, user, now, said))).ToList();
        }

        Tell(said);
        TellSchedules();

        // Where everyone is, for groups that pace their titles, have Together on or meet for a Group
        // Watch Session (outside the lock: it reads the library, once per title).
        return mine.Select(x =>
            {
                var g = x.Group;
                if (g.Schedule is not { Active: true } && x.Party is null)
                {
                    return View(g, user, x.Record, null);
                }

                var orders = Orders(g, user);
                return View(g, user, x.Record, orders) with { Party = x.Party is { } p ? WithPart(p, g, x.Record, orders) : null };
            })
            .OrderBy(g => g.SeriesName, StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>Each of the group's titles in watching order (as <paramref name="user"/> sees the library).</summary>
    private Dictionary<Guid, IReadOnlyList<Guid>> Orders(WatchGroup g, Guid user) =>
        g.Titles.Select(t => t.Id).Distinct().ToDictionary(t => t, t => _watch.Order(t, user));

    /// <summary>
    /// Adds a title to the caller's group (any member may; it's a group change everyone sees).
    /// Already there: nothing changes.
    /// </summary>
    public void AddTitle(Guid user, Guid groupId, Guid titleId, string name)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            if (titleId == Guid.Empty)
            {
                throw new PartnerException(PartnerError.Invalid, "Choose something to add.");
            }

            if (g.HasTitle(titleId))
            {
                return;
            }

            g.Titles.Add(new GroupTitle { Id = titleId, Name = (name ?? string.Empty).Trim() });
            FirstTitle(g);
            _storage.Save(_state);
            _sharesDue = true;
        }
    }

    /// <summary>
    /// Removes a title from the caller's group (any member may, like adding one). A group keeps at
    /// least one title; what was watched of it stays in the record, so adding it back carries on.
    /// </summary>
    public void RemoveTitle(Guid user, Guid groupId, Guid titleId)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            if (!g.HasTitle(titleId))
            {
                throw new PartnerException(PartnerError.NotFound, "That isn't one of the group's titles.");
            }

            if (g.Titles.Count == 1)
            {
                throw new PartnerException(PartnerError.Invalid, "A group keeps at least one title: leave the group instead.");
            }

            g.Titles.RemoveAll(t => t.Id == titleId);
            FirstTitle(g);
            _storage.Save(_state);
            _sharesDue = true;
        }
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

            var change = new ScheduleChange { Id = Guid.NewGuid(), From = user, Proposed = Valid(schedule), Yes = new() { user }, At = _time.GetUtcNow() };
            g.Pending = change;
            if (Settle(g, null) is null)
            {
                SaySchedule(new ScheduleAnnouncement(g.Id, ScheduleNews.Ask, change.Id, user, change.Proposed.Copy()));
            }

            _storage.Save(_state);
        }

        TellSchedules();
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
            Settle(g, user);
            _storage.Save(_state);
        }

        TellSchedules();
    }

    /// <summary>
    /// Applies the waiting change once it carries; drops it once it can't. The group's record
    /// carries on either way. Returns how it went (null: still waiting), announced to the group
    /// ([by]: whose vote settled it).
    /// </summary>
    private string? Settle(WatchGroup g, Guid? by)
    {
        if (g.Pending is not { } p)
        {
            return null;
        }

        var members = g.Members.Count;
        string? outcome = null;
        if (ScheduleRules.Passes(p.Yes.Count(g.Members.Contains), members))
        {
            var s = p.Proposed.Copy();
            s.Since = _time.GetUtcNow();
            g.Schedule = s.Cadence == GroupSchedule.Binge && !s.Together && s.Party is null ? null : s;
            g.Pending = null;
            outcome = ScheduleNews.Passed;
        }
        else if (ScheduleRules.Fails(p.No.Count(g.Members.Contains), members))
        {
            g.Pending = null;
            outcome = ScheduleNews.Failed;
        }

        if (outcome is not null)
        {
            SaySchedule(new ScheduleAnnouncement(g.Id, ScheduleNews.Done, p.Id, p.From, p.Proposed.Copy(), outcome, by));
        }

        return outcome;
    }

    private void PruneChanges()
    {
        var cutoff = _time.GetUtcNow() - ScheduleRules.ChangeLifetime;
        var changed = false;
        foreach (var g in _state.Groups.Where(g => g.Pending is { } p && p.At < cutoff))
        {
            var lapsed = g.Pending!;
            SaySchedule(new ScheduleAnnouncement(g.Id, ScheduleNews.Done, lapsed.Id, lapsed.From, lapsed.Proposed.Copy(), ScheduleNews.Lapsed));
            g.Pending = null;
            changed = true;
        }

        if (changed)
        {
            _storage.Save(_state);
        }
    }

    /// <summary>A schedule as sent, made sensible: a known cadence, a real time, a known time zone; the old lock choice dropped.</summary>
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
        v.SoftLock = false;
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
            _sharesDue = true;
        }
    }

    /// <summary>Where the other members of each of the caller's groups holding a title are in it, by each group's record.</summary>
    public IReadOnlyList<PartnerProgressView> Progress(Guid user, Guid seriesId)
    {
        SeedRecords();
        List<(Guid Group, Guid Member, List<GroupWatch> Record)> members;
        lock (_lock)
        {
            members = _state.Groups.Where(g => g.HasTitle(seriesId) && g.Members.Contains(user))
                .SelectMany(g =>
                {
                    var record = g.Watched.ToList();
                    return g.Members.Where(m => m != user).Select(m => (g.Id, m, record));
                }).ToList();
        }

        var known = members.Where(x => _directory.NameOf(x.Member) is not null).ToList();
        if (known.Count == 0)
        {
            return Array.Empty<PartnerProgressView>();
        }

        // Outside the lock: the title's order, read once.
        var order = _watch.Order(seriesId, user);
        return known.Select(x =>
        {
            var p = FromRecord(order, x.Record, x.Member);
            return new PartnerProgressView(x.Group, x.Member, _directory.NameOf(x.Member)!, p.Watched, p.Last, p.NextId);
        }).ToList();
    }

    /// <summary>
    /// Marks parts watched (or, with <paramref name="watched"/> false, not watched) for the caller
    /// in each of <paramref name="groupIds"/> (the caller must be in each, and every part must be
    /// in one of each group's titles: else nothing changes). Only those groups' records change:
    /// never the caller's own watched state, never anyone else's. Returns the groups marked.
    /// </summary>
    public IReadOnlyList<Guid> Mark(Guid user, IReadOnlyList<Guid> groupIds, IReadOnlyList<Guid> itemIds, bool watched = true)
    {
        SeedRecords();
        if (itemIds.Count == 0)
        {
            throw new PartnerException(PartnerError.Invalid, "Choose what to mark.");
        }

        if (groupIds.Count == 0)
        {
            throw new PartnerException(PartnerError.Invalid, "Choose a group to mark it for.");
        }

        List<(Guid Id, List<Guid> Titles)> groups;
        lock (_lock)
        {
            groups = groupIds.Distinct().Select(id => _state.Groups.FirstOrDefault(g => g.Id == id && g.Members.Contains(user))
                    ?? throw new PartnerException(PartnerError.NotFound, "No such group."))
                .Select(g => (g.Id, g.Titles.Select(t => t.Id).ToList())).ToList();
        }

        // Outside the lock: it reads the library.
        foreach (var g in groups)
        {
            if (itemIds.Any(i => !Holds(g.Titles, i, user)))
            {
                throw new PartnerException(PartnerError.Invalid, "Only what's in the group's titles can be marked for it.");
            }
        }

        lock (_lock)
        {
            var changed = false;
            foreach (var g in _state.Groups.Where(g => groups.Any(x => x.Id == g.Id)))
            {
                changed |= watched
                    ? CountIn(g, new[] { user }, itemIds)
                    : g.Watched.RemoveAll(w => w.Member == user && itemIds.Contains(w.ItemId)) > 0;
            }

            if (changed)
            {
                _storage.Save(_state);
            }
        }

        return groups.Select(g => g.Id).ToList();
    }

    /// <summary>Whether one of <paramref name="titles"/> holds <paramref name="itemId"/> (or is it).</summary>
    private bool Holds(IReadOnlyList<Guid> titles, Guid itemId, Guid user) =>
        titles.Any(t => t == itemId || _watch.Belongs(t, itemId, user));

    /// <summary>
    /// "&lt;names&gt; watched S1:E3 with me": counts the item in the group's record for the caller
    /// and everyone else in it. Only for part of one of the group's titles, and only by a member.
    /// Nobody's own watched state changes. Returns the others it counted for.
    /// </summary>
    public IReadOnlyList<Guid> MarkTogether(Guid user, Guid itemId, Guid groupId)
    {
        SeedRecords();
        List<Guid> others;
        List<Guid> titles;
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user));
            if (g is null)
            {
                // Not a member of that group: nobody to mark it for.
                return Array.Empty<Guid>();
            }

            titles = g.Titles.Select(t => t.Id).ToList();
            others = g.Members.Where(m => m != user && _directory.NameOf(m) is not null).ToList();
        }

        if (!Holds(titles, itemId, user))
        {
            throw new PartnerException(PartnerError.Invalid, "Only what's in a group's titles can be marked together.");
        }

        Count(groupId, others.Append(user).ToList(), new[] { itemId });
        return others;
    }

    /// <summary>
    /// The app's word that the live (SyncPlay) session <paramref name="sessionId"/> was started for
    /// the caller's group ("Watch now together" from its card or its question): what's played to
    /// the end in it counts for that group (<see cref="RecordLive"/>). Kept in memory for 12 hours.
    /// </summary>
    public void SessionFor(Guid user, Guid groupId, string sessionId)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
        {
            throw new PartnerException(PartnerError.Invalid, "Which session?");
        }

        lock (_lock)
        {
            if (!_state.Groups.Any(g => g.Id == groupId && g.Members.Contains(user)))
            {
                throw new PartnerException(PartnerError.NotFound, "No such group.");
            }

            var now = _time.GetUtcNow();
            foreach (var old in _sessions.Where(x => now - x.Value.At > SessionLifetime).Select(x => x.Key).ToList())
            {
                _sessions.Remove(old);
            }

            var key = SessionKey(sessionId);
            var groups = _sessions.TryGetValue(key, out var x) ? x.Groups : new HashSet<Guid>();
            groups.Add(groupId);
            _sessions[key] = (groups, now);
        }
    }

    /// <summary>The live sessions started for groups, for the next logic.</summary>
    public IReadOnlyList<GroupSessionState> ExportSessions()
    {
        lock (_lock)
        {
            return _sessions.Select(s => new GroupSessionState(s.Key, s.Value.Groups.ToList(), s.Value.At)).ToList();
        }
    }

    /// <summary>Takes the live sessions the previous logic knew were started for groups.</summary>
    public void ImportSessions(IEnumerable<GroupSessionState> sessions)
    {
        lock (_lock)
        {
            foreach (var s in sessions)
            {
                _sessions[s.Key] = (s.Groups.ToHashSet(), s.At);
            }
        }
    }

    /// <summary>A live session's id as the apps and the server's SyncPlay groups both write it.</summary>
    public static string SessionKey(string id) => Guid.TryParse(id, out var g) ? g.ToString("N") : id.Trim().ToLowerInvariant();

    /// <summary>
    /// Played to the end in a live (SyncPlay) session (<paramref name="sessionId"/>, its SyncPlay
    /// group) whose participants are <paramref name="present"/>. It counts, for each member there,
    /// in every group the session was started for (<see cref="SessionFor"/>, or a Group Watch
    /// Session's own) whose titles hold the item. When the session can't tell which group it's
    /// for, it counts in each group that everyone there (two or more) is in and for which the item
    /// is the next part for each of them by the group's record. Whoever wasn't there simply falls
    /// behind. Returns the groups it counted for.
    /// </summary>
    public IReadOnlyList<Guid> RecordLive(Guid itemId, IReadOnlyCollection<Guid> present, string? sessionId = null)
    {
        SeedRecords();
        List<(Guid Id, List<Guid> Titles, List<Guid> Here, List<GroupWatch>? Record)> candidates;
        lock (_lock)
        {
            var key = sessionId is null ? null : SessionKey(sessionId);
            var known = new HashSet<Guid>();
            if (key is not null)
            {
                if (_sessions.TryGetValue(key, out var s))
                {
                    known.UnionWith(s.Groups);
                }

                known.UnionWith(_state.Parties.Where(n => n.SessionId is { } id && SessionKey(id) == key).Select(n => n.GroupId));
            }

            candidates = known.Count > 0
                ? _state.Groups.Where(g => known.Contains(g.Id))
                    .Select(g => (g.Id, g.Titles.Select(t => t.Id).ToList(), g.Members.Where(present.Contains).ToList(), (List<GroupWatch>?)null))
                    .Where(x => x.Item3.Count > 0).ToList()
                : present.Count >= 2
                    ? _state.Groups.Where(g => present.All(g.Members.Contains))
                        .Select(g => (g.Id, g.Titles.Select(t => t.Id).ToList(), present.ToList(), (List<GroupWatch>?)g.Watched.ToList())).ToList()
                    : new();
        }

        // Outside the lock: it reads the library.
        var counted = new List<Guid>();
        foreach (var c in candidates)
        {
            var title = c.Titles.Cast<Guid?>().FirstOrDefault(t => t == itemId || _watch.Belongs(t!.Value, itemId, c.Here[0]));
            if (title is not { } t)
            {
                continue;
            }

            if (c.Record is { } record)
            {
                // Nobody said which group: only where it's the next part for everyone there.
                var order = _watch.Order(t, c.Here[0]);
                if (c.Here.Any(m => order.FirstOrDefault(i => !record.Any(w => w.Member == m && w.ItemId == i)) != itemId))
                {
                    continue;
                }
            }

            Count(c.Id, c.Here, new[] { itemId });
            counted.Add(c.Id);
        }

        return counted;
    }

    /// <summary>
    /// "We're in the same room": the caller watched <paramref name="itemIds"/> (parts of the
    /// group's titles) with <paramref name="present"/> (members of the group; the caller always
    /// counts as there). It counts in the group's record for everyone there; nobody's own watched
    /// state changes. Returns who it counted for, the caller included.
    /// </summary>
    public IReadOnlyList<Guid> WatchedWith(Guid user, Guid groupId, IReadOnlyList<Guid> itemIds, IReadOnlyCollection<Guid> present)
    {
        SeedRecords();
        List<Guid> titles;
        List<Guid> here;
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

            titles = g.Titles.Select(t => t.Id).ToList();
        }

        // Outside the lock: it reads the library.
        if (itemIds.Any(i => !Holds(titles, i, user)))
        {
            throw new PartnerException(PartnerError.Invalid, "Only what's in a group's titles can be marked together.");
        }

        Count(groupId, here, itemIds);
        return here;
    }

    /// <summary>Adds <paramref name="items"/> to a group's record for those of <paramref name="members"/> still in it.</summary>
    private void Count(Guid groupId, IReadOnlyList<Guid> members, IReadOnlyList<Guid> items)
    {
        lock (_lock)
        {
            if (_state.Groups.FirstOrDefault(x => x.Id == groupId) is { } g && CountIn(g, members, items))
            {
                _storage.Save(_state);
            }
        }
    }

    /// <summary>Under the lock: adds what isn't there yet; returns whether anything was.</summary>
    private bool CountIn(WatchGroup g, IReadOnlyList<Guid> members, IReadOnlyList<Guid> items)
    {
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

        return changed;
    }

    /// <summary>A member's place in one of a group's titles (in <paramref name="order"/>) by the group's record.</summary>
    private ShowProgress FromRecord(IReadOnlyList<Guid> order, IReadOnlyList<GroupWatch> record, Guid member)
    {
        var entries = record.Where(w => w.Member == member).ToList();
        var ids = entries.Select(w => w.ItemId).ToHashSet();
        var watched = order.Count > 0 ? order.Where(ids.Contains).Distinct().ToList() : ids.ToList();
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
    /// The caller's groups one of whose titles holds <paramref name="itemId"/> (a title itself, an
    /// episode of a show, an item of a list), each with everyone in it now: who a note pinned there
    /// goes to. Empty for anyone not in such a group.
    /// </summary>
    public IReadOnlyList<Nitrate.Companion.Insights.GroupMembers> GroupsHolding(Guid user, Guid itemId)
    {
        List<(Guid Id, List<Guid> Titles, string Name, List<Guid> Members)> mine;
        lock (_lock)
        {
            mine = _state.Groups.Where(g => g.Members.Contains(user)).Select(g => (g.Id, g.Titles.Select(t => t.Id).ToList(), g.TitleNames, g.Members.ToList())).ToList();
        }

        // Outside the lock: it reads the library (once per title).
        var holds = new Dictionary<Guid, bool>();
        bool Holds(Guid title) => title == itemId || (holds.TryGetValue(title, out var h) ? h : holds[title] = _watch.Belongs(title, itemId, user));
        return mine.Where(g => g.Titles.Any(Holds))
            .Select(g => new Nitrate.Companion.Insights.GroupMembers(g.Id, g.Name, g.Members.Where(m => _directory.NameOf(m) is not null).ToList()))
            .ToList();
    }

    private bool SharesGroup(Guid a, Guid b) => _state.Groups.Any(g => g.Members.Contains(a) && g.Members.Contains(b));

    /// <summary>
    /// The group as [user] sees it; [orders] (each title's parts in order, null: not read) gives
    /// each title what its schedule holds back now, by the group's record.
    /// </summary>
    private GroupView View(WatchGroup g, Guid user, IReadOnlyList<GroupWatch> record, IReadOnlyDictionary<Guid, IReadOnlyList<Guid>>? orders)
    {
        string Name(Guid m) => _directory.NameOf(m) ?? "Former account";
        var pending = g.Pending is { } p
            ? new ScheduleChangeView(p.Id, Name(p.From), p.Proposed, p.Yes.Select(Name).ToList(), p.No.Select(Name).ToList(), p.Yes.Contains(user) || p.No.Contains(user), p.At)
            : null;
        var titles = g.Titles.Select(t => WithReach(g, user, t.Id, TitleOf(t))).ToList();
        TitleView TitleOf(GroupTitle t)
        {
            if (g.Schedule is not { Active: true } || orders is null || !orders.TryGetValue(t.Id, out var order) || order.Count == 0)
            {
                return new TitleView(t.Id, t.Name);
            }

            // The group's frontier in this title: the furthest part anyone has watched with it, and when it was first counted.
            var everyone = g.Members.Select(m => FromRecord(order, record, m)).ToList();
            var reach = everyone.Select(x => (Furthest: x.Watched.Select(id => IndexIn(order, id)).DefaultIfEmpty(-1).Max(), x.FurthestAt)).ToList();
            var frontier = reach.Max(r => r.Furthest);
            var at = reach.Where(r => r.Furthest == frontier && frontier >= 0).Select(r => r.FurthestAt).Where(x => x != null).DefaultIfEmpty(null).Min();
            if (reach.Any(r => r.Furthest == frontier && frontier >= 0 && r.FurthestAt is null))
            {
                // Counted from before the schedule (or the record's seed): the next part is out now.
                at = null;
            }

            var l = ScheduleRules.Of(g.Schedule, order, frontier, at, _time.GetUtcNow());
            return new TitleView(t.Id, t.Name, l.LockedFromId, l.OpensAt, l.OpensDay(g.Schedule), l.TogetherFromId);
        }

        var first = titles.FirstOrDefault();
        return new GroupView(
            g.Id, first?.Id ?? g.SeriesId, first?.Name ?? g.SeriesName,
            g.Members.Where(m => m != user).Select(m => new MemberView(m, Name(m))).ToList(),
            g.Since, g.Schedule, pending, first?.LockedFromId, first?.OpensAt, first?.OpensDay, first?.TogetherFromId,
            true, record.Where(w => w.Member == user).Select(w => w.ItemId).Distinct().ToList(), null, titles);
    }

    /// <summary>
    /// The title with who else in the group can't open all of it (a library their account lacks,
    /// or parental rating): told only to members, so nothing reaches anyone outside the group.
    /// Read from the library at most every few minutes per title and member.
    /// </summary>
    private TitleView WithReach(WatchGroup g, Guid user, Guid titleId, TitleView view)
    {
        var others = g.Members.Where(m => m != user && _directory.NameOf(m) is not null).ToList();
        if (others.Count == 0)
        {
            return view;
        }

        var parts = Cached((titleId, user, false), () => _watch.Order(titleId, user).Count);
        var short_ = others
            .Select(m => (Member: m, Parts: Cached((titleId, m, true), () => _watch.Reach(titleId, m, parts) ?? -1)))
            .Where(x => x.Parts >= 0)
            .Select(x => new MemberReach(_directory.NameOf(x.Member)!, x.Parts, parts))
            .ToList();
        return short_.Count == 0 ? view : view with { Reach = short_ };
    }

    private readonly Dictionary<(Guid Title, Guid User, bool Reach), (int Value, DateTimeOffset At)> _reach = new();

    private int Cached((Guid Title, Guid User, bool Reach) key, Func<int> read)
    {
        var now = _time.GetUtcNow();
        lock (_reach)
        {
            if (_reach.TryGetValue(key, out var hit) && now - hit.At < TimeSpan.FromMinutes(5))
            {
                return hit.Value;
            }
        }

        int value;
        try
        {
            value = read();
        }
        catch (Exception)
        {
            value = -1;
        }

        lock (_reach)
        {
            _reach[key] = (value, now);
        }

        return value;
    }

    private static OutgoingView Outgoing(PartnerRequest r) =>
        new(r.Id, r.ToLabel, r.SeriesId, r.SeriesName, r.GroupId == Guid.Empty ? null : r.GroupId, r.At, r.Titles.Select(t => new GroupTitle { Id = t.Id, Name = t.Name }).ToList());

    private void Prune()
    {
        var cutoff = _time.GetUtcNow() - RequestLifetime;
        if (_state.Requests.RemoveAll(r => r.At < cutoff) > 0)
        {
            _storage.Save(_state);
        }
    }
}
