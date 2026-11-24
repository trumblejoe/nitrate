using System.Text.Json;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Nitrate.Companion.Storage;

namespace Nitrate.Companion.Insights;

/// <summary>
/// How long chats are kept, as the admin set it on the dashboard page (through the logic's own
/// settings, <c>/Nitrate/Settings</c>, so the shell's configuration stays as it is).
/// </summary>
/// <param name="DeleteAfterDays">Chats are deleted this many days after they were written (0: only after <see cref="PeopleRecords.ChatDays"/>, as always).</param>
/// <param name="KeepFrom">Chats with a message from any of these people are never deleted by the time rules.</param>
public record ChatKeepingSettings(int DeleteAfterDays = 0, IReadOnlyList<Guid>? KeepFrom = null)
{
    /// <summary>The people whose chats are kept (never null).</summary>
    public IReadOnlyList<Guid> Kept => KeepFrom ?? Array.Empty<Guid>();
}

/// <summary>
/// The time rules for chats (timeline notes and the chat histories over them): a message is deleted
/// once it is older than the admin's "Delete chats after" (never longer than
/// <see cref="PeopleRecords.ChatDays"/>, the limit there always was), unless its chat is kept: a
/// chat someone in its audience made a favourite, or one with a message from someone the admin
/// keeps chats from; a kept chat keeps the chats it builds on too. Favourites of chats that are
/// gone are forgotten. Run once a day by <see cref="ChatPruner"/>.
/// </summary>
public class ChatKeeping
{
    private const string PairKind = "settings";
    private const string PairKey = "chats";
    private readonly CompanionDb _db;
    private readonly TimelineNoteBook _notes;
    private readonly NoteChatBook _chats;

    /// <summary>Initializes a new instance of the <see cref="ChatKeeping"/> class.</summary>
    public ChatKeeping(CompanionDb db, TimelineNoteBook notes, NoteChatBook chats)
    {
        _db = db;
        _notes = notes;
        _chats = chats;
    }

    /// <summary>Gets the admin's settings (the defaults when never set: nothing changes).</summary>
    public ChatKeepingSettings Settings =>
        _db.Pair(PairKind, PairKey) is { } json ? DbDiaryStorage.Read<ChatKeepingSettings>(json) ?? new ChatKeepingSettings() : new ChatKeepingSettings();

    /// <summary>Keeps the admin's settings (days below 0 count as 0).</summary>
    public void Save(ChatKeepingSettings settings) =>
        _db.PutPair(PairKind, PairKey, JsonSerializer.Serialize(settings with { DeleteAfterDays = Math.Max(0, settings.DeleteAfterDays), KeepFrom = settings.Kept.Distinct().ToList() }));

    /// <summary>How many days a message is kept now.</summary>
    public int Days(ChatKeepingSettings settings) =>
        settings.DeleteAfterDays > 0 ? Math.Min(settings.DeleteAfterDays, PeopleRecords.ChatDays) : PeopleRecords.ChatDays;

    /// <summary>Deletes what the time rules say is due at <paramref name="now"/>; the number of messages deleted.</summary>
    public int Prune(DateTimeOffset now)
    {
        var settings = Settings;
        var before = now.AddDays(-Days(settings)).ToUnixTimeMilliseconds();
        var kept = settings.Kept.ToHashSet();
        var titles = _db.Rows("SELECT DISTINCT owner, kind FROM records WHERE (kind = $n OR kind = $c) AND at < $at", ("$n", TimelineNoteBook.Kind), ("$c", NoteChatBook.Kind), ("$at", before))
            .Select(r => r.Key).Distinct().ToList();
        var deleted = 0;
        foreach (var owner in titles)
        {
            if (Guid.TryParseExact(owner, "N", out var itemId))
            {
                deleted += PruneTitle(itemId, before, kept);
            }
        }

        return deleted;
    }

    /// <summary>The chats on a title the time rules never delete: favourites (of someone in their audience), chats with a kept person's message, and what they build on.</summary>
    public HashSet<Guid> Kept(Guid itemId, IReadOnlyCollection<Guid> keptPeople)
    {
        var notes = _notes.All(itemId);
        var favourites = _chats.Favourites(itemId);
        var keep = new HashSet<Guid>();
        foreach (var chat in notes.GroupBy(NoteChatBook.ChatOf))
        {
            var audience = chat.SelectMany(n => (n.Audience ?? n.Shares.SelectMany(s => s.Members).ToList()).Append(n.UserId)).ToHashSet();
            if (chat.Any(n => keptPeople.Contains(n.UserId)) || favourites.Any(f => f.ChatId == chat.Key && audience.Contains(f.UserId)))
            {
                keep.UnionWith(_chats.Chain(itemId, chat.Key));
            }
        }

        return keep;
    }

    private int PruneTitle(Guid itemId, long before, IReadOnlyCollection<Guid> keptPeople)
    {
        var owner = itemId.ToString("N");
        var keep = Kept(itemId, keptPeople);
        var deleted = 0;
        var left = new HashSet<Guid>();
        foreach (var note in _notes.All(itemId))
        {
            var chat = NoteChatBook.ChatOf(note);
            if (note.At < before && !keep.Contains(chat))
            {
                _db.DeleteRecord(TimelineNoteBook.Kind, owner, note.Id.ToString("N"));
                deleted++;
            }
            else
            {
                left.Add(chat);
            }
        }

        foreach (var chat in _chats.Stored(itemId))
        {
            if (chat.Created < before && !keep.Contains(chat.Id) && !left.Contains(chat.Id))
            {
                _db.DeleteRecord(NoteChatBook.Kind, owner, chat.Id.ToString("N"));
            }
        }

        foreach (var favourite in _chats.Favourites(itemId).Where(f => !left.Contains(f.ChatId)))
        {
            _db.DeleteRecord(NoteChatBook.FavouriteKind, owner, favourite.UserId.ToString("N") + "|" + favourite.ChatId.ToString("N"));
        }

        return deleted;
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
