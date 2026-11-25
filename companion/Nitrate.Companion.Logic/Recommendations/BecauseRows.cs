namespace Nitrate.Companion.Recommendations;

/// <summary>A film or episode the person played lately (the apps pick seeds from these).</summary>
/// <param name="Id">The film or episode.</param>
/// <param name="Kind">Jellyfin's kind ("Movie", "Episode").</param>
/// <param name="Name">Its name.</param>
/// <param name="SeriesId">An episode's show.</param>
/// <param name="SeriesName">An episode's show's name.</param>
public record BecauseRecent(Guid Id, string Kind, string Name, Guid? SeriesId, string? SeriesName);

/// <summary>The server's similar titles for one film or show, best first.</summary>
/// <param name="TitleId">The film or show.</param>
/// <param name="ItemIds">Titles like it, best first (watched ones included: the apps leave them out).</param>
public record BecauseSimilar(Guid TitleId, IReadOnlyList<Guid> ItemIds);

/// <summary>
/// Facts for a person's "Because you watched" rows, made overnight: what they played lately and the
/// server's similar titles for the films and shows those are of. No rule is applied here: which
/// seeds make rows, what's left out and how many show is the apps' (core <c>BecauseYouWatched</c>).
/// </summary>
/// <param name="Built">When they were made.</param>
/// <param name="Recent">Played lately, most recent first.</param>
/// <param name="Similar">Similar titles for the first few films and shows of [Recent].</param>
public record BecauseFacts(DateTimeOffset Built, IReadOnlyList<BecauseRecent> Recent, IReadOnlyList<BecauseSimilar> Similar);

/// <summary>How much is gathered (a fetching window, not a rule about what shows).</summary>
public static class BecauseGather
{
    /// <summary>Films and episodes played lately looked at.</summary>
    public const int Recent = 12;

    /// <summary>Films and shows whose similar titles are found (the apps try up to twice the rows they show).</summary>
    public const int Titles = 4;

    /// <summary>Similar titles kept for each.</summary>
    public const int Similar = 40;

    /// <summary>The films and shows [recent] is of, most recent first, at most [count].</summary>
    public static IReadOnlyList<Guid> TitlesOf(IEnumerable<BecauseRecent> recent, int count) =>
        recent.Select(r => r.SeriesId ?? r.Id).Distinct().Take(count).ToList();
}

/// <summary>
/// Each person's facts, in memory only: a few dozen title ids each, worked out again overnight and,
/// after the server starts, in the background (<see cref="BecauseWarmup"/>). Nothing on disk.
/// </summary>
public class BecauseStore
{
    private readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, BecauseFacts> _sets = new();

    /// <summary>The person's rows, or null when none were made since the server started.</summary>
    public BecauseFacts? Get(Guid user) => _sets.TryGetValue(user, out var set) ? set : null;

    /// <summary>Keeps the person's rows.</summary>
    public void Put(Guid user, BecauseFacts set) => _sets[user] = set;

    /// <summary>Everyone's rows, for the next logic.</summary>
    public IReadOnlyDictionary<Guid, BecauseFacts> ExportState() => new Dictionary<Guid, BecauseFacts>(_sets);

    /// <summary>Takes the rows the previous logic made.</summary>
    public void ImportState(IReadOnlyDictionary<Guid, BecauseFacts> sets)
    {
        foreach (var (user, set) in sets)
        {
            _sets[user] = set;
        }
    }
}
