using System.Text.Json;

namespace Nitrate.Companion.Scripts;

/// <summary>
/// Shared screenplays: who may write in each, its text and version, and its recent changes.
/// A change is taken only when it was made on the latest version (compare-and-set), so the
/// server just keeps order; apps rebase their own unsent changes onto everyone else's and send
/// again. Apps wait for others' changes by long polling, and say where their cursor is.
/// </summary>
public class ScriptHub
{
    /// <summary>Longest script, in characters.</summary>
    public const int MaxLength = 2_000_000;

    /// <summary>Most people invited to one script.</summary>
    public const int MaxMembers = 20;

    /// <summary>Changes kept per script for apps catching up.</summary>
    public const int KeptEdits = 2_000;

    /// <summary>How long someone counts as here after they last said where their cursor is.</summary>
    public static readonly TimeSpan PresenceLifetime = TimeSpan.FromSeconds(45);

    private readonly IScriptStorage _storage;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, Live> _scripts = new();
    private bool _loaded;

    /// <summary>Initializes a new instance of the <see cref="ScriptHub"/> class.</summary>
    public ScriptHub(IScriptStorage storage, TimeProvider time)
    {
        _storage = storage;
        _time = time;
    }

    /// <summary>Scripts <paramref name="userId"/> owns or was invited to, most recently changed first.</summary>
    public IReadOnlyList<StoredScript> List(Guid userId)
    {
        lock (_lock)
        {
            Load();
            return _scripts.Values.Select(l => l.Script).Where(s => s.CanWrite(userId)).OrderByDescending(s => s.UpdatedAt).ToList();
        }
    }

    /// <summary>Most story records one script may hold.</summary>
    public const int MaxRecords = 5_000;

    /// <summary>Longest story field, in characters.</summary>
    public const int MaxFieldLength = 50_000;

    /// <summary>Starts a shared script from <paramref name="text"/> (and its story data), owned by <paramref name="ownerId"/>.</summary>
    public StoredScript Create(Guid ownerId, string? text, IReadOnlyList<RecordChange>? records = null)
    {
        var body = text ?? string.Empty;
        if (body.Length > MaxLength)
        {
            throw new ScriptException(ScriptError.Invalid, "The script is too long to share.");
        }

        var now = _time.GetUtcNow();
        var script = new StoredScript { Id = Guid.NewGuid(), OwnerId = ownerId, Text = body, InitialText = body, CreatedAt = now, UpdatedAt = now };
        if (records is { Count: > 0 })
        {
            ApplyRecords(script.Records, records, ownerId, now);
            script.InitialRecords = script.Records.ToDictionary(r => r.Key, r => r.Value.Clone());
        }

        lock (_lock)
        {
            Load();
            _scripts[script.Id] = new Live(script);
            _storage.Save(script);
        }

        return script;
    }

    /// <summary>A script <paramref name="userId"/> may write in.</summary>
    public StoredScript Get(Guid id, Guid userId)
    {
        lock (_lock)
        {
            return Find(id, userId).Script;
        }
    }

    /// <summary>Sets who's invited (the owner only). The owner is never a member of their own script.</summary>
    public StoredScript SetMembers(Guid id, Guid ownerId, IEnumerable<Guid> members)
    {
        var list = members.Where(m => m != ownerId && m != Guid.Empty).Distinct().ToList();
        if (list.Count > MaxMembers)
        {
            throw new ScriptException(ScriptError.Invalid, $"A script can be shared with at most {MaxMembers} people.");
        }

        lock (_lock)
        {
            var live = Find(id, ownerId);
            if (live.Script.OwnerId != ownerId)
            {
                throw new ScriptException(ScriptError.NotOwner, "Only the script's owner can change who it's shared with.");
            }

            live.Script.Members = list;
            _storage.Save(live.Script);
            live.Wake();
            return live.Script;
        }
    }

