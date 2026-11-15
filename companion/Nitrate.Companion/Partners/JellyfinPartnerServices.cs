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
    public ShowProgress Progress(Guid userId, Guid seriesId)
    {
        var user = _users.GetUserById(userId);
        if (user is null)
        {
            return new ShowProgress(Array.Empty<Guid>(), null, null);
        }

        var episodes = _library.GetItemList(new InternalItemsQuery
        {
            AncestorIds = new[] { seriesId },
            IncludeItemTypes = new[] { BaseItemKind.Episode },
            Recursive = true,
            IsVirtualItem = false,
            OrderBy = new[] { (ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending) },
        }).OfType<Episode>().Where(e => e.ParentIndexNumber != 0).ToList();
        var watched = episodes.Where(e => _userData.GetUserData(user, e)?.Played == true).ToList();
        var last = watched.LastOrDefault();
        var next = last is null ? episodes.FirstOrDefault() : episodes.SkipWhile(e => e.Id != last.Id).Skip(1).FirstOrDefault(e => !watched.Contains(e));
        return new ShowProgress(
            watched.Select(e => e.Id).ToList(),
            last is null ? null : $"S{last.ParentIndexNumber ?? 0} E{last.IndexNumber ?? 0}",
            next?.Id);
    }

    /// <inheritdoc />
    public Guid? SeriesOf(Guid itemId) => _library.GetItemById(itemId) is Episode e ? e.SeriesId : null;

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
