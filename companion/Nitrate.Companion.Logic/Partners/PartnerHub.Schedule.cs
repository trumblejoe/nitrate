namespace Nitrate.Companion.Partners;

/// <summary>
/// Something about a group's schedule change to tell its members' open apps at once: a change
/// asked for (<see cref="ScheduleNews.Ask"/>, to everyone in the group but the asker) or settled
/// (<see cref="ScheduleNews.Done"/>, to everyone in the group, with how it went).
/// </summary>
/// <param name="GroupId">The group.</param>
/// <param name="Kind"><see cref="ScheduleNews.Ask"/> or <see cref="ScheduleNews.Done"/>.</param>
/// <param name="ChangeId">The change.</param>
/// <param name="From">Who asked for it.</param>
/// <param name="Proposed">The schedule asked for.</param>
/// <param name="Outcome">For a settled change: <see cref="ScheduleNews.Passed"/>, <see cref="ScheduleNews.Failed"/> or <see cref="ScheduleNews.Lapsed"/>.</param>
/// <param name="By">Whose vote settled it (null: the asker's ask, or the clock).</param>
public record ScheduleAnnouncement(Guid GroupId, string Kind, Guid ChangeId, Guid From, GroupSchedule Proposed, string? Outcome = null, Guid? By = null);

/// <summary>The kinds and outcomes of <see cref="ScheduleAnnouncement"/> (sent with the party messages' header; older apps ignore them).</summary>
public static class ScheduleNews
{
    /// <summary>A change was asked for: agree?</summary>
    public const string Ask = "schedule-ask";

    /// <summary>A change was settled.</summary>
    public const string Done = "schedule-done";

    /// <summary>Most of the group agreed: the schedule changed.</summary>
    public const string Passed = "passed";

    /// <summary>Too many said no: the schedule stays.</summary>
    public const string Failed = "failed";

    /// <summary>Nobody settled it within <see cref="ScheduleRules.ChangeLifetime"/>: the schedule stays.</summary>
    public const string Lapsed = "lapsed";
}

/// <summary>Schedule changes, told to the group's open apps as they're asked for and settled (<see cref="ScheduleAnnounced"/>).</summary>
public partial class PartnerHub
{
    /// <summary>What's to be told once the lock is let go; guarded by the hub's lock.</summary>
    private readonly List<ScheduleAnnouncement> _scheduleSaid = new();

    /// <summary>Raised (outside the hub's lock) when a group's members should hear about a schedule change at once.</summary>
    public event Action<ScheduleAnnouncement>? ScheduleAnnounced;

    /// <summary>Lapses the changes nobody settled in time (called with the party clock); returns what was announced (also raised).</summary>
    public IReadOnlyList<ScheduleAnnouncement> DueChanges()
    {
        lock (_lock)
        {
            PruneChanges();
        }

        return TellSchedules();
    }

    /// <summary>The group's schedule now, as a member sees it (null: binge, Together off, or no such group for them).</summary>
    public GroupSchedule? ScheduleOf(Guid user, Guid groupId)
    {
        lock (_lock)
        {
            return _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user))?.Schedule?.Copy();
        }
    }

    /// <summary>The group's titles' names in order, as a member sees them (null when they're not in it).</summary>
    public IReadOnlyList<string>? TitlesOf(Guid user, Guid groupId)
    {
        lock (_lock)
        {
            var g = _state.Groups.FirstOrDefault(x => x.Id == groupId && x.Members.Contains(user));
            return g is null ? null : g.Titles.Count == 0 ? new List<string> { g.SeriesName } : g.Titles.Select(t => t.Name).ToList();
        }
    }

    /// <summary>Notes [a] to be told once the lock is let go. Call under the lock.</summary>
    private void SaySchedule(ScheduleAnnouncement a) => _scheduleSaid.Add(a);

    /// <summary>Raises what was noted under the lock. Call outside it.</summary>
    private List<ScheduleAnnouncement> TellSchedules()
    {
        List<ScheduleAnnouncement> said;
        lock (_lock)
        {
            if (_scheduleSaid.Count == 0)
            {
                return new List<ScheduleAnnouncement>();
            }

            said = _scheduleSaid.ToList();
            _scheduleSaid.Clear();
        }

        foreach (var a in said)
        {
            try
            {
                ScheduleAnnounced?.Invoke(a);
            }
            catch (Exception)
            {
                // Telling people is never in the way of the change.
            }
        }

        return said;
    }
}
