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

    /// <summary>Starts a shared script from <paramref name="text"/>, owned by <paramref name="ownerId"/>.</summary>
    public StoredScript Create(Guid ownerId, string? text)
    {
        var body = text ?? string.Empty;
        if (body.Length > MaxLength)
        {
            throw new ScriptException(ScriptError.Invalid, "The script is too long to share.");
        }

        var now = _time.GetUtcNow();
        var script = new StoredScript { Id = Guid.NewGuid(), OwnerId = ownerId, Text = body, CreatedAt = now, UpdatedAt = now };
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
            live.Wake();
        }
    }

    /// <summary>
    /// Applies a change made on <paramref name="baseVersion"/>. Taken only when that is the
    /// latest version; a change already taken (same <paramref name="editId"/>) answers as before.
    /// </summary>
    public (EditOutcome Outcome, long Version) Edit(Guid id, Guid userId, string editId, long baseVersion, JsonElement ops, int? cursor)
    {
        if (string.IsNullOrWhiteSpace(editId) || editId.Length > 64)
        {
            throw new ScriptException(ScriptError.Invalid, "A change needs an id.");
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

            if (baseVersion != script.Version)
            {
                return (EditOutcome.Stale, script.Version);
            }

            string text;
            try
            {
                text = TextOps.Apply(script.Text, ops);
            }
            catch (ArgumentException e)
            {
                throw new ScriptException(ScriptError.Invalid, e.Message);
            }

            if (text.Length > MaxLength)
            {
                throw new ScriptException(ScriptError.Invalid, "The script would be too long.");
            }

            var now = _time.GetUtcNow();
            script.Text = text;
            script.Version++;
            script.UpdatedAt = now;
            script.Edits.Add(new StoredEdit(script.Version, editId, userId, ops.Clone(), now));
            if (script.Edits.Count > KeptEdits)
            {
                script.Edits.RemoveRange(0, script.Edits.Count - KeptEdits);
            }

            if (cursor is { } c)
            {
                live.Presence[userId] = new Presence(userId, c, script.Version, now);
            }

            _storage.Save(script);
            live.Wake();
            return (EditOutcome.Accepted, script.Version);
        }
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
