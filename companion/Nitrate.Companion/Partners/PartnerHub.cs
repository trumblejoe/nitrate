namespace Nitrate.Companion.Partners;

/// <summary>Why a partner request was refused.</summary>
public enum PartnerError
{
    /// <summary>Nothing of the caller's by that id.</summary>
    NotFound,

    /// <summary>The request can't be made as sent (the message says why).</summary>
    Invalid,
}

/// <summary>A refused partner request.</summary>
public class PartnerException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="PartnerException"/> class.</summary>
    public PartnerException(PartnerError error, string message)
        : base(message) => Error = error;

    /// <summary>Gets why.</summary>
    public PartnerError Error { get; }
}

/// <summary>A partner, as one of the two sees the partnership.</summary>
public record PartnerView(Guid Id, Guid UserId, string UserName, IReadOnlyList<SharedShow> Shows, DateTimeOffset Since);

/// <summary>A request someone sent the caller.</summary>
public record IncomingView(Guid Id, Guid FromId, string FromName, Guid SeriesId, string SeriesName, DateTimeOffset At);

/// <summary>A request the caller sent: always shown as waiting until it's accepted or lapses.</summary>
public record OutgoingView(Guid Id, string To, Guid SeriesId, string SeriesName, DateTimeOffset At);

/// <summary>Where a partner is in a shared show.</summary>
public record PartnerProgressView(Guid UserId, string UserName, IReadOnlyList<Guid> Watched, string? Last, Guid? NextId);

