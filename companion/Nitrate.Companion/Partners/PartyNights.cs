namespace Nitrate.Companion.Partners;

/// <summary>
/// A watch group's recurring watch party: one or more weekdays (0 = Sunday … 6 = Saturday) at a
/// time of day, in the group's schedule's time zone (the server's own unless set). It's stored
/// with the schedule, so it's set by the inviter of a new group and changed afterwards by a
/// majority, exactly like the rest of the schedule.
/// </summary>
public class PartyTime
{
    /// <summary>Gets or sets the weekdays (0 = Sunday … 6 = Saturday).</summary>
    public List<int> Days { get; set; } = new();

    /// <summary>Gets or sets the hour (0–23).</summary>
    public int Hour { get; set; } = 20;

    /// <summary>Gets or sets the minute.</summary>
    public int Minute { get; set; }

    /// <summary>A copy.</summary>
    public PartyTime Copy() => new() { Days = Days.ToList(), Hour = Hour, Minute = Minute };
}

/// <summary>
/// One occurrence of a group's watch party that something happened to: pushed back, asked to be
/// postponed or skipped, people who can't make it, who started it. Identified by the group and
/// the time it was set for (<see cref="At"/>); an occurrence nobody touched has none.
/// </summary>
public class PartyNight
{
    /// <summary>Gets or sets the group.</summary>
    public Guid GroupId { get; set; }

    /// <summary>Gets or sets when it was set for by the party time (before any postponing).</summary>
    public DateTimeOffset At { get; set; }

    /// <summary>Gets or sets how many minutes later it starts now (+15s and passed postponements).</summary>
    public int Minutes { get; set; }

    /// <summary>Gets or sets the members who used their one +15 tonight.</summary>
    public List<Guid> Pushed { get; set; } = new();

    /// <summary>Gets or sets every postponement that took effect, in order (for "Suzy pushed it back 15 min").</summary>
    public List<PartyDelay> Pushes { get; set; } = new();

    /// <summary>Gets or sets a postponement (+30, +60) or "Not tonight" waiting for the group's votes.</summary>
    public PartyRequest? Request { get; set; }

    /// <summary>Gets or sets the members who can't make it tonight (the others are asked at start time whether to start without them).</summary>
    public List<Guid> CantMake { get; set; } = new();

    /// <summary>Gets or sets a value indicating whether the group agreed to skip tonight's party (the title's schedule carries on).</summary>
    public bool Skipped { get; set; }

    /// <summary>Gets or sets who joined first and so started the watch party.</summary>
    public Guid? StartedBy { get; set; }

    /// <summary>Gets or sets the watch party's live (SyncPlay) group, once its starter says.</summary>
    public string? SessionId { get; set; }

    /// <summary>Gets or sets the start time the members were last reminded of (a postponement reminds again).</summary>
    public DateTimeOffset? Reminded { get; set; }

    /// <summary>Gets or sets a value indicating whether the others were asked at start time to start without whoever can't make it.</summary>
    public bool AskedWithout { get; set; }
}

/// <summary>A postponement that took effect.</summary>
public class PartyDelay
{
    /// <summary>Gets or sets who asked for it.</summary>
    public Guid By { get; set; }

    /// <summary>Gets or sets how many minutes.</summary>
    public int Minutes { get; set; }
}

/// <summary>A request about tonight's party, waiting for the others' votes.</summary>
public class PartyRequest
{
    /// <summary>Postpone by <see cref="Minutes"/>.</summary>
    public const string Later = "later";

    /// <summary>Skip tonight's party.</summary>
    public const string Skip = "skip";

    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets who asked.</summary>
    public Guid From { get; set; }

    /// <summary>Gets or sets what (<see cref="Later"/> or <see cref="Skip"/>).</summary>
    public string Kind { get; set; } = Later;

    /// <summary>Gets or sets the minutes for <see cref="Later"/>.</summary>
    public int Minutes { get; set; }

    /// <summary>Gets or sets who said yes (the asker included).</summary>
    public List<Guid> Yes { get; set; } = new();

    /// <summary>Gets or sets who said no.</summary>
    public List<Guid> No { get; set; } = new();
}

/// <summary>A postponement as members see it.</summary>
public record PartyPushView(string Name, int Minutes, bool Mine);

/// <summary>A request about tonight's party as one member sees it ([Voted]: they answered; [Mine]: they asked).</summary>
public record PartyRequestView(Guid Id, string FromName, string Kind, int Minutes, IReadOnlyList<string> Yes, IReadOnlyList<string> No, bool Voted, bool Mine);

/// <summary>
/// A group's next (or current) watch party, as one member sees it: when it was set for
/// (<paramref name="At"/>) and when it starts now (<paramref name="StartsAt"/>), what plays
/// (<paramref name="PartId"/>, "S1:E4" or a film's name), what happened to it, and when the one
/// after it is (<paramref name="NextAt"/>, so a phone can set its reminder for that one too).
/// <paramref name="OffsetMinutes"/> is the schedule's time zone's offset now, for the apps' own
/// arithmetic between loads.
/// </summary>
public record PartyView(
    Guid GroupId, DateTimeOffset At, DateTimeOffset StartsAt, Guid? PartId, string? PartLabel,
    IReadOnlyList<PartyPushView> Pushes, bool CanPushBack, PartyRequestView? Request, IReadOnlyList<string> CantMake, bool ICantMake,
    bool Skipped, bool Started, string? StartedBy, string? SessionId, DateTimeOffset NextAt, int OffsetMinutes);

