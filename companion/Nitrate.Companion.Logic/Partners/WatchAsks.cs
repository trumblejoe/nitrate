namespace Nitrate.Companion.Partners;

/// <summary>
/// An ask to watch together, as one member sees it.
/// </summary>
/// <param name="Id">The ask.</param>
/// <param name="GroupId">The watch group asked.</param>
/// <param name="ItemId">The episode or film.</param>
/// <param name="PartLabel">"S1:E4", or a film's name.</param>
/// <param name="Title">The group's titles' names.</param>
/// <param name="FromName">Who asked.</param>
/// <param name="FromMe">Whether the caller asked.</param>
/// <param name="At">When they'd like to start (now, or one of the postpone steps later).</param>
/// <param name="State">open, taken, declined, cancelled or expired.</param>
/// <param name="TakenBy">Who said "Start it" (their device hosts the session).</param>
/// <param name="TakenByMe">Whether the caller did.</param>
/// <param name="Declined">Who said Not now.</param>
/// <param name="ServerTime">The server's clock.</param>
public record AskView(
    Guid Id, Guid GroupId, Guid ItemId, string? PartLabel, string Title, string FromName, bool FromMe, DateTimeOffset At,
    string State, string? TakenBy, bool TakenByMe, IReadOnlyList<string> Declined, DateTimeOffset ServerTime);

/// <summary>Something about an ask its people should hear at once (<see cref="PartyPush"/> sends it).</summary>
/// <param name="AskId">The ask.</param>
/// <param name="GroupId">Its group.</param>
/// <param name="Event">asked, taken, declined, cancelled, expired or due.</param>
/// <param name="By">Who did it.</param>
/// <param name="To">Who hears it: everyone in the group but <paramref name="By"/>, or just these.</param>
public record AskAnnouncement(Guid AskId, Guid GroupId, string Event, Guid? By, IReadOnlyList<Guid>? To);

/// <summary>An ask as one logic hands it to the next (<c>watchasks.v1</c>).</summary>
/// <param name="Id">The ask.</param>
/// <param name="GroupId">Its group.</param>
/// <param name="ItemId">What it plays.</param>
/// <param name="From">Who asked.</param>
/// <param name="Created">When.</param>
/// <param name="At">When they'd like to start.</param>
/// <param name="State">Its state.</param>
/// <param name="TakenBy">Who took it.</param>
/// <param name="Declined">Who said Not now.</param>
/// <param name="DueTold">Whether the taker was told it's time.</param>
public record WatchAskState(
    Guid Id, Guid GroupId, Guid ItemId, Guid From, DateTimeOffset Created, DateTimeOffset At, string State,
    Guid? TakenBy, IReadOnlyList<Guid> Declined, bool DueTold);

/// <summary>
/// "Ask to watch together": a member who'd rather someone else ran it asks their group to start a
/// Group Watch Session of one part, now or a postpone step later (15, 30 or 60 minutes). Everyone
/// else in the group is asked; the first to say "Start it" hosts it (their app starts the session,
/// at once or when it's time, and the asker joins as usual), and the others' questions go away. Not
/// now from someone is told to the asker; once everyone has said it, the ask is over. An ask nobody
/// took is over three hours after its time. The asker can call it off. One open ask per member per
/// group: a new one replaces it.
/// </summary>
public class WatchAsks
{
    /// <summary>Still open.</summary>
    public const string Open = "open";

    /// <summary>Someone said "Start it".</summary>
    public const string Taken = "taken";

    /// <summary>Everyone else said Not now.</summary>
    public const string Declined = "declined";

    /// <summary>The asker called it off.</summary>
    public const string Cancelled = "cancelled";

    /// <summary>Nobody took it in time.</summary>
    public const string Expired = "expired";

    /// <summary>Asked (to everyone in the group but the asker).</summary>
    public const string EventAsked = "asked";

    /// <summary>Taken (to everyone in the group).</summary>
    public const string EventTaken = "taken";

    /// <summary>Someone said Not now (to the asker).</summary>
    public const string EventDeclined = "declined";

    /// <summary>Called off (to everyone in the group).</summary>
    public const string EventCancelled = "cancelled";

    /// <summary>Nobody took it (to the asker).</summary>
    public const string EventExpired = "expired";

    /// <summary>A later ask's time came (to whoever took it: start it now).</summary>
    public const string EventDue = "due";

    /// <summary>The steps an ask may be for later, as the schedule's postponements: 15, 30 or 60 minutes.</summary>
    public static readonly IReadOnlyList<int> LaterSteps = new[] { 15, 30, 60 };

