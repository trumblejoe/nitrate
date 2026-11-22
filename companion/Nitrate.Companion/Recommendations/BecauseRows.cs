using System.Text.Json;

namespace Nitrate.Companion.Recommendations;

/// <summary>What a row is based on: a film, or the show an episode belongs to.</summary>
/// <param name="Id">The film or show.</param>
/// <param name="Name">Its name.</param>
public record BecauseSeed(Guid Id, string Name);

/// <summary>One "Because you watched" row as kept: its seed and the titles like it, best first.</summary>
/// <param name="SeedId">The film or show it's based on.</param>
/// <param name="SeedName">Its name ("Because you watched …").</param>
/// <param name="ItemIds">Titles like it, best first (filtered for what's been watched when read).</param>
public record BecauseRow(Guid SeedId, string SeedName, IReadOnlyList<Guid> ItemIds);

/// <summary>A person's rows and when they were made.</summary>
/// <param name="Built">When they were made.</param>
/// <param name="Rows">The rows.</param>
public record BecauseSet(DateTimeOffset Built, IReadOnlyList<BecauseRow> Rows);

/// <summary>
/// The rules for "Because you watched", as the apps have them (core <c>BecauseYouWatched</c>):
/// seeds are the most recently watched films and shows, each row the titles like its seed that
/// aren't watched, aren't a seed and aren't in an earlier row; rows with too few are dropped.
/// </summary>
public static class BecauseRows
{
    /// <summary>Rows made (the apps show two).</summary>
    public const int Rows = 2;

    /// <summary>Titles kept per row, more than shown so watching some still leaves a full row.</summary>
    public const int Keep = 30;

    /// <summary>The fewest titles worth a row.</summary>
    public const int MinItems = 3;

    /// <summary>Distinct seeds, most recent first.</summary>
    public static List<BecauseSeed> Seeds(IEnumerable<BecauseSeed> recent, int count) =>
        recent.GroupBy(s => s.Id).Select(g => g.First()).Take(count).ToList();

    /// <summary>
    /// The rows from [seeds] (tried in order, only as many as it takes), with [similar] giving titles
    /// like a seed and [watched] saying what's been seen.
    /// </summary>
    public static async Task<List<BecauseRow>> BuildAsync(IReadOnlyList<BecauseSeed> seeds, Func<BecauseSeed, Task<IReadOnlyList<Guid>>> similar, Func<Guid, bool> watched)
    {
        var shown = new HashSet<Guid>(seeds.Select(s => s.Id));
        var rows = new List<BecauseRow>();
        foreach (var seed in seeds)
        {
            if (rows.Count == Rows)
            {
                break;
            }

            var like = (await similar(seed).ConfigureAwait(false)).Where(id => !shown.Contains(id) && !watched(id)).Distinct().Take(Keep).ToList();
            if (like.Count < MinItems)
            {
                continue;
            }

            shown.UnionWith(like);
            rows.Add(new BecauseRow(seed.Id, seed.Name, like));
        }

        return rows;
    }

    /// <summary>What a row shows now: its titles not watched since, at most [limit], or none when too few are left.</summary>
    public static IReadOnlyList<Guid> Showing(BecauseRow row, Func<Guid, bool> watched, int limit) =>
        row.ItemIds.Where(id => !watched(id)).ToList() is var left && left.Count >= MinItems ? left.Take(limit).ToList() : Array.Empty<Guid>();
}

/// <summary>Each person's rows, one JSON file each in the server's data folder.</summary>
public class BecauseStore
{
    private readonly string _folder;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="BecauseStore"/> class.</summary>
    public BecauseStore(string folder)
    {
        _folder = folder;
        Directory.CreateDirectory(folder);
    }

    /// <summary>The person's rows, or null when none were made yet.</summary>
    public BecauseSet? Get(Guid user)
    {
        lock (_lock)
        {
            var path = PathOf(user);
            try
            {
                return File.Exists(path) ? JsonSerializer.Deserialize<BecauseSet>(File.ReadAllText(path)) : null;
            }
            catch (JsonException)
            {
                return null;
            }
        }
    }

    /// <summary>Keeps the person's rows.</summary>
    public void Put(Guid user, BecauseSet set)
    {
        lock (_lock)
        {
            var path = PathOf(user);
            File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(set));
            File.Move(path + ".tmp", path, true);
        }
    }

    private string PathOf(Guid user) => Path.Combine(_folder, user.ToString("N") + ".json");
}
