namespace Nitrate.Companion.SmartCollections;

/// <summary>
/// A rule-based collection: a real Jellyfin collection the plugin keeps up to date. Every condition
/// set must hold (and); within a list, any one entry will do (or). Unset conditions don't count.
/// </summary>
public class SmartRule
{
    /// <summary>Gets or sets the rule's id (kept, so its collection stays the same one).</summary>
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>Gets or sets the collection's name.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether the rule is on.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>Gets or sets what it gathers: "Movie", "Series" (empty: both).</summary>
    public string[] Types { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets the libraries to look in (empty: all).</summary>
    public string[] LibraryIds { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets genres, any of which matches.</summary>
    public string[] Genres { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets tags, any of which matches.</summary>
    public string[] Tags { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets studios, any of which matches.</summary>
    public string[] Studios { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets age ratings, any of which matches ("PG-13", "TV-MA").</summary>
    public string[] AgeRatings { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets the earliest release year.</summary>
    public int? YearFrom { get; set; }

    /// <summary>Gets or sets the latest release year.</summary>
    public int? YearTo { get; set; }

    /// <summary>Gets or sets the lowest community rating (0–10).</summary>
    public double? MinRating { get; set; }

    /// <summary>Gets or sets the longest running time in minutes (films).</summary>
    public int? MaxMinutes { get; set; }

    /// <summary>Gets or sets: added to the library within this many days.</summary>
    public int? AddedWithinDays { get; set; }

    /// <summary>Gets or sets a value indicating whether only 4K (2160p and up) titles count.</summary>
    public bool Only4K { get; set; }

    /// <summary>Gets or sets a value indicating whether only HDR titles (HDR10, HDR10+, HLG, Dolby Vision) count.</summary>
    public bool OnlyHdr { get; set; }

    /// <summary>Gets or sets the most titles (newest added first); 0: no limit.</summary>
    public int Limit { get; set; }

    /// <summary>Gets a value indicating whether the rule needs each title's video streams (4K or HDR).</summary>
    public bool NeedsStreams => Only4K || OnlyHdr;

    /// <summary>Whether [item] belongs in the collection, at [now].</summary>
    public bool Matches(ItemFacts item, DateTimeOffset now)
    {
        static bool Any(string[] wanted, IEnumerable<string> have) =>
            wanted.Length == 0 || have.Any(h => wanted.Any(w => string.Equals(w.Trim(), h, StringComparison.OrdinalIgnoreCase)));

        return (Types.Length == 0 || Types.Contains(item.Type, StringComparer.OrdinalIgnoreCase))
            && (LibraryIds.Length == 0 || (item.LibraryId is { } lib && LibraryIds.Any(l => Same(l, lib))))
            && Any(Genres, item.Genres)
            && Any(Tags, item.Tags)
            && Any(Studios, item.Studios)
            && Any(AgeRatings, item.AgeRating is null ? Array.Empty<string>() : new[] { item.AgeRating })
            && (YearFrom is null || item.Year >= YearFrom)
            && (YearTo is null || (item.Year is { } y && y <= YearTo))
            && (MinRating is null || item.Rating >= MinRating)
            && (MaxMinutes is null || (item.Minutes is { } m && m <= MaxMinutes))
            && (AddedWithinDays is null || now - item.Added <= TimeSpan.FromDays(AddedWithinDays.Value))
            && (!Only4K || item.Height >= 2000 || item.Width >= 3800)
            && (!OnlyHdr || item.Hdr);
    }

    /// <summary>The titles that belong, newest added first, at most [Limit].</summary>
    public IReadOnlyList<Guid> Select(IEnumerable<ItemFacts> items, DateTimeOffset now)
    {
        var chosen = items.Where(i => Matches(i, now)).OrderByDescending(i => i.Added);
        return (Limit > 0 ? chosen.Take(Limit) : chosen).Select(i => i.Id).ToList();
    }

    private static bool Same(string a, Guid b) => Guid.TryParse(a, out var g) && g == b;
}

/// <summary>What a rule looks at in a title (taken from the library, so rules can be tested alone).</summary>
public record ItemFacts(
    Guid Id,
    string Type,
    Guid? LibraryId,
    IReadOnlyList<string> Genres,
    IReadOnlyList<string> Tags,
    IReadOnlyList<string> Studios,
    string? AgeRating,
    int? Year,
    double? Rating,
    int? Minutes,
    DateTimeOffset Added,
    int Width,
    int Height,
    bool Hdr);
