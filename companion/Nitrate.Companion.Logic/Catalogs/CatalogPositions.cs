using System.Collections.Concurrent;
using System.Text.Json;

namespace Nitrate.Companion.Catalogs;

/// <summary>
/// Where each person is in catalog books (a page, or for an EPUB thousandths of the book), one row
/// per person and book in the plugin's database, written as the place changes. Kept here rather
/// than on the catalog, which the whole household reaches with one sign-in and so can't tell people
/// apart. The older one-file-per-person folder is moved in once.
/// </summary>
public sealed class CatalogPositions
{
    /// <summary>The most books remembered per person (the least recently read go first).</summary>
    public const int Keep = 2000;

    private const string Kind = "catalog-place";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly Nitrate.Companion.Storage.CompanionDb _db;
    private readonly ConcurrentDictionary<Guid, int> _sets = new();

    /// <summary>Initializes a new instance of the <see cref="CatalogPositions"/> class ([legacyDir]: the older folder, moved in once).</summary>
    public CatalogPositions(Nitrate.Companion.Storage.CompanionDb db, string? legacyDir = null)
    {
        _db = db;
        Import(legacyDir);
    }

    /// <summary>Where <paramref name="user"/> is in a book, or null.</summary>
    public int? Get(Guid user, string catalogId, string bookId) =>
        int.TryParse(_db.Record(Kind, user.ToString("N"), Key(catalogId, bookId)), out var page) ? page : null;

    /// <summary>Remembers where <paramref name="user"/> is in a book.</summary>
    public void Set(Guid user, string catalogId, string bookId, int page)
    {
        var owner = user.ToString("N");
        _db.PutRecord(Kind, owner, Key(catalogId, bookId), Math.Max(0, page).ToString(System.Globalization.CultureInfo.InvariantCulture), DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        // Now and then, only the most recently read books are kept.
        if (_sets.AddOrUpdate(user, 1, (_, n) => n + 1) % 100 == 1)
        {
            _db.KeepNewest(Kind, owner, Keep);
        }
    }

    private static string Key(string catalogId, string bookId) => catalogId + "|" + bookId;

    private void Import(string? dir)
    {
        if (dir == null || !Directory.Exists(dir))
        {
            return;
        }

        foreach (var file in Directory.EnumerateFiles(dir, "*.json").ToList())
        {
            if (Guid.TryParseExact(Path.GetFileNameWithoutExtension(file), "N", out var user))
            {
                try
                {
                    var at = 0L;
                    foreach (var pair in JsonSerializer.Deserialize<string[][]>(File.ReadAllText(file), Json) ?? [])
                    {
                        // Oldest first in the file: kept in that order.
                        if (pair.Length == 2 && int.TryParse(pair[1], out _))
                        {
                            _db.PutRecord(Kind, user.ToString("N"), pair[0], pair[1], at++);
                        }
                    }
                }
                catch (JsonException)
                {
                    // Unreadable: places start afresh from the catalog's own.
                }
            }

            File.Delete(file);
        }

        if (!Directory.EnumerateFileSystemEntries(dir).Any())
        {
            Directory.Delete(dir);
        }
    }
}
