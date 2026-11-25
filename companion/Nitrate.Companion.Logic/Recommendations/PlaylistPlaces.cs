namespace Nitrate.Companion.Recommendations;

/// <summary>
/// Home's "Continue your playlists", worked out inside the server: for each video playlist the person
/// is watching through, what carries on from the entry they played last. The same rules as the apps'
/// own (core <c>PlaylistPlaces</c>), so an app with or without the Companion shows the same row.
/// </summary>
public static class PlaylistPlaces
{
    /// <summary>A playlist counts as being watched through when something in it was played within this long.</summary>
    public static readonly TimeSpan Active = TimeSpan.FromDays(60);

    /// <summary>The entry played most recently, or null when nothing in it has been.</summary>
    public static int? LastPlayed(IReadOnlyList<PlaylistEntry> entries)
    {
        int? best = null;
        for (var i = 0; i < entries.Count; i++)
        {
            if (entries[i].LastPlayed is { } at && (best is null || at > entries[best.Value].LastPlayed))
            {
                best = i;
            }
        }

        return best;
    }

    /// <summary>What to carry on with from [place]: that one if not finished, else the next unwatched after it.</summary>
    public static Guid? ContinueFrom(IReadOnlyList<PlaylistEntry> entries, int place)
    {
        for (var i = Math.Max(place, 0); i < entries.Count; i++)
        {
            if (!entries[i].Played)
            {
                return entries[i].Id;
            }
        }

        return null;
    }

    /// <summary>
    /// The row: one title per playlist played within <see cref="Active"/> of [now], most recently played
    /// first, finished ones left out, each title once, at most [max].
    /// </summary>
    public static IReadOnlyList<Guid> Continue(IEnumerable<IReadOnlyList<PlaylistEntry>> playlists, DateTime now, int max)
    {
        var since = now - Active;
        return playlists
            .Select(entries => (Entries: entries, Place: LastPlayed(entries)))
            .Where(p => p.Place is { } at && p.Entries[at].LastPlayed >= since)
            .Select(p => (At: p.Entries[p.Place!.Value].LastPlayed!.Value, Next: ContinueFrom(p.Entries, p.Place!.Value)))
            .Where(p => p.Next is not null)
            .OrderByDescending(p => p.At)
            .Select(p => p.Next!.Value)
            .Distinct()
            .Take(Math.Max(max, 0))
            .ToList();
    }
}

/// <summary>One title in a playlist's watching order, with the person's watch state.</summary>
/// <param name="Id">The title.</param>
/// <param name="Played">Watched.</param>
/// <param name="LastPlayed">When it was last played (any app), if ever.</param>
public record PlaylistEntry(Guid Id, bool Played, DateTime? LastPlayed);