/// <summary>
/// Watch partners: two people who keep shows in step, made by request and approval, and who's
/// private. Private accounts (marked by an admin, or by themselves) never appear in any list and
/// can't be found by name; a request to a typed name always reads "sent" and stays waiting until
/// it's accepted or lapses, whether or not that account exists, was declined, or is private. So
/// nobody learns about a private account unless its owner accepts.
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
    /// Asks someone to watch a show together: by id (someone the caller can see) or by exact name
    /// (anyone, private too). Always "sent": nothing tells the caller whether the account exists.
    /// </summary>
    public OutgoingView Request(Guid from, Guid? toId, string? toName, Guid seriesId, string seriesName)
    {
        if (seriesId == Guid.Empty)
        {
            throw new PartnerException(PartnerError.Invalid, "Choose a show to watch together.");
        }

        lock (_lock)
        {
            Prune();
            if (_state.Requests.Count(r => r.From == from) >= MaxOutgoing)
            {
                throw new PartnerException(PartnerError.Invalid, "You have too many requests waiting. Cancel some first.");
            }

            Guid to;
            string label;
            if (toId is { } id)
            {
                // By id only for people the caller can see (or already partners with); anything else is treated as nobody.
                var name = _directory.NameOf(id);
                var known = name is not null && (!IsPrivate(id) || FindPartnership(from, id) is not null);
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

            // Already partners on this show: nothing to ask (only when the caller can know it).
            if (to != Guid.Empty && FindPartnership(from, to) is { } p && p.Shows.Any(s => s.SeriesId == seriesId))
            {
                throw new PartnerException(PartnerError.Invalid, $"You already watch {seriesName} together.");
            }

            var existing = _state.Requests.FirstOrDefault(r => r.From == from && r.SeriesId == seriesId
                && (to != Guid.Empty ? r.To == to : string.Equals(r.ToLabel, label, StringComparison.OrdinalIgnoreCase)));
            if (existing is null)
            {
                existing = new PartnerRequest { Id = Guid.NewGuid(), From = from, To = to, ToLabel = label, SeriesId = seriesId, SeriesName = seriesName.Trim(), At = _time.GetUtcNow() };
                _state.Requests.Add(existing);
                _storage.Save(_state);
            }

            return Outgoing(existing);
        }
    }

    /// <summary>Requests waiting for the caller's answer.</summary>
    public IReadOnlyList<IncomingView> Incoming(Guid user)
    {
        lock (_lock)
        {
            Prune();
            return _state.Requests.Where(r => r.To == user && !r.Declined)
                .Select(r => new IncomingView(r.Id, r.From, _directory.NameOf(r.From) ?? "Someone", r.SeriesId, r.SeriesName, r.At))
                .OrderBy(r => r.At).ToList();
        }
    }

    /// <summary>The caller's own requests, all shown as waiting.</summary>
    public IReadOnlyList<OutgoingView> Outgoing(Guid user)
    {
        lock (_lock)
        {
            Prune();
            return _state.Requests.Where(r => r.From == user).Select(Outgoing).OrderBy(r => r.At).ToList();
        }
    }

    /// <summary>Says yes: makes (or adds the show to) the partnership.</summary>
    public PartnerView Accept(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            Prune();
            var r = _state.Requests.FirstOrDefault(x => x.Id == requestId && x.To == user && !x.Declined)
                ?? throw new PartnerException(PartnerError.NotFound, "That request has lapsed.");
            _state.Requests.Remove(r);
            var p = FindPartnership(r.From, user);
            if (p is null)
            {
                p = new Partnership { Id = Guid.NewGuid(), Users = new() { r.From, user }, Since = _time.GetUtcNow() };
                _state.Partnerships.Add(p);
            }

            if (p.Shows.All(s => s.SeriesId != r.SeriesId))
            {
                p.Shows.Add(new SharedShow { SeriesId = r.SeriesId, Name = r.SeriesName, AddedBy = r.From });
            }

            _storage.Save(_state);
            return View(p, user);
        }
    }

    /// <summary>Says no. The asker isn't told: their request waits until it lapses.</summary>
    public void Decline(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            var r = _state.Requests.FirstOrDefault(x => x.Id == requestId && x.To == user && !x.Declined)
                ?? throw new PartnerException(PartnerError.NotFound, "That request has lapsed.");
            r.Declined = true;
            _storage.Save(_state);
        }
    }

    /// <summary>The asker withdraws a request.</summary>
    public void Cancel(Guid user, Guid requestId)
    {
        lock (_lock)
        {
            if (_state.Requests.RemoveAll(x => x.Id == requestId && x.From == user) == 0)
            {
                throw new PartnerException(PartnerError.NotFound, "No such request.");
            }

            _storage.Save(_state);
        }
    }

    /// <summary>The caller's partners and their shared shows.</summary>
    public IReadOnlyList<PartnerView> Partners(Guid user)
    {
        lock (_lock)
        {
            return _state.Partnerships.Where(p => p.Users.Contains(user)).Select(p => View(p, user)).OrderBy(p => p.UserName, StringComparer.OrdinalIgnoreCase).ToList();
        }
    }

    /// <summary>Adds a show to a partnership (either partner, no new approval needed).</summary>
    public PartnerView AddShow(Guid user, Guid partnershipId, Guid seriesId, string name)
    {
        lock (_lock)
        {
            var p = Mine(user, partnershipId);
            if (p.Shows.All(s => s.SeriesId != seriesId))
            {
                p.Shows.Add(new SharedShow { SeriesId = seriesId, Name = name.Trim(), AddedBy = user });
                _storage.Save(_state);
            }

            return View(p, user);
        }
    }

    /// <summary>Stops watching a show together (either partner).</summary>
    public PartnerView RemoveShow(Guid user, Guid partnershipId, Guid seriesId)
    {
        lock (_lock)
        {
            var p = Mine(user, partnershipId);
            p.Shows.RemoveAll(s => s.SeriesId == seriesId);
            _storage.Save(_state);
            return View(p, user);
        }
    }

    /// <summary>Ends a partnership (either partner).</summary>
    public void End(Guid user, Guid partnershipId)
    {
        lock (_lock)
        {
            _state.Partnerships.Remove(Mine(user, partnershipId));
            _storage.Save(_state);
        }
    }

    /// <summary>Where each of the caller's partners on a show is.</summary>
    public IReadOnlyList<PartnerProgressView> Progress(Guid user, Guid seriesId)
    {
        List<Guid> partners;
        lock (_lock)
        {
            partners = PartnersOn(user, seriesId);
        }

        return partners.Select(id =>
        {
            var p = _watch.Progress(id, seriesId);
            return new PartnerProgressView(id, _directory.NameOf(id) ?? "Partner", p.Watched, p.Last, p.NextId);
        }).ToList();
    }

    /// <summary>
    /// Watched together: marks the episode watched for those of <paramref name="partnerIds"/> who
    /// are the caller's partners on its show; returns who it was marked for.
    /// </summary>
    public IReadOnlyList<Guid> MarkTogether(Guid user, Guid itemId, IReadOnlyList<Guid> partnerIds)
    {
        var series = _watch.SeriesOf(itemId) ?? throw new PartnerException(PartnerError.Invalid, "Only episodes of shared shows can be marked together.");
        List<Guid> allowed;
        lock (_lock)
        {
            allowed = PartnersOn(user, series).Where(partnerIds.Contains).ToList();
        }

        foreach (var id in allowed)
        {
            _watch.MarkWatched(id, itemId);
        }

        return allowed;
    }

    private List<Guid> PartnersOn(Guid user, Guid seriesId) =>
        _state.Partnerships.Where(p => p.Users.Contains(user) && p.Shows.Any(s => s.SeriesId == seriesId)).Select(p => p.Other(user))
            .Where(id => _directory.NameOf(id) is not null).ToList();

    private Partnership? FindPartnership(Guid a, Guid b) =>
        _state.Partnerships.FirstOrDefault(p => p.Users.Contains(a) && p.Users.Contains(b));

    private Partnership Mine(Guid user, Guid id) =>
        _state.Partnerships.FirstOrDefault(p => p.Id == id && p.Users.Contains(user)) ?? throw new PartnerException(PartnerError.NotFound, "No such partner.");

    private PartnerView View(Partnership p, Guid user)
    {
        var other = p.Other(user);
        return new PartnerView(p.Id, other, _directory.NameOf(other) ?? "Former account", p.Shows.ToList(), p.Since);
    }

    private static OutgoingView Outgoing(PartnerRequest r) => new(r.Id, r.ToLabel, r.SeriesId, r.SeriesName, r.At);

    private void Prune()
    {
        var cutoff = _time.GetUtcNow() - RequestLifetime;
        if (_state.Requests.RemoveAll(r => r.At < cutoff) > 0)
        {
            _storage.Save(_state);
        }
    }
}
