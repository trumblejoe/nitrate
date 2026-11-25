using Jellyfin.Database.Implementations.Entities;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.IO;
using MediaBrowser.Model.Providers;

namespace Nitrate.Companion.Metadata;

/// <summary>A refresh as Jellyfin's <c>POST /Items/{id}/Refresh</c> takes it.</summary>
public record RefreshRequest(MetadataRefreshMode MetadataRefreshMode, MetadataRefreshMode ImageRefreshMode, bool ReplaceAllMetadata, bool ReplaceAllImages, bool RegenerateTrickplay);

/// <summary>
/// What metadata editing needs from the server, done through its own services exactly as its
/// admin endpoints do (so savers, locks and providers behave the same). A seam for the tests.
/// </summary>
public interface IMetadataServer
{
    /// <summary>The item when [user] can see it under Jellyfin's own library access (any item for null), else null.</summary>
    BaseItem? Find(Guid id, User? user);

    /// <summary>The libraries (collection folders) the item is in.</summary>
    IReadOnlyList<Guid> LibrariesOf(BaseItem item);

    /// <summary>The item's cast and crew.</summary>
    IReadOnlyList<PersonInfo> People(BaseItem item);

    /// <summary>Saves edited details (people first, so the savers read them), as <c>POST /Items/{id}</c>.</summary>
    Task SaveDetailsAsync(BaseItem item, IReadOnlyList<PersonInfo>? people, bool lockChanged);

    /// <summary>The external-id fields for the item, as <c>/Items/{id}/ExternalIdInfos</c>.</summary>
    IEnumerable<ExternalIdInfo> ExternalIds(BaseItem item);

    /// <summary>Applies a search result (a full refresh from it), as <c>POST /Items/RemoteSearch/Apply/{id}</c>.</summary>
    Task ApplyAsync(BaseItem item, RemoteSearchResult result, bool replaceAllImages);

    /// <summary>Queues a refresh, as <c>POST /Items/{id}/Refresh</c>.</summary>
    void QueueRefresh(BaseItem item, RefreshRequest request);

    /// <summary>The server still has a refresh of this item queued or running.</summary>
    bool Refreshing(Guid itemId);

    /// <summary>Saves an uploaded image, as <c>POST /Items/{id}/Images/{type}</c>.</summary>
    Task SaveImageAsync(BaseItem item, Stream data, string mimeType, ImageType type);

    /// <summary>Downloads and saves a remote image, as <c>POST /Items/{id}/RemoteImages/Download</c>.</summary>
    Task DownloadImageAsync(BaseItem item, Uri url, ImageType type);

    /// <summary>Removes an image, as <c>DELETE /Items/{id}/Images/{type}/{index}</c>.</summary>
    Task DeleteImageAsync(BaseItem item, ImageType type, int index);

    /// <summary>Moves an image within its type, as <c>POST /Items/{id}/Images/{type}/{index}/Index</c>.</summary>
    Task MoveImageAsync(BaseItem item, ImageType type, int index, int newIndex);
}

/// <summary>The server's own services behind <see cref="IMetadataServer"/>.</summary>
public sealed class JellyfinMetadataServer : IMetadataServer
{
    private readonly ILibraryManager _library;
    private readonly IProviderManager _providers;
    private readonly IFileSystem _files;

    /// <summary>Initializes a new instance of the <see cref="JellyfinMetadataServer"/> class.</summary>
    public JellyfinMetadataServer(ILibraryManager library, IProviderManager providers, IFileSystem files)
    {
        _library = library;
        _providers = providers;
        _files = files;
    }

    /// <inheritdoc />
    public BaseItem? Find(Guid id, User? user)
    {
        var item = _library.GetItemById(id);
        return item is not null && (user is null || item.IsVisibleStandalone(user)) ? item : null;
    }