/// <summary>What joining tonight's party means for the caller: start it (they're first), or join the one already started.</summary>
public record PartyJoin(bool Start, string? SessionId, Guid? PartId);

/// <summary>Something about a group's party to tell its members' open apps at once.</summary>
/// <param name="GroupId">The group.</param>
/// <param name="Kind">See <see cref="PartyRules"/>'s announcement kinds.</param>
/// <param name="By">Who did it (null: the clock).</param>
/// <param name="Minutes">For a postponement.</param>
public record PartyAnnouncement(Guid GroupId, string Kind, Guid? By = null, int Minutes = 0);

/// <summary>A watch party's arithmetic: when it falls, which one is tonight's, and the votes.</summary>
public static class PartyRules
{
    /// <summary>Announced 15 minutes before it starts.</summary>
    public const string Reminder = "reminder";

    /// <summary>Someone pushed it back by 15 minutes, or a postponement passed.</summary>
    public const string Pushed = "pushed";

    /// <summary>Someone asked to postpone it or skip it: the others are asked to agree.</summary>
    public const string Asked = "asked";

    /// <summary>Tonight's party is off.</summary>
    public const string Skipped = "skipped";

    /// <summary>At start time with someone who can't make it: start without them?</summary>
    public const string Without = "without";

    /// <summary>Something else changed (a vote, a lapse, someone can't make it, it started).</summary>
    public const string Changed = "changed";

    /// <summary>How long before the start the reminder goes out.</summary>
    public static readonly TimeSpan Lead = TimeSpan.FromMinutes(15);

    /// <summary>How long after it starts it's still tonight's party (late joiners).</summary>
    public static readonly TimeSpan Grace = TimeSpan.FromHours(3);

    /// <summary>The longer postponements, which need the group's agreement (+15 applies at once).</summary>
    public static readonly int[] Asks = { 30, 60 };

    /// <summary>A party time as sent, made sensible (null when it names no day).</summary>
    public static PartyTime? Valid(PartyTime? p)
    {
        if (p is null)
        {
            return null;
        }

        var days = p.Days.Where(d => d is >= 0 and <= 6).Distinct().Order().ToList();
        return days.Count == 0 ? null : new PartyTime { Days = days, Hour = Math.Clamp(p.Hour, 0, 23), Minute = Math.Clamp(p.Minute, 0, 59) };
    }

    /// <summary>The first time after [after] the party is set for (null without a party time).</summary>
    public static DateTimeOffset? Next(GroupSchedule? s, DateTimeOffset after)
    {
        if (s?.Party is not { Days.Count: > 0 } p)
        {
            return null;
        }

        var zone = ScheduleRules.Zone(s);
        var local = TimeZoneInfo.ConvertTime(after, zone);
        for (var day = 0; day <= 8; day++)
        {
            var date = local.Date.AddDays(day);
            if (!p.Days.Contains((int)date.DayOfWeek))
            {
                continue;
            }

            var wall = date.AddHours(p.Hour).AddMinutes(p.Minute);
            if (zone.IsInvalidTime(wall))
            {
                // A clock change skipped it: an hour on.
                wall = wall.AddHours(1);
            }

            var at = new DateTimeOffset(wall, zone.GetUtcOffset(wall));
            if (at > after)
            {
                return at;
            }
        }

        return null;
    }

    /// <summary>
    /// Tonight's party: the first occurrence (set for [At], starting at [At] plus what [nights]
    /// say it was put back) that hasn't finished starting yet ([now] before its start plus <see cref="Grace"/>).
    /// </summary>
    public static (DateTimeOffset At, PartyNight? Night)? Tonight(GroupSchedule? s, Guid groupId, IReadOnlyList<PartyNight> nights, DateTimeOffset now)
    {
        // Far enough back for a party pushed back a long way.
        var probe = now - TimeSpan.FromDays(2);
        for (var i = 0; i < 40; i++)
        {
            if (Next(s, probe) is not { } at)
            {
                return null;
            }

            var night = nights.FirstOrDefault(n => n.GroupId == groupId && n.At == at);
            if (Start(at, night) + Grace > now)
            {
                return (at, night);
            }

            probe = at;
        }

        return null;
    }

    /// <summary>When it starts now.</summary>
    public static DateTimeOffset Start(DateTimeOffset at, PartyNight? night) => at.AddMinutes(night?.Minutes ?? 0);

    /// <summary>Whether a member may still push it back 15 minutes: once each per night, before it starts, not when it's off or started.</summary>
    public static bool CanPushBack(PartyNight? night, Guid user, DateTimeOffset at, DateTimeOffset now) =>
        now < Start(at, night) && (night is null || (!night.Pushed.Contains(user) && !night.Skipped && night.StartedBy is null));
}