    /// <summary>How long after its time an ask nobody took stays open.</summary>
    public static readonly TimeSpan KeepOpen = TimeSpan.FromHours(3);

    private readonly PartnerHub _partners;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, Ask> _asks = new();

    /// <summary>Initializes a new instance of the <see cref="WatchAsks"/> class.</summary>
    public WatchAsks(PartnerHub partners, TimeProvider time)
    {
        _partners = partners;
        _time = time;
    }

    /// <summary>Raised (outside the lock) when an ask's people should hear something at once.</summary>
    public event Action<AskAnnouncement>? Announced;

    /// <summary>
    /// Asks the caller's group to start a Group Watch Session of <paramref name="itemId"/>, now
    /// (<paramref name="laterMinutes"/> 0) or one of <see cref="LaterSteps"/> later.
    /// </summary>
    public AskView Create(Guid user, Guid groupId, Guid itemId, int laterMinutes)
    {
        if (laterMinutes != 0 && !LaterSteps.Contains(laterMinutes))
        {
            throw new PartnerException(PartnerError.Invalid, "Ask for now, or in 15, 30 or 60 minutes.");
        }

        var group = _partners.SessionGroup(user, groupId, itemId);
        if (group.Members.Count <= 1)
        {
            throw new PartnerException(PartnerError.Invalid, "There's nobody else in the group to ask.");
        }

        AskAnnouncement told;
        AskView view;
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            // A new ask replaces the asker's open one in that group (told as called off).
            var replaced = _asks.Values.Where(a => a.GroupId == groupId && a.From == user && a.State == Open).ToList();
            foreach (var old in replaced)
            {
                old.State = Cancelled;
            }

            var ask = new Ask { Id = Guid.NewGuid(), GroupId = groupId, ItemId = itemId, From = user, Created = now, At = now.AddMinutes(laterMinutes), State = Open };
            _asks[ask.Id] = ask;
            Prune(now);
            told = new AskAnnouncement(ask.Id, groupId, EventAsked, user, null);
            view = View(ask, user, now);
            foreach (var old in replaced)
            {
                Tell(new AskAnnouncement(old.Id, groupId, EventCancelled, user, null));
            }
        }

