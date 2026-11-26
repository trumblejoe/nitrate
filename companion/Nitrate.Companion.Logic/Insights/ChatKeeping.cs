using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>
/// How long chats are kept, as the admin set it on the dashboard page (through the logic's own
/// settings, <c>/Nitrate/Settings</c>, so the shell's configuration stays as it is).
/// </summary>
/// <param name="DeleteAfterDays">A thread is deleted this many days after its newest message (0: never; never set: <see cref="ChatKeeping.DefaultDays"/>).</param>
/// <param name="KeepFrom">Threads with a message from any of these people are never deleted (remembering someone).</param>
public record ChatKeepingSettings(int DeleteAfterDays = ChatKeeping.DefaultDays, IReadOnlyList<Guid>? KeepFrom = null)
{
    /// <summary>The people whose threads are kept (never null).</summary>
    public IReadOnlyList<Guid> Kept => KeepFrom ?? Array.Empty<Guid>();
}

/// <summary>
/// The time rules for chats: a group's thread is deleted, with its messages, once its newest message
/// is older than the admin's "Delete chats after" (ten years unless set; 0: never), so a conversation
/// still going is never cut short. A thread is kept while someone in the audience of its messages
/// has it as a favourite, or when it holds a message from someone the admin keeps chats from.
/// Messages that went to no group (a live session's guests only) go by their own age. Run once a
/// day by <see cref="ChatPruner"/>.
/// </summary>
public class ChatKeeping
{
    /// <summary>How long chats are kept when the admin never set it: ten years.</summary>
    public const int DefaultDays = 3650;

    private const string PairKind = "settings";
    private const string PairKey = "chats";
    private readonly CompanionDb _db;
    private readonly TimelineNoteBook _notes;
    private readonly ThreadBook _threads;

    /// <summary>Initializes a new instance of the <see cref="ChatKeeping"/> class.</summary>
    public ChatKeeping(CompanionDb db, TimelineNoteBook notes, ThreadBook threads)
    {
        _db = db;
        _notes = notes;
        _threads = threads;
    }

    /// <summary>Gets the admin's settings (ten years when never set).</summary>
    public ChatKeepingSettings Settings =>
        _db.Pair(PairKind, PairKey) is { } json ? DbDiaryStorage.Read<ChatKeepingSettings>(json) ?? new ChatKeepingSettings() : new ChatKeepingSettings();

    /// <summary>Keeps the admin's settings (days below 0 count as 0).</summary>
    public void Save(ChatKeepingSettings settings) =>
        _db.PutPair(PairKind, PairKey, JsonSerializer.Serialize(settings with { DeleteAfterDays = Math.Max(0, settings.DeleteAfterDays), KeepFrom = settings.Kept.Distinct().ToList() }));

    /// <summary>How many days a thread is kept after its newest message; null when chats are never deleted (0).</summary>
    public int? Days(ChatKeepingSettings settings) =>
        settings.DeleteAfterDays > 0 ? settings.DeleteAfterDays : null;

    /// <summary>Deletes what the time rules say is due at <paramref name="now"/>; the number of messages deleted.</summary>
    public int Prune(DateTimeOffset now)
    {
        var settings = Settings;
        if (Days(settings) is not { } days)
        {
            return 0;
        }

        var before = now.AddDays(-days).ToUnixTimeMilliseconds();
        var keptPeople = settings.Kept.ToHashSet();
        var notes = _db.Records(TimelineNoteBook.Kind, null).Select(r => DbDiaryStorage.Read<TimelineNote>(r.Value)).OfType<TimelineNote>().ToList();
        var byThread = notes.Where(n => n.ChatId is not null).GroupBy(n => n.ChatId!.Value).ToDictionary(g => g.Key, g => g.ToList());
        var deleted = 0;
        foreach (var thread in _threads.All())
        {
            var mine = byThread.GetValueOrDefault(thread.Id) ?? new List<TimelineNote>();
            var newest = mine.Count == 0 ? thread.Started : mine.Max(n => n.At);
            if (newest >= before || Kept(thread, mine, keptPeople))
            {
                continue;
            }

            foreach (var n in mine)
            {
                _db.DeleteRecord(TimelineNoteBook.Kind, n.ItemId.ToString("N"), n.Id.ToString("N"));
                deleted++;
            }

            _threads.Delete(thread);
        }

        // Messages in no thread (a live session's guests only): by their own age.
        foreach (var n in notes.Where(n => n.ChatId is null && n.At < before && !keptPeople.Contains(n.UserId)))
        {
            _db.DeleteRecord(TimelineNoteBook.Kind, n.ItemId.ToString("N"), n.Id.ToString("N"));
            deleted++;
        }

        return deleted;
    }

    /// <summary>Whether the time rules keep a thread: a favourite of someone in its messages' audience, or a message from a kept person.</summary>
    public bool Kept(ChatThread thread, IReadOnlyList<TimelineNote> notes, IReadOnlyCollection<Guid> keptPeople)
    {
        if (notes.Any(n => keptPeople.Contains(n.UserId)))
        {
            return true;
        }

        var audience = notes.SelectMany(n => (n.Audience ?? n.Shares.SelectMany(s => s.Members).ToList()).Append(n.UserId)).ToHashSet();
        return _threads.Favourites(thread.Id).Any(f => audience.Contains(f.UserId));
    }
}

/// <summary>Runs the chats' time rules ten minutes after start, then once a day.</summary>
public class ChatPruner : BackgroundService
{
    private readonly ChatKeeping _keeping;
    private readonly ILogger<ChatPruner> _log;

    /// <summary>Initializes a new instance of the <see cref="ChatPruner"/> class.</summary>
    public ChatPruner(ChatKeeping keeping, ILogger<ChatPruner> log)
    {
        _keeping = keeping;
        _log = log;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await Task.Delay(TimeSpan.FromMinutes(10), stoppingToken).ConfigureAwait(false);
            while (!stoppingToken.IsCancellationRequested)
            {
                try
                {
                    var deleted = _keeping.Prune(DateTimeOffset.UtcNow);
                    if (deleted > 0)
                    {
                        _log.LogInformation("Deleted {Count} chat messages past their time", deleted);
                    }
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    _log.LogWarning(e, "Couldn't delete old chats");
                }

                await Task.Delay(TimeSpan.FromDays(1), stoppingToken).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Stopping (or swapped out): the next logic carries on.
        }
    }
}
