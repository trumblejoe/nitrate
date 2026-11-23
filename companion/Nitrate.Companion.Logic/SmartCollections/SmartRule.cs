namespace Nitrate.Companion.SmartCollections;

/// <summary>How a <see cref="SmartRule"/> (configuration, in the shell) picks its titles.</summary>
public static class SmartRuleMatching
{
    extension(SmartRule rule)
    {
        /// <summary>Gets a value indicating whether the rule needs each title's video streams (4K or HDR).</summary>
        public bool NeedsStreams => rule.Only4K || rule.OnlyHdr;

        /// <summary>Whether [item] belongs in the collection, at [now].</summary>
        public bool Matches(ItemFacts item, DateTimeOffset now)
        {
            static bool Any(string[] wanted, IEnumerable<string> have) =>
                wanted.Length == 0 || have.Any(h => wanted.Any(w => string.Equals(w.Trim(), h, StringComparison.OrdinalIgnoreCase)));

            return (rule.Types.Length == 0 || rule.Types.Contains(item.Type, StringComparer.OrdinalIgnoreCase))
                && (rule.LibraryIds.Length == 0 || (item.LibraryId is { } lib && rule.LibraryIds.Any(l => Same(l, lib))))
                && Any(rule.Genres, item.Genres)
                && Any(rule.Tags, item.Tags)
                && Any(rule.Studios, item.Studios)
                && Any(rule.AgeRatings, item.AgeRating is null ? Array.Empty<string>() : new[] { item.AgeRating })
                && (rule.YearFrom is null || item.Year >= rule.YearFrom)
                && (rule.YearTo is null || (item.Year is { } y && y <= rule.YearTo))
                && (rule.MinRating is null || item.Rating >= rule.MinRating)
                && (rule.MaxMinutes is null || (item.Minutes is { } m && m <= rule.MaxMinutes))
                && (rule.AddedWithinDays is null || now - item.Added <= TimeSpan.FromDays(rule.AddedWithinDays.Value))
                && (!rule.Only4K || item.Height >= 2000 || item.Width >= 3800)
                && (!rule.OnlyHdr || item.Hdr);
        }

        /// <summary>The titles that belong, newest added first, at most [Limit].</summary>
        public IReadOnlyList<Guid> Select(IEnumerable<ItemFacts> items, DateTimeOffset now)
        {
            var chosen = items.Where(i => rule.Matches(i, now)).OrderByDescending(i => i.Added);
            return (rule.Limit > 0 ? chosen.Take(rule.Limit) : chosen).Select(i => i.Id).ToList();
        }
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
