using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;

namespace Nitrate.Companion.Channels;

/// <summary>The Jellyfin library, as channels read it (from the server's database; files are never touched).</summary>
public class JellyfinChannelLibrary : IChannelLibrary
{
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="JellyfinChannelLibrary"/> class.</summary>
    public JellyfinChannelLibrary(ILibraryManager library, IUserManager users)
    {
        _library = library;
        _users = users;
    }

    /// <inheritdoc />
    public IReadOnlyList<PlayItem> Expand(ChannelSource source)
    {
        var item = _library.GetItemById(source.Id);
        if (item is null)
        {
            return Array.Empty<PlayItem>();
        }

        return source.Kind switch
        {
            SourceKind.Library => LibraryItems(item),
            _ => Playable(item),
        };
    }

    /// <inheritdoc />
    public bool CanSee(Guid itemId, Guid userId)
    {
        var user = _users.GetUserById(userId);
        var item = _library.GetItemById(itemId);
        return user is not null && item is not null && item.IsVisibleStandalone(user);
    }

    /// <inheritdoc />
    public ProgramLabel? Describe(Guid itemId)
    {
        var item = _library.GetItemById(itemId);
        return item switch
        {
            null => null,
            Episode e => new ProgramLabel(
                e.SeriesName ?? e.Name,
                $"S{e.ParentIndexNumber ?? 0} E{e.IndexNumber ?? 0} · {e.Name}",
                "Episode"),
            Movie m => new ProgramLabel(m.Name, m.ProductionYear?.ToString(System.Globalization.CultureInfo.InvariantCulture), "Movie"),
            _ => new ProgramLabel(item.Name, null, item.GetBaseItemKind().ToString()),
        };
    }

    private static PlayItem? Play(BaseItem i) =>
        i.RunTimeTicks is > 0 && !i.IsVirtualItem ? new PlayItem(i.Id, i.RunTimeTicks.Value / TimeSpan.TicksPerMillisecond) : null;

    private IReadOnlyList<PlayItem> Playable(BaseItem item) => item switch
    {
        Series s => Episodes(s.Id),
        Season season => Episodes(season.Id),
        Folder folder when item is BoxSet or MediaBrowser.Controller.Playlists.Playlist =>
            folder.GetLinkedChildren().SelectMany(child => child is Series or Season ? Playable(child) : (Play(child) is { } p ? new[] { p } : Array.Empty<PlayItem>())).ToList(),
        _ => Play(item) is { } p ? new[] { p } : Array.Empty<PlayItem>(),
    };

    private IReadOnlyList<PlayItem> Episodes(Guid ancestor) => _library.GetItemList(new InternalItemsQuery
    {
        AncestorIds = new[] { ancestor },
        IncludeItemTypes = new[] { BaseItemKind.Episode },
        Recursive = true,
        IsVirtualItem = false,
        OrderBy = new[] { (ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending) },
    })
        .OfType<Episode>()
        .Where(e => e.ParentIndexNumber != 0)
        .Select(Play).OfType<PlayItem>().ToList();

    private IReadOnlyList<PlayItem> LibraryItems(BaseItem library)
    {
        var movies = _library.GetItemList(new InternalItemsQuery
        {
            AncestorIds = new[] { library.Id },
            IncludeItemTypes = new[] { BaseItemKind.Movie },
            Recursive = true,
            IsVirtualItem = false,
            OrderBy = new[] { (ItemSortBy.SortName, SortOrder.Ascending) },
        }).Select(Play).OfType<PlayItem>().ToList();
        if (movies.Count > 0)
        {
            return movies;
        }

        return _library.GetItemList(new InternalItemsQuery
        {
            AncestorIds = new[] { library.Id },
            IncludeItemTypes = new[] { BaseItemKind.Series },
            Recursive = true,
            OrderBy = new[] { (ItemSortBy.SortName, SortOrder.Ascending) },
        }).SelectMany(s => Episodes(s.Id)).ToList();
    }
}