        Tell(told);
        return view;
    }

    /// <summary>The asks the caller should see: open ones in their groups, and their own lately (any state).</summary>
    public IReadOnlyList<AskView> Mine(Guid user)
    {
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            Due(now);
            return _asks.Values
                .Where(a => a.From == user || a.TakenBy == user || (a.State == Open && !a.Declined.Contains(user) && _partners.MembersOf(a.GroupId).Contains(user)))
                .OrderByDescending(a => a.Created)
                .Select(a => View(a, user, now))
                .ToList();
        }
    }

    /// <summary>"Start it": the caller takes the ask (their app starts the session, now or when it's time). The first one wins.</summary>
    public AskView Take(Guid user, Guid askId)
    {
        AskView view;
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var a = Visible(user, askId, now);
            if (a.From == user)
            {
                throw new PartnerException(PartnerError.Invalid, "Someone else in the group starts what you asked for.");
            }

            if (a.State != Open)
            {
                throw new PartnerException(PartnerError.Invalid, a.State == Taken ? $"{_partners.NameOf(a.TakenBy!.Value) ?? "Someone"} is already starting it." : "That ask is over.");
            }

            a.State = Taken;
            a.TakenBy = user;
            // Now: the taker's app starts it at once, so there's nothing to remind them of.
            a.DueTold = a.At <= now;
            view = View(a, user, now);
        }

        Tell(new AskAnnouncement(askId, view.GroupId, EventTaken, user, Everyone(view.GroupId)));
        return view;
    }

    /// <summary>Not now: told to the asker; once everyone else has said it, the ask is over.</summary>
    public AskView Decline(Guid user, Guid askId)
    {
        AskView view;
        Guid from;
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var a = Visible(user, askId, now);
            if (a.State != Open || a.From == user)
            {
                return View(a, user, now);
            }

            if (!a.Declined.Contains(user))
            {
                a.Declined.Add(user);
            }

            if (_partners.MembersOf(a.GroupId).Where(m => m != a.From).All(a.Declined.Contains))
            {
                a.State = Declined;
            }

            view = View(a, user, now);
            from = a.From;
        }

        Tell(new AskAnnouncement(askId, view.GroupId, EventDeclined, user, new[] { from }));
        return view;
    }

    /// <summary>The asker calls it off (told to the group).</summary>
    public void Cancel(Guid user, Guid askId)
    {
        Guid groupId;
        lock (_lock)
        {
            var now = _time.GetUtcNow();
            var a = Visible(user, askId, now);
            if (a.From != user)
            {
                throw new PartnerException(PartnerError.Invalid, "Only who asked can call it off.");
            }

            if (a.State is not (Open or Taken))
            {
                return;
            }

            a.State = Cancelled;
            groupId = a.GroupId;
        }

        Tell(new AskAnnouncement(askId, groupId, EventCancelled, user, null));
    }

    /// <summary>Whether anything waits on the clock (a later ask taken, or one still open).</summary>
    public bool AnyWaiting
    {
        get
        {
            lock (_lock)
            {
                return _asks.Values.Any(a => a.State == Open || (a.State == Taken && !a.DueTold));
            }
        }
    }

    /// <summary>Tells takers it's time, and ends asks nobody took in time (every half minute, and before each read).</summary>
    public void Due()
    {
        lock (_lock)
        {
            Due(_time.GetUtcNow());
        }
    }

    /// <summary>The asks, for the next logic.</summary>
    public IReadOnlyList<WatchAskState> ExportState()
    {
        lock (_lock)
        {
            return _asks.Values.Select(a => new WatchAskState(a.Id, a.GroupId, a.ItemId, a.From, a.Created, a.At, a.State, a.TakenBy, a.Declined.ToList(), a.DueTold)).ToList();
        }
    }

    /// <summary>Takes the previous logic's asks.</summary>
    public void ImportState(IEnumerable<WatchAskState> asks)
    {
        lock (_lock)
        {
            foreach (var x in asks)
            {
                _asks[x.Id] = new Ask
                {
                    Id = x.Id, GroupId = x.GroupId, ItemId = x.ItemId, From = x.From, Created = x.Created, At = x.At, State = x.State,
                    TakenBy = x.TakenBy, Declined = x.Declined.ToList(), DueTold = x.DueTold,
                };
            }
        }
    }

    private void Due(DateTimeOffset now)
    {
        foreach (var a in _asks.Values.ToList())
        {
            if (a.State == Taken && !a.DueTold && a.At <= now)
            {
                a.DueTold = true;
                Tell(new AskAnnouncement(a.Id, a.GroupId, EventDue, a.TakenBy, new[] { a.TakenBy!.Value }));
            }
            else if (a.State == Open && now - a.At > KeepOpen)
            {
                a.State = Expired;
                Tell(new AskAnnouncement(a.Id, a.GroupId, EventExpired, null, new[] { a.From }));
            }
        }

        Prune(now);
    }

    private void Prune(DateTimeOffset now)
    {
        foreach (var old in _asks.Values.Where(a => a.State != Open && now - a.At > KeepOpen).Select(a => a.Id).ToList())
        {
            _asks.Remove(old);
        }
    }

    private Ask Visible(Guid user, Guid askId, DateTimeOffset now)
    {
        Due(now);
        if (!_asks.TryGetValue(askId, out var a) || !(a.From == user || _partners.MembersOf(a.GroupId).Contains(user)))
        {
            throw new PartnerException(PartnerError.NotFound, "No such ask.");
        }

        return a;
    }

    private IReadOnlyList<Guid> Everyone(Guid groupId) => _partners.MembersOf(groupId);

    private AskView View(Ask a, Guid user, DateTimeOffset now) => new(
        a.Id, a.GroupId, a.ItemId, _partners.PartLabel(a.ItemId), _partners.Names(user, a.GroupId)?.Title ?? string.Empty,
        _partners.NameOf(a.From) ?? "Former account", a.From == user, a.At, a.State,
        a.TakenBy is { } t ? _partners.NameOf(t) : null, a.TakenBy == user,
        a.Declined.Select(d => _partners.NameOf(d) ?? "Former account").ToList(), now);

    private void Tell(AskAnnouncement a)
    {
        // Listeners hand it to another thread (they never call back in under the lock).
        try
        {
            Announced?.Invoke(a);
        }
        catch (Exception)
        {
            // A listener's trouble isn't the ask's.
        }
    }

    private sealed class Ask
    {
        public Guid Id { get; init; }

        public Guid GroupId { get; init; }

        public Guid ItemId { get; init; }

        public Guid From { get; init; }

        public DateTimeOffset Created { get; init; }

        public DateTimeOffset At { get; init; }

        public string State { get; set; } = Open;

        public Guid? TakenBy { get; set; }

        public List<Guid> Declined { get; init; } = new();

        public bool DueTold { get; set; }
    }
}