    /// <summary>Stops sharing a script with yourself (members); owners delete instead.</summary>
    public void Leave(Guid id, Guid userId)
    {
        lock (_lock)
        {
            var live = Find(id, userId);
            if (live.Script.OwnerId == userId)
            {
                throw new ScriptException(ScriptError.Invalid, "The owner can't leave their own script; delete it, or keep it.");
            }

            live.Script.Members.Remove(userId);
            live.Presence.Remove(userId);
            _storage.Save(live.Script);
            live.Wake();
        }
    }

    /// <summary>Deletes a shared script for everyone (the owner only). Everyone keeps their own copy in the app.</summary>
    public void Delete(Guid id, Guid ownerId)
    {
        lock (_lock)
        {
            var live = Find(id, ownerId);
            if (live.Script.OwnerId != ownerId)
            {
                throw new ScriptException(ScriptError.NotOwner, "Only the script's owner can delete it.");
            }

            _scripts.Remove(id);
            _storage.Delete(id);
            _storage.DeleteLog(id);
            live.Wake();
        }
    }

    /// <summary>
    /// Applies a change: to the text (<paramref name="ops"/>), to the story data
    /// (<paramref name="records"/>), or both. A text change is taken only when no other text
    /// change came after <paramref name="baseVersion"/>; story data changes never wait (each
    /// field simply takes the latest value). A change already taken (same
    /// <paramref name="editId"/>) answers as before.
    /// </summary>
    public (EditOutcome Outcome, long Version) Edit(Guid id, Guid userId, string editId, long baseVersion, JsonElement? ops, IReadOnlyList<RecordChange>? records, int? cursor)
    {
        if (string.IsNullOrWhiteSpace(editId) || editId.Length > 64)
        {
            throw new ScriptException(ScriptError.Invalid, "A change needs an id.");
        }

        var hasText = ops is { ValueKind: JsonValueKind.Array };
        if (!hasText && records is not { Count: > 0 })
        {
            throw new ScriptException(ScriptError.Invalid, "The change changes nothing.");
        }

        lock (_lock)
        {
            var live = Find(id, userId);
            var script = live.Script;
            var earlier = script.Edits.FirstOrDefault(e => e.EditId == editId);
            if (earlier is not null)
            {
                return (EditOutcome.Accepted, earlier.Version);
            }

            if (baseVersion > script.Version || baseVersion < 0)
            {
                throw new ScriptException(ScriptError.Invalid, "No such version.");
            }

            if (hasText && baseVersion != script.Version)
            {
                // Stale only if someone else's text change came after it (or we can't tell).
                var oldestKept = script.Edits.Count > 0 ? script.Edits[0].Version : script.Version + 1;
                if (baseVersion + 1 < oldestKept || script.Edits.Any(e => e.IsText && e.Version > baseVersion))
                {
                    return (EditOutcome.Stale, script.Version);
                }
            }

            var text = script.Text;
            string? sceneId = null;
            if (hasText)
            {
                try
                {
                    text = TextOps.Apply(script.Text, ops!.Value);
                }
                catch (ArgumentException e)
                {
                    throw new ScriptException(ScriptError.Invalid, e.Message);
                }

                if (text.Length > MaxLength)
                {
                    throw new ScriptException(ScriptError.Invalid, "The script would be too long.");
                }

                sceneId = SceneAt(text, ops!.Value);
            }

            var now = _time.GetUtcNow();
            var newRecords = script.Records;
            if (records is { Count: > 0 })
            {
                newRecords = script.Records.ToDictionary(r => r.Key, r => r.Value);
                ApplyRecords(newRecords, records, userId, now);
            }

            script.Text = text;
            script.Records = newRecords;
            script.Version++;
            script.UpdatedAt = now;
            var edit = new StoredEdit(script.Version, editId, userId, hasText ? ops!.Value.Clone() : null, now, records?.ToList(), sceneId);
            script.Edits.Add(edit);
            if (script.Edits.Count > KeptEdits)
            {
                script.Edits.RemoveRange(0, script.Edits.Count - KeptEdits);
            }

            if (cursor is { } c)
            {
                live.Presence[userId] = new Presence(userId, c, script.Version, now);
            }

            _storage.Save(script);
            _storage.AppendLog(script.Id, edit);
            live.Wake();
            return (EditOutcome.Accepted, script.Version);
        }
    }

