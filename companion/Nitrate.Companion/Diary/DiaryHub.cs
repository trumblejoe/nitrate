using System.Text.Json;

namespace Nitrate.Companion.Diary;

/// <summary>What was watched (filled in from the library when it's recorded).</summary>
public record DiaryTitle(Guid ItemId, string Kind, string Name, Guid? SeriesId, string? SeriesName, int? Season, int? Episode, int? Year, long? RuntimeMs, IReadOnlyList<string> Genres);

/// <summary>One watch: a title, when it was finished, and the person's own rating (1–10, half stars) and note.</summary>
public record DiaryEntry(
    Guid Id,
    Guid ItemId,
    string Kind,
    string Name,
    Guid? SeriesId,
    string? SeriesName,
    int? Season,
    int? Episode,
    int? Year,
    long? RuntimeMs,
    IReadOnlyList<string> Genres,
    long WatchedAt,
    int? Rating,
    string? Note,
    bool Logged);

/// <summary>A change to an entry: a rating (null keeps it, 0 clears it), a note (null keeps it, "" clears it), or the date.</summary>
public record DiaryEdit(int? Rating, string? Note, long? WatchedAt);

/// <summary>Where each person's diary is kept.</summary>
public interface IDiaryStorage
{
    /// <summary>A person's entries.</summary>
    List<DiaryEntry> Load(Guid userId);

    /// <summary>Saves a person's entries.</summary>
    void Save(Guid userId, List<DiaryEntry> entries);
}

/// <summary>One JSON file per person in the server's data folder.</summary>
public class FileDiaryStorage : IDiaryStorage
{
    private readonly string _folder;

    /// <summary>Initializes a new instance of the <see cref="FileDiaryStorage"/> class.</summary>
    public FileDiaryStorage(string folder)
    {
        _folder = folder;
        Directory.CreateDirectory(folder);
    }

    /// <inheritdoc />
    public List<DiaryEntry> Load(Guid userId)
    {
        var path = PathOf(userId);
        try
        {
            return File.Exists(path) ? JsonSerializer.Deserialize<List<DiaryEntry>>(File.ReadAllText(path)) ?? new() : new();
        }
        catch (JsonException)
        {
            File.Copy(path, path + ".damaged", true);
            return new();
        }
    }

    /// <inheritdoc />
    public void Save(Guid userId, List<DiaryEntry> entries)
    {
        var path = PathOf(userId);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(entries));
        File.Move(temp, path, true);
    }

    private string PathOf(Guid userId) => Path.Combine(_folder, userId.ToString("N") + ".json");
}

/// <summary>
/// The watch diary: every film and episode a person finishes, with the date (Jellyfin itself
/// keeps only the last), plus their own rating and note. Each diary is its owner's alone.
/// </summary>
public class DiaryHub
{
    /// <summary>Finishing the same title again within this long is the same watch (several stop reports).</summary>
    public static readonly TimeSpan SameWatch = TimeSpan.FromMinutes(30);

    private readonly IDiaryStorage _storage;
    private readonly TimeProvider _time;
    private readonly Dictionary<Guid, List<DiaryEntry>> _cache = new();
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="DiaryHub"/> class.</summary>
    public DiaryHub(IDiaryStorage storage, TimeProvider time)
    {
        _storage = storage;
        _time = time;
    }

    /// <summary>Records that <paramref name="userId"/> finished <paramref name="title"/> just now (once per watch).</summary>
    public DiaryEntry? Finished(Guid userId, DiaryTitle title)
    {
        var now = _time.GetUtcNow().ToUnixTimeMilliseconds();
        return Change(userId, list =>
        {
            if (list.Any(e => e.ItemId == title.ItemId && now - e.WatchedAt < SameWatch.TotalMilliseconds))
            {
                return null;
            }

            var entry = Entry(title, now, null, null, logged: false);
            list.Add(entry);
            return entry;
        });
    }

    /// <summary>A watch added by hand (watched elsewhere, or before the diary).</summary>
    public DiaryEntry Log(Guid userId, DiaryTitle title, long? watchedAt, int? rating, string? note) =>
        Change(userId, list =>
        {
            var entry = Entry(title, watchedAt ?? _time.GetUtcNow().ToUnixTimeMilliseconds(), ValidRating(rating), Note(note), logged: true);
            list.Add(entry);
            return entry;
        })!;

    /// <summary>Changes an entry's rating, note or date; null when it isn't the caller's.</summary>
    public DiaryEntry? Edit(Guid userId, Guid entryId, DiaryEdit edit) =>
        Change(userId, list =>
        {
            var i = list.FindIndex(e => e.Id == entryId);
            if (i < 0)
            {
                return null;
            }

            var e = list[i];
            list[i] = e with
            {
                Rating = edit.Rating is null ? e.Rating : edit.Rating == 0 ? null : ValidRating(edit.Rating),
                Note = edit.Note is null ? e.Note : Note(edit.Note),
                WatchedAt = edit.WatchedAt ?? e.WatchedAt,
            };
            return list[i];
        });

    /// <summary>Removes an entry; false when it isn't the caller's.</summary>
    public bool Delete(Guid userId, Guid entryId) => Change(userId, list => list.RemoveAll(e => e.Id == entryId) > 0 ? true : (bool?)null) == true;

    /// <summary>A person's entries between two moments (either open), newest first.</summary>
    public IReadOnlyList<DiaryEntry> List(Guid userId, long? from, long? to)
    {
        lock (_lock)
        {
            return Entries(userId).Where(e => (from is null || e.WatchedAt >= from) && (to is null || e.WatchedAt < to))
                .OrderByDescending(e => e.WatchedAt).ToList();
        }
    }

    private static DiaryEntry Entry(DiaryTitle t, long at, int? rating, string? note, bool logged) =>
        new(Guid.NewGuid(), t.ItemId, t.Kind, t.Name, t.SeriesId, t.SeriesName, t.Season, t.Episode, t.Year, t.RuntimeMs, t.Genres, at, rating, note, logged);

    private static int? ValidRating(int? r) => r is null ? null : Math.Clamp(r.Value, 1, 10);

    private static string? Note(string? n) => string.IsNullOrWhiteSpace(n) ? null : n.Trim()[..Math.Min(n.Trim().Length, 2000)];

    private T? Change<T>(Guid userId, Func<List<DiaryEntry>, T?> change)
    {
        lock (_lock)
        {
            var list = Entries(userId);
            var result = change(list);
            if (result is not null)
            {
                _storage.Save(userId, list);
            }

            return result;
        }
    }

    private List<DiaryEntry> Entries(Guid userId)
    {
        if (!_cache.TryGetValue(userId, out var list))
        {
            list = _storage.Load(userId);
            _cache[userId] = list;
        }

        return list;
    }
}
