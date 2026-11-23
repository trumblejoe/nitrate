using System.Text.Json;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Collections;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.SmartCollections;

/// <summary>
/// Which Jellyfin collection each rule keeps, in the plugin's database (the collections
/// themselves are Jellyfin's, the rules are the plugin's settings). The older
/// smart-collections.json is moved in once.
/// </summary>
public class SmartCollectionStore
{
    private const string Kind = "smart-collections";
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly object _lock = new();
    private readonly Dictionary<string, Guid> _collections;

    /// <summary>Initializes a new instance of the <see cref="SmartCollectionStore"/> class ([db] null: in memory; [legacyJson]: the older file, moved in once).</summary>
    public SmartCollectionStore(Nitrate.Companion.Storage.CompanionDb? db, string? legacyJson = null)
    {
        _db = db;
        if (db != null && legacyJson != null && File.Exists(legacyJson))
        {
            try
            {
                foreach (var (rule, id) in JsonSerializer.Deserialize<Dictionary<string, Guid>>(File.ReadAllText(legacyJson)) ?? new())
                {
                    if (db.Pair(Kind, rule) == null)
                    {
                        db.PutPair(Kind, rule, id.ToString("N"));
                    }
                }
            }
            catch (JsonException)
            {
                // Unreadable: the collections are found again by name.
            }

            File.Delete(legacyJson);
        }

        _collections = (db?.Pairs(Kind) ?? new())
            .Where(kv => Guid.TryParse(kv.Value, out _))
            .ToDictionary(kv => kv.Key, kv => Guid.Parse(kv.Value));
    }

    /// <summary>The collection kept for [ruleId], if any.</summary>
    public Guid? Get(string ruleId)
    {
        lock (_lock)
        {
            return _collections.TryGetValue(ruleId, out var id) ? id : null;
        }
    }

    /// <summary>Remembers [collectionId] for [ruleId].</summary>
    public void Set(string ruleId, Guid collectionId)
    {
        lock (_lock)
        {
            _collections[ruleId] = collectionId;
            _db?.PutPair(Kind, ruleId, collectionId.ToString("N"));
        }
    }

    /// <summary>Forgets [ruleId] (the rule was removed).</summary>
    public void Remove(string ruleId)
    {
        lock (_lock)
        {
            if (_collections.Remove(ruleId))
            {
                _db?.DeletePair(Kind, ruleId);
            }
        }
    }

    /// <summary>Every rule id with a collection.</summary>
    public IReadOnlyList<string> Rules()
    {
        lock (_lock)
        {
            return _collections.Keys.ToList();
        }
    }
}

/// <summary>
/// "Update smart collections" (nightly, and ten minutes after the library stops changing): keeps a
/// real Jellyfin collection per rule (the plugin's settings), adding titles that now match and
/// removing ones that no longer do. A collection deleted by hand is made again; a rule removed
/// leaves its collection as it is (an ordinary collection from then on).
/// </summary>
public class SmartCollectionJob : IScheduledTask
{
    private static readonly SemaphoreSlim Running = new(1, 1);
    private readonly SmartCollectionStore _store;
    private readonly ILibraryManager _library;
    private readonly ICollectionManager _collections;
    private readonly IMediaSourceManager _sources;
    private readonly ILogger<SmartCollectionJob> _log;

    /// <summary>Initializes a new instance of the <see cref="SmartCollectionJob"/> class.</summary>
    public SmartCollectionJob(SmartCollectionStore store, ILibraryManager library, ICollectionManager collections, IMediaSourceManager sources, ILogger<SmartCollectionJob> log)
    {
        _store = store;
        _library = library;
        _collections = collections;
        _sources = sources;
        _log = log;
    }

    /// <inheritdoc />
    public string Name => "Update smart collections";

    /// <inheritdoc />
    public string Key => "NitrateUpdateSmartCollections";

    /// <inheritdoc />
    public string Description => "Keeps the Nitrate Companion's rule-based collections up to date.";

