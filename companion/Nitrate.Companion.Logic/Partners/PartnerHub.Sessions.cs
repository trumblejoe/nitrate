namespace Nitrate.Companion.Partners;

/// <summary>What Group Watch Sessions (<see cref="WatchSessions"/>) ask of the watch groups.</summary>
public partial class PartnerHub
{
    /// <summary>
    /// A member's group for a Group Watch Session on <paramref name="itemId"/>: the group's id, its
    /// members and title names. Throws when the caller isn't in it, or the item isn't part of one of its titles.
    /// </summary>
    public (Guid Id, IReadOnlyList<Guid> Members, string Title) SessionGroup(Guid user, Guid groupId, Guid itemId)
    {
        List<Guid> titles;
        (Guid, IReadOnlyList<Guid>, string) result;
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))
                ?? throw new PartnerException(PartnerError.NotFound, "No such group.");
            titles = g.Titles.Select(t => t.Id).ToList();
            result = (g.Id, g.Members.ToList(), g.TitleNames);
        }

        // Outside the lock: it reads the library.
        if (!Holds(titles, itemId, user))
        {
            throw new PartnerException(PartnerError.Invalid, "Only what's in the group's titles can be watched in its Group Watch Session.");
        }

        return result;
    }

    /// <summary>Someone's name as the groups show it (null: no such account).</summary>
    public string? NameOf(Guid user) => _directory.NameOf(user);

    /// <summary>A part's short label ("S1:E4", or a film's name), when the library knows one.</summary>
    public string? PartLabel(Guid itemId) => Short(_watch.Label(itemId));
}
