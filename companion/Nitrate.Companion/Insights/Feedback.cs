using System.Text.Json;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>Which found part someone said was wrong.</summary>
public enum SegmentPart
{
    /// <summary>The intro.</summary>
    Intro,

    /// <summary>The "Previously on…" recap.</summary>
    Recap,

    /// <summary>The final credits (skipped or Up Next shown too early).</summary>
    Credits,
}

/// <summary>One person's say on one part of one title.</summary>
public record SegmentVote(Guid UserId, SegmentPart Part, bool Admin, long At);

/// <summary>
/// "That intro (recap, credits) was wrong", from people's own actions in the apps (going straight
/// back after a skip, "Watch credits" after the credits were skipped). An admin's word, or two
/// different people's, rejects the part for that version of the file: Jellyfin is handed none for
/// it, and the nightly tasks keep it rejected until the file changes.
/// </summary>
public class SegmentVotes
{
    /// <summary>How many different people (not admins) it takes.</summary>
    public const int Needed = 2;

    private const string Kind = "segment-vote";
    private readonly CompanionDb _db;

    /// <summary>Initializes a new instance of the <see cref="SegmentVotes"/> class.</summary>
    public SegmentVotes(CompanionDb db) => _db = db;

    /// <summary>Notes a vote; true when the part now counts as wrong.</summary>
    public bool Add(Guid itemId, SegmentVote vote)
    {
        var owner = itemId.ToString("N");
        _db.PutRecord(Kind, owner, vote.UserId.ToString("N") + "|" + vote.Part, JsonSerializer.Serialize(vote), vote.At);
        return Decided(Votes(itemId).Where(v => v.Part == vote.Part).ToList());
    }

    /// <summary>Every vote on a title.</summary>
    public IReadOnlyList<SegmentVote> Votes(Guid itemId) =>
        _db.Records(Kind, itemId.ToString("N")).Select(r => DbDiaryStorage.Read<SegmentVote>(r.Value)).OfType<SegmentVote>().ToList();

    /// <summary>Whether these votes (on one part) reject it.</summary>
    public static bool Decided(IReadOnlyCollection<SegmentVote> votes) =>
        votes.Any(v => v.Admin) || votes.Select(v => v.UserId).Distinct().Count() >= Needed;
}

/// <summary>How long one sitting is counted from the player's own reports.</summary>
public class SittingClock
{
    /// <summary>A gap between reports longer than this isn't counted (the player went quiet).</summary>
    public static readonly TimeSpan MaxGap = TimeSpan.FromSeconds(90);

    private DateTimeOffset _last;
    private bool _paused;

    /// <summary>Initializes a new instance of the <see cref="SittingClock"/> class.</summary>
    public SittingClock(DateTimeOffset start)
    {
        Started = start;
        _last = start;
    }

    /// <summary>Gets when it started.</summary>
    public DateTimeOffset Started { get; }

    /// <summary>Gets the time watched so far (not paused).</summary>
    public TimeSpan Watched { get; private set; }

    /// <summary>A report from the player at [now].</summary>
    public void Tick(DateTimeOffset now, bool paused)
    {
        var gap = now - _last;
        if (!_paused && gap > TimeSpan.Zero)
        {
            Watched += gap > MaxGap ? MaxGap : gap;
        }

        _last = now;
        _paused = paused;
    }
}

/// <summary>One sitting: who watched what, when, and for how long (pauses not counted).</summary>
public record Sitting(Guid ItemId, string Kind, string Name, string? SeriesName, long StartedAt, long EndedAt, long WatchedMs);

/// <summary>One kind of trouble one device had with one title.</summary>
public record PlaybackProblem(Guid ItemId, string Name, string DeviceId, string DeviceName, string Kind, string? Reason, int Count, long FirstAt, long LastAt);

/// <summary>A title that misbehaves, across the household's devices (admins).</summary>
public record ProblemTitle(Guid ItemId, string Name, int Devices, int Failed, int Converted, IReadOnlyList<string> Reasons, IReadOnlyList<string> DeviceNames, long LastAt);

/// <summary>A saved moment in a film or episode, optionally shown to a watch group.</summary>
public record Moment(Guid Id, Guid UserId, string UserName, Guid ItemId, long PositionMs, string? Note, Guid? GroupId, long At);

/// <summary>One chat message as kept for the people who were in that viewing.</summary>
public record ChatRecord(Guid From, string Name, string Kind, string Text, long? PositionMs, long At, IReadOnlyList<Guid> Members);

/// <summary>Every new thing the database keeps for people: sittings, problems, moments, chat.</summary>
public class PeopleRecords
{
    /// <summary>How long problem reports are kept.</summary>
    public const int ProblemDays = 90;

