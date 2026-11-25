using System.Collections.Concurrent;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Playlists;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Changes;

/// <summary>What changed, as counters the apps compare with the ones they saw last.</summary>
/// <param name="Epoch">This run of the plugin's logic: a different one means anything may have changed.</param>
/// <param name="Library">Titles added, changed or removed anywhere (playlists and libraries aside).</param>
/// <param name="Views">Libraries themselves added, renamed or removed.</param>
/// <param name="Playlists">Playlists made, changed (their entries) or deleted.</param>
/// <param name="Watch">The caller's watch state (played, progress, favourites) changed, from any app.</param>
/// <param name="Because">When the caller's "Because you watched" was last made (ticks), 0 if never.</param>
public record ChangesOut(string Epoch, long Library, long Views, long Playlists, long Watch, long Because);

/// <summary>
/// Counters bumped as the server reports changes, so an app can ask "anything since?" in one tiny
/// request and keep the rows it has when nothing it shows has changed. Kept in memory: a server
/// restart or a new logic version starts a new epoch, and the apps then load everything once.
/// </summary>
public class ChangeCounters
{
    private readonly ConcurrentDictionary<Guid, long> _watch = new();
    private long _library;
    private long _views;
    private long _playlists;

    /// <summary>Gets this run's epoch.</summary>
    public string Epoch { get; } = Guid.NewGuid().ToString("N");

    /// <summary>What an item's change counts as.</summary>
    public enum Kind
    {
        /// <summary>A title or anything else in a library.</summary>
        Title,

        /// <summary>A library itself (a collection folder).</summary>
        Library,

        /// <summary>A playlist.</summary>
        Playlist,
    }

    /// <summary>An item was added, changed or removed.</summary>
    public void ItemChanged(Kind kind)
    {
        switch (kind)
        {
            case Kind.Playlist:
                Interlocked.Increment(ref _playlists);
                break;
            case Kind.Library:
                Interlocked.Increment(ref _views);
                Interlocked.Increment(ref _library);
                break;
            default:
                Interlocked.Increment(ref _library);
                break;
        }
    }

    /// <summary>A person's watch state changed.</summary>
    public void WatchChanged(Guid userId) => _watch.AddOrUpdate(userId, 1, (_, n) => n + 1);

    /// <summary>The counters as [userId] sees them, with when their "Because you watched" was made.</summary>
    public ChangesOut For(Guid userId, DateTimeOffset? becauseBuilt) => new(
        Epoch,
        Interlocked.Read(ref _library),
        Interlocked.Read(ref _views),
        Interlocked.Read(ref _playlists),
        _watch.TryGetValue(userId, out var n) ? n : 0,
        becauseBuilt?.UtcTicks ?? 0);
}

/// <summary>Feeds <see cref="ChangeCounters"/> from the server's own change events.</summary>
public class ChangeStamps : IHostedService
{
    private readonly ILibraryManager _library;
    private readonly IUserDataManager _userData;

    /// <summary>Initializes a new instance of the <see cref="ChangeStamps"/> class.</summary>
    public ChangeStamps(ILibraryManager library, IUserDataManager userData, ChangeCounters counters)
    {
        _library = library;
        _userData = userData;
        Counters = counters;
    }

    /// <summary>Gets the counters.</summary>
    public ChangeCounters Counters { get; }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded += OnItem;
        _library.ItemUpdated += OnItem;
        _library.ItemRemoved += OnItem;
        _userData.UserDataSaved += OnUserData;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded -= OnItem;
        _library.ItemUpdated -= OnItem;
        _library.ItemRemoved -= OnItem;
        _userData.UserDataSaved -= OnUserData;
        return Task.CompletedTask;
    }

    private void OnItem(object? sender, ItemChangeEventArgs e) => Counters.ItemChanged(e.Item switch
    {
        Playlist => ChangeCounters.Kind.Playlist,
        CollectionFolder or UserRootFolder or AggregateFolder => ChangeCounters.Kind.Library,
        _ => ChangeCounters.Kind.Title,
    });

    private void OnUserData(object? sender, UserDataSaveEventArgs e) => Counters.WatchChanged(e.UserId);
}
