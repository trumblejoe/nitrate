using System.Collections.Concurrent;
using System.Text.Json;

namespace Nitrate.Companion.Catalogs;

/// <summary>
/// Where each person is in catalog books (a page, or for an EPUB thousandths of the book), one
/// file per person. Kept here rather than on the catalog, which the whole household reaches with
/// one sign-in and so can't tell people apart.
/// </summary>
public sealed class CatalogPositions
{
    /// <summary>The most books remembered per person (the least recently read go first).</summary>
    public const int Keep = 2000;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly string _dir;
    private readonly ConcurrentDictionary<Guid, object> _locks = new();

    /// <summary>Initializes a new instance of the <see cref="CatalogPositions"/> class.</summary>
    public CatalogPositions(string dir) => _dir = dir;

    /// <summary>Where <paramref name="user"/> is in a book, or null.</summary>
    public int? Get(Guid user, string catalogId, string bookId)
    {
        lock (Lock(user))
        {
            return Read(user).TryGetValue(Key(catalogId, bookId), out var page) ? page : null;
        }
    }

    /// <summary>Remembers where <paramref name="user"/> is in a book.</summary>
    public void Set(Guid user, string catalogId, string bookId, int page)
    {
        lock (Lock(user))
        {
            var all = Read(user);
            var key = Key(catalogId, bookId);
            all.Remove(key);
            all[key] = Math.Max(0, page);
            // Dictionaries keep insertion order while nothing is removed in between: oldest first.
            var kept = all.Count > Keep ? all.Skip(all.Count - Keep).ToDictionary(kv => kv.Key, kv => kv.Value) : all;
            Directory.CreateDirectory(_dir);
            var file = Path.Combine(_dir, user.ToString("N") + ".json");
            File.WriteAllText(file + ".tmp", JsonSerializer.Serialize(kept.Select(kv => new[] { kv.Key, kv.Value.ToString(System.Globalization.CultureInfo.InvariantCulture) }), Json));
            File.Move(file + ".tmp", file, overwrite: true);
        }
    }

    private static string Key(string catalogId, string bookId) => catalogId + "|" + bookId;

    private object Lock(Guid user) => _locks.GetOrAdd(user, _ => new object());

    private Dictionary<string, int> Read(Guid user)
    {
        var file = Path.Combine(_dir, user.ToString("N") + ".json");
        var map = new Dictionary<string, int>();
        if (!File.Exists(file))
        {
            return map;
        }

        try
        {
            foreach (var pair in JsonSerializer.Deserialize<string[][]>(File.ReadAllText(file), Json) ?? [])
            {
                if (pair.Length == 2 && int.TryParse(pair[1], out var page))
                {
                    map[pair[0]] = page;
                }
            }
        }
        catch (JsonException)
        {
        }

        return map;
    }
}
