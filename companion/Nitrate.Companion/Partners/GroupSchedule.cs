namespace Nitrate.Companion.Partners;

/// <summary>
/// How a watch group lets its title out: all at once (binge), or one new part (an episode, a
/// film of a list) every 2 days, every 3 days or weekly, at a time of day in the time zone it was
/// set in. Anything someone in the group has already seen always stays open, so whoever's behind
/// can catch up; only what nobody has seen yet is rationed.
/// </summary>
public class GroupSchedule
{
    /// <summary>All at once.</summary>
    public const string Binge = "binge";

    /// <summary>One every 2 days.</summary>
    public const string Days2 = "days2";

    /// <summary>One every 3 days.</summary>
    public const string Days3 = "days3";

    /// <summary>One a week, on <see cref="Day"/>.</summary>
    public const string Weekly = "weekly";

    /// <summary>Gets or sets the cadence (<see cref="Binge"/>, <see cref="Days2"/>, <see cref="Days3"/>, <see cref="Weekly"/>).</summary>
    public string Cadence { get; set; } = Binge;

    /// <summary>Gets or sets the day for <see cref="Weekly"/> (0 = Sunday … 6 = Saturday).</summary>
    public int Day { get; set; } = 4;

    /// <summary>Gets or sets the hour each new part opens (0–23).</summary>
    public int Hour { get; set; } = 19;

    /// <summary>Gets or sets the minute.</summary>
    public int Minute { get; set; }

    /// <summary>Gets or sets a value indicating whether a locked part can still be played after a warning (else it can't be played in Nitrate).</summary>
    public bool SoftLock { get; set; }

    /// <summary>Gets or sets the time zone (IANA id) the times are in; empty: the server's own.</summary>
    public string TimeZone { get; set; } = string.Empty;

    /// <summary>Gets or sets when it was set (every 2 or 3 days counts from that day).</summary>
    public DateTimeOffset Since { get; set; }

    /// <summary>Gets a value indicating whether anything is rationed.</summary>
    public bool Limits => Cadence is Days2 or Days3 or Weekly;

    /// <summary>A copy, to keep apart from requests and views.</summary>
    public GroupSchedule Copy() => (GroupSchedule)MemberwiseClone();
}

/// <summary>A member's request to change a group's schedule, and who has said yes or no.</summary>
public class ScheduleChange
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets who asked.</summary>
    public Guid From { get; set; }

    /// <summary>Gets or sets the schedule asked for.</summary>
    public GroupSchedule Proposed { get; set; } = new();

    /// <summary>Gets or sets who said yes (the asker included).</summary>
    public List<Guid> Yes { get; set; } = new();

    /// <summary>Gets or sets who said no.</summary>
    public List<Guid> No { get; set; } = new();

    /// <summary>Gets or sets when it was asked.</summary>
    public DateTimeOffset At { get; set; }
}

/// <summary>What's locked in a group's title, and when the first locked part opens (null: once someone watches the open one).</summary>
public record ScheduleLock(Guid? LockedFromId, DateTimeOffset? OpensAt)
{
    /// <summary>The day it opens in the schedule's time zone (0 = Sunday), for "Thursday 7pm".</summary>
    public int? OpensDay(GroupSchedule s) => OpensAt is { } at ? (int)TimeZoneInfo.ConvertTime(at, ScheduleRules.Zone(s)).DayOfWeek : null;
}

/// <summary>The schedule's arithmetic: release times, what's open, and votes.</summary>
public static class ScheduleRules
{
    /// <summary>How long a change request waits for votes.</summary>
    public static readonly TimeSpan ChangeLifetime = TimeSpan.FromDays(7);

    /// <summary>Whether [yes] votes out of [members] carry it: more than half (both, in a pair).</summary>
    public static bool Passes(int yes, int members) => yes * 2 > members;

    /// <summary>Whether enough said no that it can't carry any more.</summary>
    public static bool Fails(int no, int members) => !Passes(members - no, members);

    /// <summary>The schedule's time zone (the server's own when none is set; UTC when unknown here).</summary>
    public static TimeZoneInfo Zone(GroupSchedule s)
    {
        if (string.IsNullOrWhiteSpace(s.TimeZone))
        {
            return TimeZoneInfo.Local;
        }

        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById(s.TimeZone);
        }
        catch (Exception e) when (e is TimeZoneNotFoundException or InvalidTimeZoneException)
        {
            return TimeZoneInfo.Utc;
        }
    }

    /// <summary>The first time after [after] that a new part opens.</summary>
    public static DateTimeOffset NextRelease(GroupSchedule s, DateTimeOffset after)
    {
        var zone = Zone(s);
        var local = TimeZoneInfo.ConvertTime(after, zone);
        var since = TimeZoneInfo.ConvertTime(s.Since, zone).Date;
        for (var day = 0; day <= 8; day++)
        {
            var date = local.Date.AddDays(day);
            var wanted = s.Cadence switch
            {
                GroupSchedule.Weekly => (int)date.DayOfWeek == s.Day,
                GroupSchedule.Days2 => (date - since).Days % 2 == 0,
                GroupSchedule.Days3 => (date - since).Days % 3 == 0,
                _ => true,
            };
            if (!wanted)
            {
                continue;
            }

            var wall = date.AddHours(s.Hour).AddMinutes(s.Minute);
            var at = new DateTimeOffset(wall, zone.GetUtcOffset(wall));
            if (at > after)
            {
                return at;
            }
        }

        return after.AddDays(7);
    }

    /// <summary>
    /// What's locked in [order] (the title's parts in watching order) for a group whose furthest
    /// part watched by anyone is at [frontier] (-1: none), first watched at [frontierAt]: the part
    /// after the frontier opens at the first release after that (straight away if it was watched
    /// before the schedule was set, or nothing has been watched), and everything after it waits
    /// until someone has watched it.
    /// </summary>
    public static ScheduleLock Of(GroupSchedule? s, IReadOnlyList<Guid> order, int frontier, DateTimeOffset? frontierAt, DateTimeOffset now)
    {
        if (s is null || !s.Limits)
        {
            return new ScheduleLock(null, null);
        }

        var next = frontier + 1;
        if (next >= order.Count)
        {
            return new ScheduleLock(null, null);
        }

        var opensAt = frontierAt is { } at && at >= s.Since ? NextRelease(s, at) : (DateTimeOffset?)null;
        if (opensAt is { } o && now < o)
        {
            return new ScheduleLock(order[next], o);
        }

        return next + 1 < order.Count ? new ScheduleLock(order[next + 1], null) : new ScheduleLock(null, null);
    }
}
