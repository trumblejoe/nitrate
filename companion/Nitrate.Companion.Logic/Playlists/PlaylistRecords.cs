using System.Text.Json;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Playlists;

/// <summary>
/// A playlist's own record for one person, apart from their own watch state: which entries were
/// watched in it, where each started one left off, and the entry last played from it (where it
/// carries on from). Watching an entry on its own never touches it, and a sitting of the playlist
/// never touches the person's own Watched marks or resume points.
/// </summary>
/// <param name="PlaylistId">The playlist.</param>
/// <param name="Watched">Entries watched in it.</param>
/// <param name="Resume">Where each started entry left off, ms (an entry watched to the end has none).</param>
/// <param name="LastId">The entry last played from it.</param>
/// <param name="LastAtMs">When, epoch ms.</param>
public record PlaylistRecord(Guid PlaylistId, IReadOnlyList<Guid> Watched, IReadOnlyDictionary<Guid, long> Resume, Guid? LastId, long LastAtMs)
{
    /// <summary>A playlist nothing was watched in yet.</summary>
    public static PlaylistRecord Empty(Guid playlistId) => new(playlistId, Array.Empty<Guid>(), new Dictionary<Guid, long>(), null, 0);
}

/// <summary>What a sitting of a playlist changes in its record (the apps' own rules, core <c>PlaylistRecords</c>).</summary>
public static class PlaylistRules
{
    /// <summary>Watched to here (of its length) counts as watched, as Jellyfin's own default.</summary>
    public const double WatchedShare = 0.9;

    /// <summary>Less than this in: not worth a resume point (as Jellyfin's own minimum).</summary>
    public const long MinResumeMs = 60_000;

    /// <summary>
    /// A sitting of [itemId] stopped at [positionMs] of [runtimeMs] (null: not known) at [atMs]: watched
    /// when it got to <see cref="WatchedShare"/> (or [ended]), else its resume point.
    /// </summary>
    public static PlaylistRecord Stopped(PlaylistRecord record, Guid itemId, long positionMs, long? runtimeMs, bool ended, long atMs)
    {
        var done = ended || (runtimeMs is > 0 && positionMs >= runtimeMs.Value * WatchedShare);
        var watched = record.Watched.ToList();
        if (done && !watched.Contains(itemId))
        {
            watched.Add(itemId);
        }

        var resume = record.Resume.ToDictionary(r => r.Key, r => r.Value);
        if (done || positionMs < MinResumeMs)
        {
            resume.Remove(itemId);
        }
        else
        {
            resume[itemId] = positionMs;
        }

        return new PlaylistRecord(record.PlaylistId, watched, resume, itemId, atMs);
    }
}

/// <summary>Each person's playlist records, one row per playlist in the plugin's database; each person's alone.</summary>
public class PlaylistRecordBook
{
    /// <summary>The most entries a record keeps (a record from an app holding more is refused).</summary>
    public const int MaxEntries = 20_000;

    private const string Kind = "plrec";
    private readonly CompanionDb _db;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="PlaylistRecordBook"/> class.</summary>
    public PlaylistRecordBook(CompanionDb db) => _db = db;

    /// <summary>Every record of [user]'s, most recently sat in last.</summary>
    public IReadOnlyList<PlaylistRecord> All(Guid user) =>
        _db.Records(Kind, user.ToString("N")).Select(r => Read(r.Value)).OfType<PlaylistRecord>().ToList();

    /// <summary>[user]'s record of [playlistId], or null.</summary>
    public PlaylistRecord? Get(Guid user, Guid playlistId) =>
        _db.Record(Kind, user.ToString("N"), playlistId.ToString("N")) is { } json ? Read(json) : null;

    /// <summary>
    /// Keeps [record] as [user]'s for its playlist and gives back what's kept; [onlyIfNew]: only when
    /// there's none yet (a record started from the person's own progress never replaces one kept).
    /// </summary>
    public PlaylistRecord Put(Guid user, PlaylistRecord record, bool onlyIfNew = false)
    {
        if (record.Watched.Count + record.Resume.Count > MaxEntries)
        {
            throw new ArgumentException("Too many entries");
        }

        lock (_lock)
        {
            if (onlyIfNew && Get(user, record.PlaylistId) is { } kept)
            {
                return kept;
            }

            var clean = record with { Watched = record.Watched.Distinct().ToList(), Resume = record.Resume.Where(r => r.Value > 0).ToDictionary(r => r.Key, r => r.Value) };
            Write(user, clean);
            return clean;
        }
    }

    /// <summary>[user]'s sitting of [itemId] in [playlistId] stopped: the record moves on (<see cref="PlaylistRules.Stopped"/>).</summary>
    public PlaylistRecord Stopped(Guid user, Guid playlistId, Guid itemId, long positionMs, long? runtimeMs, bool ended, long atMs)
    {
        lock (_lock)
        {
            var record = PlaylistRules.Stopped(Get(user, playlistId) ?? PlaylistRecord.Empty(playlistId), itemId, positionMs, runtimeMs, ended, atMs);
            Write(user, record);
            return record;
        }
    }

    private void Write(Guid user, PlaylistRecord record) =>
        _db.PutRecord(Kind, user.ToString("N"), record.PlaylistId.ToString("N"), JsonSerializer.Serialize(record), record.LastAtMs);

    private static PlaylistRecord? Read(string json)
    {
        try
        {
            return JsonSerializer.Deserialize<PlaylistRecord>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