    /// <inheritdoc />
    public string Category => "Nitrate Companion";

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => new[]
    {
        new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = TimeSpan.FromHours(5).Ticks },
    };

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        await Running.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var rules = (Plugin.Instance?.Configuration.SmartCollections ?? Array.Empty<SmartRule>()).Where(r => r.Enabled && !string.IsNullOrWhiteSpace(r.Name)).ToList();
            foreach (var gone in _store.Rules().Where(id => rules.All(r => r.Id != id)))
            {
                _store.Remove(gone);
            }

            if (rules.Count == 0)
            {
                return;
            }

            var streams = rules.Any(r => r.NeedsStreams);
            var items = _library.GetItemList(new InternalItemsQuery
            {
                IncludeItemTypes = new[] { BaseItemKind.Movie, BaseItemKind.Series },
                Recursive = true,
                IsVirtualItem = false,
            });
            var facts = items.Select(i => Facts(i, streams)).ToList();
            var now = DateTimeOffset.UtcNow;
            var done = 0;
            foreach (var rule in rules)
            {
                cancellationToken.ThrowIfCancellationRequested();
                progress.Report(100.0 * done++ / rules.Count);
                try
                {
                    await UpdateAsync(rule, rule.Select(facts, now)).ConfigureAwait(false);
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    _log.LogWarning("Smart collection {Name}: {Message}", rule.Name, e.Message);
                }
            }

            progress.Report(100);
        }
        finally
        {
            Running.Release();
        }
    }

    private async Task UpdateAsync(SmartRule rule, IReadOnlyList<Guid> wanted)
    {
        var existing = _store.Get(rule.Id) is { } id ? _library.GetItemById(id) as Folder : null;
        if (existing is null)
        {
            if (wanted.Count == 0)
            {
                return;
            }

            var made = await _collections.CreateCollectionAsync(new CollectionCreationOptions
            {
                Name = rule.Name,
                ItemIdList = wanted.Select(g => g.ToString("N", System.Globalization.CultureInfo.InvariantCulture)).ToArray(),
            }).ConfigureAwait(false);
            _store.Set(rule.Id, made.Id);
            return;
        }

        var current = existing.GetLinkedChildren().Select(c => c.Id).ToHashSet();
        var add = wanted.Where(w => !current.Contains(w)).ToList();
        var remove = current.Where(c => !wanted.Contains(c)).ToList();
        if (add.Count > 0)
        {
            await _collections.AddToCollectionAsync(existing.Id, add).ConfigureAwait(false);
        }

        if (remove.Count > 0)
        {
            await _collections.RemoveFromCollectionAsync(existing.Id, remove).ConfigureAwait(false);
        }

        if (existing.Name != rule.Name)
        {
            existing.Name = rule.Name;
            await existing.UpdateToRepositoryAsync(ItemUpdateType.MetadataEdit, CancellationToken.None).ConfigureAwait(false);
        }
    }

    private ItemFacts Facts(BaseItem item, bool streams)
    {
        var library = _library.GetCollectionFolders(item).FirstOrDefault()?.Id;
        int width = 0, height = 0;
        var hdr = false;
        if (streams)
        {
            // A show: its newest episode's file stands for it.
            var source = item is MediaBrowser.Controller.Entities.TV.Series
                ? _library.GetItemList(new InternalItemsQuery { AncestorIds = new[] { item.Id }, IncludeItemTypes = new[] { BaseItemKind.Episode }, Recursive = true, Limit = 1, OrderBy = new[] { (ItemSortBy.DateCreated, Jellyfin.Database.Implementations.Enums.SortOrder.Descending) } }).FirstOrDefault()
                : item;
            var video = source is null ? null : _sources.GetMediaStreams(source.Id).FirstOrDefault(s => s.Type == MediaStreamType.Video);
            width = video?.Width ?? 0;
            height = video?.Height ?? 0;
            hdr = video is not null && video.VideoRangeType is not (VideoRangeType.Unknown or VideoRangeType.SDR);
        }

        return new ItemFacts(
            item.Id,
            item is MediaBrowser.Controller.Entities.TV.Series ? "Series" : "Movie",
            library,
            item.Genres,
            item.Tags,
            item.Studios,
            item.OfficialRating,
            item.ProductionYear,
            item.CommunityRating,
            item.RunTimeTicks is { } t ? (int)(t / TimeSpan.TicksPerMinute) : null,
            new DateTimeOffset(item.DateCreated, TimeSpan.Zero),
            width,
            height,
            hdr);
    }
}

/// <summary>Runs the update ten minutes after the library stops changing (titles added or removed).</summary>
public sealed class SmartCollectionWatcher : IHostedService, IDisposable
{
    private static readonly TimeSpan Quiet = TimeSpan.FromMinutes(10);
    private readonly ILibraryManager _library;
    private readonly ITaskManager _tasks;
    private Timer? _timer;

    /// <summary>Initializes a new instance of the <see cref="SmartCollectionWatcher"/> class.</summary>
    public SmartCollectionWatcher(ILibraryManager library, ITaskManager tasks)
    {
        _library = library;
        _tasks = tasks;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded += Changed;
        _library.ItemRemoved += Changed;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _library.ItemAdded -= Changed;
        _library.ItemRemoved -= Changed;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public void Dispose() => _timer?.Dispose();

    private void Changed(object? sender, ItemChangeEventArgs e)
    {
        if (e.Item is not (MediaBrowser.Controller.Entities.Movies.Movie or MediaBrowser.Controller.Entities.TV.Series or MediaBrowser.Controller.Entities.TV.Episode))
        {
            return;
        }

        if ((Plugin.Instance?.Configuration.SmartCollections?.Length ?? 0) == 0)
        {
            return;
        }

        // Each change pushes the run back: it happens once things are quiet.
        _timer ??= new Timer(_ => _tasks.QueueScheduledTask<SmartCollectionJob>());
        _timer.Change(Quiet, Timeout.InfiniteTimeSpan);
    }
}
