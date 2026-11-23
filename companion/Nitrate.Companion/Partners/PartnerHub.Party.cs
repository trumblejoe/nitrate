using System.Text.RegularExpressions;

namespace Nitrate.Companion.Partners;

/// <summary>
/// Group Watch Sessions (stored and called "party" in code and JSON): a group's recurring time (<see cref="GroupSchedule.Party"/>) and what
/// happens to each occurrence. +15 minutes applies at once (once per person per night); +30, +60
/// and "Not tonight" are requests that pass on a majority of the group (the asker counts as yes,
/// as with schedule changes) and lapse at the current start time; a failed "Not tonight" is its
/// asker's "Can't make it". The first member to join starts the watch party. Only members see a
/// group's party, and announcements go only to members (<see cref="PartyAnnounced"/>).
/// </summary>
public partial class PartnerHub
{
    /// <summary>How long a night's state is kept after it was set for.</summary>
    private static readonly TimeSpan KeepNights = TimeSpan.FromDays(3);

    /// <summary>How early someone may start the party.</summary>
    private static readonly TimeSpan EarlyJoin = TimeSpan.FromHours(1);

    /// <summary>Raised (outside the hub's lock) when a group's members should hear about its party at once.</summary>
    public event Action<PartyAnnouncement>? PartyAnnounced;

    /// <summary>The group's members now (empty for no such group): who its announcements go to.</summary>
    public IReadOnlyList<Guid> MembersOf(Guid groupId)
    {
        lock (_lock)
        {
            return _state.Groups.FirstOrDefault(g => g.Id == groupId)?.Members.ToList() ?? new List<Guid>();
        }
    }

