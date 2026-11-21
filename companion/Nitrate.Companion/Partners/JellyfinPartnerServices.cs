using Jellyfin.Data;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;

namespace Nitrate.Companion.Partners;

/// <summary>Jellyfin's accounts for partners; admin-private accounts come from the plugin's settings.</summary>
public class JellyfinPartnerDirectory : IPartnerDirectory
{
    private readonly IUserManager _users;

    /// <summary>Initializes a new instance of the <see cref="JellyfinPartnerDirectory"/> class.</summary>
    public JellyfinPartnerDirectory(IUserManager users) => _users = users;

    /// <inheritdoc />
    public IReadOnlyList<(Guid Id, string Name)> Users() =>
        _users.GetUsers().Where(u => !u.HasPermission(PermissionKind.IsDisabled)).Select(u => (u.Id, u.Username)).ToList();

    /// <inheritdoc />
    public (Guid Id, string Name)? Find(string name)
    {
        var u = _users.GetUserByName(name.Trim())
            ?? _users.GetUsers().FirstOrDefault(x => string.Equals(x.Username, name.Trim(), StringComparison.OrdinalIgnoreCase));
        return u is null || u.HasPermission(PermissionKind.IsDisabled) ? null : (u.Id, u.Username);
    }

    /// <inheritdoc />
    public string? NameOf(Guid id)
    {
        var u = _users.GetUserById(id);
        return u is null || u.HasPermission(PermissionKind.IsDisabled) ? null : u.Username;
    }

    /// <inheritdoc />
    public bool AdminPrivate(Guid id) =>
        (Plugin.Instance?.Configuration.PrivateUserIds ?? Array.Empty<string>()).Any(s => Guid.TryParse(s, out var g) && g == id);
}

/// <summary>Watched state from Jellyfin's database (never the media files).</summary>
public class JellyfinWatchState : IWatchState
{
    private readonly ILibraryManager _library;
    private readonly IUserManager _users;
    private readonly IUserDataManager _userData;

    /// <summary>Initializes a new instance of the <see cref="JellyfinWatchState"/> class.</summary>
    public JellyfinWatchState(ILibraryManager library, IUserManager users, IUserDataManager userData)
    {
        _library = library;
        _users = users;
        _userData = userData;
    }

    /// <inheritdoc />
    public ShowProgress Progress(Guid userId, Guid titleId)
    {
        var user = _users.GetUserById(userId);
        if (user is null)
        {
            return new ShowProgress(Array.Empty<Guid>(), null, null);
        }

        var items = TitleItems(titleId, user);
        var watched = items.Where(e => _userData.GetUserData(user, e)?.Played == true).ToList();
        var last = watched.LastOrDefault();
        var next = last is null ? items.FirstOrDefault() : items.SkipWhile(e => e.Id != last.Id).Skip(1).FirstOrDefault(e => !watched.Contains(e));
        return new ShowProgress(watched.Select(e => e.Id).ToList(), last is null ? null : Label(last), next?.Id);
    }

    /// <inheritdoc />
    public bool Belongs(Guid titleId, Guid itemId, Guid userId)
    {
        var user = _users.GetUserById(userId);
        return user is not null && TitleItems(titleId, user).Any(i => i.Id == itemId);
    }

    /// <summary>"S2 E4" for an episode, else the title's name.</summary>
    private static string Label(BaseItem item) =>
        item is Episode e ? $"S{e.ParentIndexNumber ?? 0} E{e.IndexNumber ?? 0}" : item.Name;

    /// <summary>
    /// What a group's title holds, in watching order: a show's episodes (specials left out), a
    /// playlist's items in its own order, a collection's in release order (shows in either
    /// expanded to their episodes), or a film or video on its own.
    /// </summary>
    private IReadOnlyList<BaseItem> TitleItems(Guid titleId, Jellyfin.Database.Implementations.Entities.User user)
    {
        var title = _library.GetItemById(titleId);
        switch (title)
        {
            case null:
                return Array.Empty<BaseItem>();
            case Series:
                return Episodes(titleId);
            case MediaBrowser.Controller.Playlists.Playlist playlist:
                return playlist.GetChildren(user, true, new InternalItemsQuery(user)).SelectMany(Expand).ToList();
            case Folder folder:
                return folder.GetChildren(user, true, new InternalItemsQuery(user))
                    .OrderBy(i => i.PremiereDate ?? DateTime.MaxValue).ThenBy(i => i.ProductionYear ?? int.MaxValue)
                    .SelectMany(Expand).ToList();
            default:
                return new[] { title };
        }
    }

    private IEnumerable<BaseItem> Expand(BaseItem item) => item is Series ? Episodes(item.Id) : item.IsFolder ? Array.Empty<BaseItem>() : new[] { item };

    private List<BaseItem> Episodes(Guid seriesId) => _library.GetItemList(new InternalItemsQuery
    {
        AncestorIds = new[] { seriesId },
        IncludeItemTypes = new[] { BaseItemKind.Episode },
        Recursive = true,
        IsVirtualItem = false,
        OrderBy = new[] { (ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending) },
    }).OfType<Episode>().Where(e => e.ParentIndexNumber != 0).Cast<BaseItem>().ToList();

    /// <inheritdoc />
    public void MarkWatched(Guid userId, Guid itemId)
    {
        var user = _users.GetUserById(userId);
        var item = _library.GetItemById(itemId);
        if (user is not null && item is not null)
        {
            item.MarkPlayed(user, DateTime.UtcNow, true);
        }
    }
}