    /// <summary>Every change from <paramref name="fromVersion"/> on (up to <paramref name="limit"/>), from the full history.</summary>
    public IReadOnlyList<StoredEdit> Log(Guid id, Guid userId, long fromVersion, int limit)
    {
        lock (_lock)
        {
            Find(id, userId);
        }

        return _storage.ReadLog(id, fromVersion, Math.Clamp(limit, 1, 5_000));
    }

    /// <summary>The text and story data as they were at <paramref name="version"/> (replayed from the full history).</summary>
    public (string Text, Dictionary<string, StoredRecord> Records) At(Guid id, Guid userId, long version)
    {
        StoredScript script;
        lock (_lock)
        {
            script = Find(id, userId).Script;
            if (version < 0 || version > script.Version)
            {
                throw new ScriptException(ScriptError.Invalid, "No such version.");
            }
        }

        var text = script.InitialText;
        var records = script.InitialRecords.ToDictionary(r => r.Key, r => r.Value.Clone());
        long from = 1;
        while (from <= version)
        {
            var page = _storage.ReadLog(id, from, 2_000);
            if (page.Count == 0)
            {
                throw new ScriptException(ScriptError.Gone, "That version's history isn't kept.");
            }

            foreach (var e in page.TakeWhile(e => e.Version <= version))
            {
                if (e.IsText)
                {
                    text = TextOps.Apply(text, e.Ops!.Value);
                }

                if (e.Records is { Count: > 0 })
                {
                    ApplyRecords(records, e.Records, e.UserId, e.At);
                }
            }

            from = page[^1].Version + 1;
        }

        return (text, records);
    }

    /// <summary>Applies story data changes field by field, stamping who and when.</summary>
    public static void ApplyRecords(Dictionary<string, StoredRecord> records, IEnumerable<RecordChange> changes, Guid by, DateTimeOffset at)
    {
        foreach (var c in changes)
        {
            if (string.IsNullOrWhiteSpace(c.Id) || c.Id.Length > 100 || string.IsNullOrWhiteSpace(c.Type) || c.Type.Length > 40)
            {
                throw new ScriptException(ScriptError.Invalid, "A story change needs an id and a type.");
            }

            if (c.Set?.Values.Any(v => v?.Length > MaxFieldLength) == true || c.Set?.Count > 100)
            {
                throw new ScriptException(ScriptError.Invalid, "That story change is too big.");
            }

            if (!records.TryGetValue(c.Id, out var old))
            {
                if (c.Delete)
                {
                    continue;
                }

                if (records.Count >= MaxRecords)
                {
                    throw new ScriptException(ScriptError.Invalid, "This script has too many story items.");
                }

                old = new StoredRecord { Id = c.Id, Type = c.Type, CreatedBy = by, CreatedAt = at };
            }

            var record = old.Clone();
            if (c.Delete)
            {
                record.Deleted = true;
            }
            else
            {
                record.Deleted = false;
                foreach (var (k, v) in c.Set ?? new())
                {
                    if (v is null)
                    {
                        record.Fields.Remove(k);
                    }
                    else
                    {
                        record.Fields[k] = v;
                    }
                }
            }

            record.UpdatedBy = by;
            record.UpdatedAt = at;
            records[c.Id] = record;
        }
    }