    /// <summary>The group's title and the other members' names, as [user] (a member) sees them; null when they're not in it.</summary>
    public (string Title, IReadOnlyList<string> Others)? Names(Guid user, Guid groupId)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user));
            return g is null ? null : (g.TitleNames, g.Members.Where(m => m != user).Select(m => _directory.NameOf(m) ?? "Former account").ToList());
        }
    }

    /// <summary>The caller's group's next (or current) watch party, or null when it has no party time.</summary>
    public PartyView? Party(Guid user, Guid groupId)
    {
        SeedRecords();
        WatchGroup g;
        PartyView? stub;
        List<GroupWatch> record;
        var said = new List<PartyAnnouncement>();
        lock (_lock)
        {
            g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            stub = PartyStub(g, user, _time.GetUtcNow(), said);
            record = g.Watched.ToList();
        }

        Tell(said);
        return stub is null ? null : WithPart(stub, g, record, Orders(g, user));
    }

    /// <summary>
    /// Pushes tonight's party back: 15 minutes at once (once per person per night), 30 or 60 as a
    /// request to the group. Returns the party as the caller sees it now.
    /// </summary>
    public PartyView? PushBack(Guid user, Guid groupId, int minutes)
    {
        Night(user, groupId, (g, n, now, said) =>
        {
            if (n.Skipped || n.StartedBy is not null || now >= PartyRules.Start(n.At, n))
            {
                throw new PartnerException(PartnerError.Invalid, "Tonight's Group Watch Session can't be moved now.");
            }

            if (minutes == 15)
            {
                if (n.Pushed.Contains(user))
                {
                    throw new PartnerException(PartnerError.Invalid, "You've pushed it back once tonight already.");
                }

                n.Minutes += 15;
                n.Pushed.Add(user);
                n.Pushes.Add(new PartyDelay { By = user, Minutes = 15 });
                said.Add(new PartyAnnouncement(g.Id, PartyRules.Pushed, user, 15));
                return;
            }

            if (!PartyRules.Asks.Contains(minutes))
            {
                throw new PartnerException(PartnerError.Invalid, "Push it back 15, 30 or 60 minutes.");
            }

            Ask(g, n, user, PartyRequest.Later, minutes, now, said);
        });
        return Party(user, groupId);
    }

    /// <summary>Asks the group to skip tonight's party ("Not tonight"); if it doesn't pass, the asker can't make it.</summary>
    public PartyView? SkipTonight(Guid user, Guid groupId)
    {
        Night(user, groupId, (g, n, now, said) =>
        {
            if (n.Skipped)
            {
                return;
            }

            if (n.StartedBy is not null)
            {
                throw new PartnerException(PartnerError.Invalid, "Tonight's Group Watch Session has started.");
            }

            Ask(g, n, user, PartyRequest.Skip, 0, now, said);
        });
        return Party(user, groupId);
    }

    /// <summary>The caller can't make it tonight (or can after all): the others are asked at start time whether to start without them.</summary>
    public PartyView? CantMake(Guid user, Guid groupId, bool cant)
    {
        Night(user, groupId, (g, n, _, said) =>
        {
            n.CantMake.Remove(user);
            if (cant)
            {
                n.CantMake.Add(user);
            }

            said.Add(new PartyAnnouncement(g.Id, PartyRules.Changed, user));
        });
        return Party(user, groupId);
    }

    /// <summary>A member's yes or no to tonight's waiting request.</summary>
    public PartyView? PartyVote(Guid user, Guid groupId, Guid requestId, bool yes)
    {
        Night(user, groupId, (g, n, now, said) =>
        {
            var r = n.Request is { } x && x.Id == requestId ? x : throw new PartnerException(PartnerError.NotFound, "That request has been settled or has lapsed.");
            r.Yes.Remove(user);
            r.No.Remove(user);
            (yes ? r.Yes : r.No).Add(user);
            if (!SettleNight(g, n, now, said))
            {
                said.Add(new PartyAnnouncement(g.Id, PartyRules.Changed, user));
            }
        });
        return Party(user, groupId);
    }

    /// <summary>
    /// Joining tonight's party: the first to join starts it (<see cref="PartyJoin.Start"/>: the app
    /// starts the live session, invites the others and plays the part); later joiners get the live
    /// session to join, once its starter has said which (<see cref="PartySession"/>).
    /// </summary>
    public PartyJoin JoinParty(Guid user, Guid groupId)
    {
        PartyJoin? join = null;
        Night(user, groupId, (g, n, now, said) =>
        {
            if (n.Skipped)
            {
                throw new PartnerException(PartnerError.Invalid, "Tonight's Group Watch Session is off.");
            }

            if (now < PartyRules.Start(n.At, n) - EarlyJoin)
            {
                throw new PartnerException(PartnerError.Invalid, "It's not time for the Group Watch Session yet.");
            }

            n.CantMake.Remove(user);
            if (n.StartedBy is null || n.StartedBy == user)
            {
                n.StartedBy = user;
                said.Add(new PartyAnnouncement(g.Id, PartyRules.Changed, user));
                join = new PartyJoin(true, n.SessionId, null);
            }
            else
            {
                join = new PartyJoin(false, n.SessionId, null);
            }
        });
        return join! with { PartId = Party(user, groupId)?.PartId };
    }

    /// <summary>The starter says which live session tonight's party is (later joiners join it; what it plays counts for the group).</summary>
    public void PartySession(Guid user, Guid groupId, string sessionId)
    {
        Night(user, groupId, (g, n, _, said) =>
        {
            if (n.StartedBy != user)
            {
                throw new PartnerException(PartnerError.Invalid, "Someone else started tonight's Group Watch Session.");
            }

            n.SessionId = sessionId;
            said.Add(new PartyAnnouncement(g.Id, PartyRules.Changed, user));
        });
    }

    /// <summary>
    /// What's due by the clock (called every half minute): the reminder 15 minutes before each
    /// party, requests lapsing at start time, and at start time the question whether to start
    /// without whoever can't make it. Each is announced once; returns them (also raised).
    /// </summary>
    public IReadOnlyList<PartyAnnouncement> DueParties()
    {
        var said = new List<PartyAnnouncement>();
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var changed = PruneNights(now);
            foreach (var g in _state.Groups.Where(g => g.Schedule?.Party is not null))
            {
                if (PartyRules.Tonight(g.Schedule, g.Id, _state.Parties, now) is not { } t)
                {
                    continue;
                }

                var n = t.Night;
                var start = PartyRules.Start(t.At, n);
                if (n?.Request is not null && now >= start)
                {
                    SettleNight(g, n, now, said);
                    changed = true;
                }

                if (n is { Skipped: true })
                {
                    continue;
                }

                if (now >= start - PartyRules.Lead && now < start + TimeSpan.FromMinutes(5) && n?.Reminded is null && n?.StartedBy is null)
                {
                    n ??= NewNight(g.Id, t.At);
                    n.Reminded = start;
                    said.Add(new PartyAnnouncement(g.Id, PartyRules.Reminder));
                    changed = true;
                }

                if (n is { CantMake.Count: > 0, AskedWithout: false, StartedBy: null } && now >= start && now < start + TimeSpan.FromMinutes(30)
                    && n.CantMake.Count(g.Members.Contains) < g.Members.Count)
                {
                    n.AskedWithout = true;
                    said.Add(new PartyAnnouncement(g.Id, PartyRules.Without));
                    changed = true;
                }
            }

            if (changed)
            {
                _storage.Save(_state);
            }
        }

        Tell(said);
        return said;
    }

    /// <summary>Someone left a group: what they asked or said about its parties goes with them.</summary>
    private void ForgetInParties(Guid groupId, Guid user)
    {
        foreach (var n in _state.Parties.Where(n => n.GroupId == groupId))
        {
            n.CantMake.Remove(user);
            if (n.Request is { } r && r.From == user)
            {
                n.Request = null;
            }

            n.Request?.Yes.Remove(user);
            n.Request?.No.Remove(user);
        }
    }

    /// <summary>Tonight's party of the caller's group, changed by [change] under the lock, saved, then announced.</summary>
    private void Night(Guid user, Guid groupId, Action<WatchGroup, PartyNight, DateTimeOffset, List<PartyAnnouncement>> change)
    {
        var said = new List<PartyAnnouncement>();
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            PruneNights(now);
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            var t = PartyRules.Tonight(g.Schedule, g.Id, _state.Parties, now)
                ?? throw new PartnerException(PartnerError.Invalid, "This group has no Group Watch Session time.");
            var n = t.Night ?? NewNight(g.Id, t.At);
            SettleNight(g, n, now, said);
            change(g, n, now, said);
            _storage.Save(_state);
        }

        Tell(said);
    }

    private PartyNight NewNight(Guid groupId, DateTimeOffset at)
    {
        var n = new PartyNight { GroupId = groupId, At = at };
        _state.Parties.Add(n);
        return n;
    }

    /// <summary>A new request (replacing the caller's own earlier one; someone else's must be settled first).</summary>
    private void Ask(WatchGroup g, PartyNight n, Guid user, string kind, int minutes, DateTimeOffset now, List<PartyAnnouncement> said)
    {
        if (n.Request is { } waiting && waiting.From != user)
        {
            throw new PartnerException(PartnerError.Invalid, $"{_directory.NameOf(waiting.From) ?? "Someone"}'s request is waiting: answer that one first.");
        }

        n.Request = new PartyRequest { Id = Guid.NewGuid(), From = user, Kind = kind, Minutes = minutes, Yes = new() { user } };
        if (!SettleNight(g, n, now, said))
        {
            said.Add(new PartyAnnouncement(g.Id, PartyRules.Asked, user, minutes));
        }
    }

    /// <summary>
    /// Applies tonight's request once a majority agrees; drops it once it can't pass or the start
    /// time comes (the current time stands; a failed "Not tonight" is its asker's can't-make-it).
    /// Returns whether it settled.
    /// </summary>
    private bool SettleNight(WatchGroup g, PartyNight n, DateTimeOffset now, List<PartyAnnouncement> said)
    {
        if (n.Request is not { } r)
        {
            return false;
        }

        var members = g.Members.Count;
        if (ScheduleRules.Passes(r.Yes.Count(g.Members.Contains), members))
        {
            n.Request = null;
            if (r.Kind == PartyRequest.Skip)
            {
                n.Skipped = true;
                said.Add(new PartyAnnouncement(g.Id, PartyRules.Skipped, r.From));
            }
            else
            {
                n.Minutes += r.Minutes;
                n.Pushes.Add(new PartyDelay { By = r.From, Minutes = r.Minutes });
                said.Add(new PartyAnnouncement(g.Id, PartyRules.Pushed, r.From, r.Minutes));
            }

            return true;
        }

        if (ScheduleRules.Fails(r.No.Count(g.Members.Contains), members) || now >= PartyRules.Start(n.At, n))
        {
            n.Request = null;
            if (r.Kind == PartyRequest.Skip && !n.CantMake.Contains(r.From))
            {
                n.CantMake.Add(r.From);
            }

            said.Add(new PartyAnnouncement(g.Id, PartyRules.Changed, r.From));
            return true;
        }

        return false;
    }

    private bool PruneNights(DateTimeOffset now)
    {
        var groups = _state.Groups.Select(g => g.Id).ToHashSet();
        return _state.Parties.RemoveAll(n => !groups.Contains(n.GroupId) || n.At < now - KeepNights) > 0;
    }

    /// <summary>The party as [user] sees it, without what plays (that reads the library: <see cref="WithPart"/>, outside the lock).</summary>
    private PartyView? PartyStub(WatchGroup g, Guid user, DateTimeOffset now, List<PartyAnnouncement> said)
    {
        var s = g.Schedule;
        if (PartyRules.Tonight(s, g.Id, _state.Parties, now) is not { } t)
        {
            return null;
        }

        var n = t.Night;
        if (n is not null && SettleNight(g, n, now, said))
        {
            _storage.Save(_state);
        }

        string Name(Guid m) => _directory.NameOf(m) ?? "Former account";
        var request = n?.Request is { } r
            ? new PartyRequestView(r.Id, Name(r.From), r.Kind, r.Minutes, r.Yes.Where(g.Members.Contains).Select(Name).ToList(), r.No.Where(g.Members.Contains).Select(Name).ToList(), r.Yes.Contains(user) || r.No.Contains(user), r.From == user)
            : null;
        var start = PartyRules.Start(t.At, n);
        var after = PartyRules.Next(s, t.At) ?? t.At.AddDays(7);
        return new PartyView(
            g.Id, t.At, start, null, null,
            n?.Pushes.Select(p => new PartyPushView(Name(p.By), p.Minutes, p.By == user)).ToList() ?? new List<PartyPushView>(),
            PartyRules.CanPushBack(n, user, t.At, now),
            request,
            n?.CantMake.Where(m => m != user && g.Members.Contains(m)).Select(Name).ToList() ?? new List<string>(),
            n?.CantMake.Contains(user) == true,
            n?.Skipped == true,
            n?.StartedBy is not null,
            n?.StartedBy is { } by ? Name(by) : null,
            n?.SessionId,
            after,
            (int)ScheduleRules.Zone(s!).GetUtcOffset(now).TotalMinutes);
    }

    /// <summary>
    /// What plays at the Group Watch Session, by the group's record: in the title the group watched
    /// most lately that still has a part to play (else the first such title), with Together on the
    /// part after the furthest anyone watched with the group; else the part after the furthest
    /// everyone has watched (so nobody misses one); labelled "S1:E4" or by name.
    /// </summary>
    private PartyView WithPart(PartyView stub, WatchGroup g, IReadOnlyList<GroupWatch> record, IReadOnlyDictionary<Guid, IReadOnlyList<Guid>> orders)
    {
        var together = g.Schedule?.Together == true;
        var parts = g.Titles.Select(t => orders.TryGetValue(t.Id, out var order) ? order : Array.Empty<Guid>())
            .Where(order => order.Count > 0)
            .Select(order => (
                Part: PartOf(together, g.Members.Select(m => FromRecord(order, record, m)).ToList()),
                Latest: record.Where(w => order.Contains(w.ItemId)).Select(w => w.At ?? DateTimeOffset.MinValue).DefaultIfEmpty(DateTimeOffset.MinValue).Max()))
            .Where(x => x.Part is not null).ToList();
        var part = parts.OrderByDescending(x => x.Latest).Select(x => x.Part).FirstOrDefault();
        return stub with { PartId = part, PartLabel = part is { } p ? Short(_watch.Label(p)) : null };
    }

    /// <summary>The session's part in one title (see <see cref="WithPart"/>; <paramref name="record"/>: Together on); null when everyone's seen everything.</summary>
    public static Guid? PartOf(bool record, IReadOnlyList<ShowProgress> everyone)
    {
        var order = everyone.Select(x => x.Order).FirstOrDefault(o => o is { Count: > 0 });
        if (order is null || everyone.Count == 0)
        {
            return null;
        }

        var index = order.Select((id, i) => (id, i)).GroupBy(x => x.id).ToDictionary(x => x.Key, x => x.First().i);
        var reach = everyone.Select(x => x.Watched.Where(index.ContainsKey).Select(id => index[id]).DefaultIfEmpty(-1).Max()).ToList();
        var from = record ? reach.Max() : reach.Min();
        return from + 1 < order.Count ? order[from + 1] : null;
    }

    /// <summary>"S1 E4" → "S1:E4"; a film's name as it is.</summary>
    private static string? Short(string? label) =>
        label is null ? null : Regex.Replace(label, @"^S(\d+) E(\d+)$", "S$1:E$2");

    private void Tell(List<PartyAnnouncement> said)
    {
        foreach (var a in said)
        {
            try
            {
                PartyAnnounced?.Invoke(a);
            }
            catch (Exception)
            {
                // Telling people is never in the way of the change.
            }
        }
    }
}
