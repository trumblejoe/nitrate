namespace Nitrate.Companion.SmartCollections;

/// <summary>
/// A rule-based collection: a real Jellyfin collection the plugin keeps up to date. Every condition
/// set must hold (and); within a list, any one entry will do (or). Unset conditions don't count.
/// Kept in the plugin's configuration, so it lives in the shell; the matching is the logic's
/// (<c>SmartRuleMatching</c>).
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
}