    /// <summary>The scene a text change landed in: the marker on the heading at or before where it starts.</summary>
    public static string? SceneAt(string text, JsonElement ops)
    {
        var pos = 0;
        foreach (var part in ops.EnumerateArray())
        {
            if (part.ValueKind == JsonValueKind.Number && part.GetInt32() > 0)
            {
                pos += part.GetInt32();
            }
            else
            {
                break;
            }
        }

        pos = Math.Min(pos, text.Length);
        var lineEnd = text.IndexOf('\n', pos);
        if (lineEnd < 0)
        {
            lineEnd = text.Length;
        }

        if (lineEnd == 0)
        {
            return null;
        }

        var marker = text.LastIndexOf("[[scene:", lineEnd - 1, StringComparison.Ordinal);
        if (marker < 0)
        {
            return null;
        }

        var close = text.IndexOf("]]", marker, StringComparison.Ordinal);
        return close < 0 ? null : text[(marker + 8)..close];
    }

    /// <summary>Says where <paramref name="userId"/>'s cursor is, for the others.</summary>
    public void SetPresence(Guid id, Guid userId, int cursor, long version)
    {
        lock (_lock)
        {
            var live = Find(id, userId);
            live.Presence[userId] = new Presence(userId, cursor, version, _time.GetUtcNow());
            live.Wake();
        }
    }

    /// <summary>
    /// Changes after <paramref name="after"/>, waiting up to <paramref name="wait"/> when there are
    /// none. Anything else happening (a cursor moving, someone invited) also answers, once the
    /// page's stamp is past <paramref name="seen"/>. Throws <see cref="ScriptError.Gone"/> when
    /// the changes asked for are no longer kept.
    /// </summary>
    public async Task<EditsPage> WaitAsync(Guid id, Guid userId, long after, long? seen, TimeSpan wait, CancellationToken cancellationToken)
    {
        Task changed;
        lock (_lock)
        {
            var live = Find(id, userId);
            var page = PageOf(live, userId, after);
            if (page.Edits.Count > 0 || wait <= TimeSpan.Zero || (seen is { } stamp && live.Stamp > stamp))
            {
                return page;
            }

            changed = live.Changed.Task;
        }

        try
        {
            await changed.WaitAsync(wait, _time, cancellationToken).ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            // Nothing new in time.
        }

        lock (_lock)
        {
            return PageOf(Find(id, userId), userId, after);
        }
    }

    private EditsPage PageOf(Live live, Guid userId, long after)
    {
        var script = live.Script;
        if (after > script.Version || after < 0)
        {
            throw new ScriptException(ScriptError.Invalid, "No such version.");
        }

        var oldestKept = script.Edits.Count > 0 ? script.Edits[0].Version : script.Version + 1;
        if (after < script.Version && after + 1 < oldestKept)
        {
            throw new ScriptException(ScriptError.Gone, "Those changes are no longer kept; load the script again.");
        }

        var now = _time.GetUtcNow();
        var edits = script.Edits.Where(e => e.Version > after).ToList();
        var presence = live.Presence.Values.Where(p => p.UserId != userId && now - p.SeenAt < PresenceLifetime).ToList();
        return new EditsPage(edits, script.Version, presence, live.Stamp);
    }

    private Live Find(Guid id, Guid userId)
    {
        Load();
        if (!_scripts.TryGetValue(id, out var live) || !live.Script.CanWrite(userId))
        {
            throw new ScriptException(ScriptError.NotFound, "No such shared script.");
        }

        return live;
    }

    private void Load()
    {
        if (_loaded)
        {
            return;
        }

        foreach (var script in _storage.LoadAll())
        {
            _scripts[script.Id] = new Live(script);
        }

        _loaded = true;
    }

    private sealed class Live
    {
        public Live(StoredScript script) => Script = script;

        public StoredScript Script { get; }

        public Dictionary<Guid, Presence> Presence { get; } = new();

        public long Stamp { get; private set; }

        public TaskCompletionSource Changed { get; private set; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public void Wake()
        {
            Stamp++;
            var signal = Changed;
            Changed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            signal.TrySetResult();
        }
    }
}