    /// <summary>How long viewing chat is kept.</summary>
    public const int ChatDays = 3 * 365;

    /// <summary>The most moments one person keeps.</summary>
    public const int MaxMoments = 500;

    private readonly CompanionDb _db;
    private readonly object _lock = new();
    private int _writes;

    /// <summary>Initializes a new instance of the <see cref="PeopleRecords"/> class.</summary>
    public PeopleRecords(CompanionDb db) => _db = db;

    /// <summary>Keeps a sitting.</summary>
    public void AddSitting(Guid user, Sitting sitting) =>
        _db.PutRecord("sitting", user.ToString("N"), sitting.StartedAt + "|" + sitting.ItemId.ToString("N"), JsonSerializer.Serialize(sitting), sitting.StartedAt);

    /// <summary>One person's sittings in [from, to) (ms).</summary>
    public IReadOnlyList<Sitting> Sittings(Guid user, long from, long to) =>
        _db.RecordsBetween("sitting", user.ToString("N"), from, to).Select(r => DbDiaryStorage.Read<Sitting>(r.Value)).OfType<Sitting>().ToList();

    /// <summary>Notes a problem (counted per title, device and kind).</summary>
    public void AddProblem(PlaybackProblem report)
    {
        lock (_lock)
        {
            var key = report.ItemId.ToString("N") + "|" + report.DeviceId + "|" + report.Kind;
            var before = _db.Record("problem", "all", key) is { } json ? DbDiaryStorage.Read<PlaybackProblem>(json) : null;
            var now = before is null ? report : report with { Count = before.Count + 1, FirstAt = before.FirstAt };
            _db.PutRecord("problem", "all", key, JsonSerializer.Serialize(now), now.LastAt);
            Prune();
        }
    }

    /// <summary>Titles with trouble, those on the most devices first.</summary>
    public IReadOnlyList<ProblemTitle> Problems() =>
        _db.Records("problem", "all").Select(r => DbDiaryStorage.Read<PlaybackProblem>(r.Value)).OfType<PlaybackProblem>()
            .GroupBy(p => p.ItemId)
            .Select(g => new ProblemTitle(
                g.Key,
                g.First().Name,
                g.Select(p => p.DeviceId).Distinct().Count(),
                g.Where(p => p.Kind == "Failed").Sum(p => p.Count),
                g.Where(p => p.Kind == "Converted").Sum(p => p.Count),
                g.Select(p => p.Reason).OfType<string>().Distinct().Take(5).ToList(),
                g.Select(p => p.DeviceName).Distinct().ToList(),
                g.Max(p => p.LastAt)))
            .OrderByDescending(t => t.Devices).ThenByDescending(t => t.Failed).ThenByDescending(t => t.LastAt)
            .ToList();

    /// <summary>Keeps a moment.</summary>
    public void PutMoment(Moment moment)
    {
        var owner = moment.UserId.ToString("N");
        _db.PutRecord("moment", owner, moment.Id.ToString("N"), JsonSerializer.Serialize(moment), moment.At);
        _db.KeepNewest("moment", owner, MaxMoments);
    }

    /// <summary>One person's moments.</summary>
    public IReadOnlyList<Moment> Moments(Guid user) =>
        _db.Records("moment", user.ToString("N")).Select(r => DbDiaryStorage.Read<Moment>(r.Value)).OfType<Moment>().ToList();

    /// <summary>Forgets one of a person's moments.</summary>
    public void DeleteMoment(Guid user, Guid id) => _db.DeleteRecord("moment", user.ToString("N"), id.ToString("N"));

    /// <summary>Keeps one chat message under the title being watched.</summary>
    public void AddChat(Guid itemId, string key, ChatRecord message)
    {
        _db.PutRecord("chat", itemId.ToString("N"), key, JsonSerializer.Serialize(message), message.At);
        lock (_lock)
        {
            Prune();
        }
    }

    /// <summary>A title's kept chat that [user] was part of, oldest first.</summary>
    public IReadOnlyList<ChatRecord> Chat(Guid itemId, Guid user) =>
        _db.Records("chat", itemId.ToString("N")).Select(r => DbDiaryStorage.Read<ChatRecord>(r.Value)).OfType<ChatRecord>()
            .Where(m => m.Members.Contains(user)).ToList();

    private void Prune()
    {
        if (_writes++ % 200 != 0)
        {
            return;
        }

        var now = DateTimeOffset.UtcNow;
        _db.DeleteOlder("problem", now.AddDays(-ProblemDays).ToUnixTimeMilliseconds());
        _db.DeleteOlder("chat", now.AddDays(-ChatDays).ToUnixTimeMilliseconds());
        _db.DeleteOlder(TimelineNoteBook.Kind, now.AddDays(-ChatDays).ToUnixTimeMilliseconds());
    }
}