    /// <inheritdoc />
    public IReadOnlyList<Guid> LibrariesOf(BaseItem item) => _library.GetCollectionFolders(item).Select(f => f.Id).ToList();

    /// <inheritdoc />
    public IReadOnlyList<PersonInfo> People(BaseItem item) => _library.GetPeople(item);

    /// <inheritdoc />
    public async Task SaveDetailsAsync(BaseItem item, IReadOnlyList<PersonInfo>? people, bool lockChanged)
    {
        if (people is not null)
        {
            await _library.UpdatePeopleAsync(item, people, CancellationToken.None).ConfigureAwait(false);
        }

        item.OnMetadataChanged();
        // Through the library, which runs the metadata savers (NFO and the like) as for an admin's edit.
        await item.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit, CancellationToken.None).ConfigureAwait(false);
        if (lockChanged && item is Folder folder)
        {
            foreach (var child in folder.GetRecursiveChildren())
            {
                child.IsLocked = item.IsLocked;
                await child.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit, CancellationToken.None).ConfigureAwait(false);
            }
        }
    }

    /// <inheritdoc />
    public IEnumerable<ExternalIdInfo> ExternalIds(BaseItem item) => _providers.GetExternalIdInfos(item);

    /// <inheritdoc />
    public Task ApplyAsync(BaseItem item, RemoteSearchResult result, bool replaceAllImages)
    {
        item.ProviderIds = result.ProviderIds ?? new Dictionary<string, string>();
        return _providers.RefreshFullItem(
            item,
            new MetadataRefreshOptions(new DirectoryService(_files))
            {
                MetadataRefreshMode = MetadataRefreshMode.FullRefresh,
                ImageRefreshMode = MetadataRefreshMode.FullRefresh,
                ReplaceAllMetadata = true,
                ReplaceAllImages = replaceAllImages,
                SearchResult = result,
                RemoveOldMetadata = true,
            },
            CancellationToken.None);
    }

    /// <inheritdoc />
    public void QueueRefresh(BaseItem item, RefreshRequest r) => _providers.QueueRefresh(
        item.Id,
        new MetadataRefreshOptions(new DirectoryService(_files))
        {
            MetadataRefreshMode = r.MetadataRefreshMode,
            ImageRefreshMode = r.ImageRefreshMode,
            ReplaceAllImages = r.ReplaceAllImages,
            ReplaceAllMetadata = r.ReplaceAllMetadata,
            ForceSave = r.MetadataRefreshMode == MetadataRefreshMode.FullRefresh || r.ImageRefreshMode == MetadataRefreshMode.FullRefresh || r.ReplaceAllImages || r.ReplaceAllMetadata,
            IsAutomated = false,
            RegenerateTrickplay = r.RegenerateTrickplay,
        },
        RefreshPriority.High);

    /// <inheritdoc />
    public bool Refreshing(Guid itemId) => _providers.GetRefreshProgress(itemId) is not null || _providers.GetRefreshQueue().Contains(itemId);

    /// <inheritdoc />
    public async Task SaveImageAsync(BaseItem item, Stream data, string mimeType, ImageType type)
    {
        await _providers.SaveImage(item, data, mimeType, type, null, CancellationToken.None).ConfigureAwait(false);
        await item.UpdateToRepositoryAsync(ItemUpdateType.ImageUpdate, CancellationToken.None).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public async Task DownloadImageAsync(BaseItem item, Uri url, ImageType type)
    {
        await _providers.SaveImage(item, url.AbsoluteUri, type, null, CancellationToken.None).ConfigureAwait(false);
        await item.UpdateToRepositoryAsync(ItemUpdateType.ImageUpdate, CancellationToken.None).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public Task DeleteImageAsync(BaseItem item, ImageType type, int index) => item.DeleteImageAsync(type, index);

    /// <inheritdoc />
    public Task MoveImageAsync(BaseItem item, ImageType type, int index, int newIndex) => item.SwapImagesAsync(type, index, newIndex);
}
