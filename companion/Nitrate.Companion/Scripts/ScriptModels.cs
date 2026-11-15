using System.Text.Json;

namespace Nitrate.Companion.Scripts;

/// <summary>A shared script as kept on the server.</summary>
public class StoredScript
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets who made it: only they invite, remove and delete.</summary>
    public Guid OwnerId { get; set; }

    /// <summary>Gets or sets the people invited to write in it (not the owner).</summary>
    public List<Guid> Members { get; set; } = new();

    /// <summary>Gets or sets the text now.</summary>
    public string Text { get; set; } = string.Empty;

    /// <summary>Gets or sets the number of changes made so far.</summary>
    public long Version { get; set; }

    /// <summary>Gets or sets the recent changes, oldest first, so apps that were away can catch up.</summary>
    public List<StoredEdit> Edits { get; set; } = new();

    /// <summary>Gets or sets when it was made.</summary>
    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>Gets or sets when it last changed.</summary>
    public DateTimeOffset UpdatedAt { get; set; }

    /// <summary>True when <paramref name="userId"/> may read and write it.</summary>
    public bool CanWrite(Guid userId) => OwnerId == userId || Members.Contains(userId);
}

/// <summary>One accepted change.</summary>
/// <param name="Version">The script's version this change made.</param>
/// <param name="EditId">The app's id for it, so a resent change isn't applied twice.</param>
/// <param name="UserId">Who made it.</param>
/// <param name="Ops">The change, in the wire form (see <see cref="TextOps"/>).</param>
/// <param name="At">When the server took it.</param>
public record StoredEdit(long Version, string EditId, Guid UserId, JsonElement Ops, DateTimeOffset At);

/// <summary>Where someone's cursor is in a script, and how recently they were there.</summary>
/// <param name="UserId">Who.</param>
/// <param name="Cursor">Their cursor, as a character offset into the text at <paramref name="Version"/>.</param>
/// <param name="Version">The version the cursor is in.</param>
/// <param name="SeenAt">When they last said so.</param>
public record Presence(Guid UserId, int Cursor, long Version, DateTimeOffset SeenAt);

/// <summary>What happened to a change.</summary>
public enum EditOutcome
{
    /// <summary>Applied: it made the returned version.</summary>
    Accepted,

    /// <summary>Not applied: it was made on an older version; catch up, rebase and send again.</summary>
    Stale,
}

/// <summary>Changes after a version, who's here, and the version now.</summary>
/// <param name="Edits">Oldest first.</param>
/// <param name="Version">The script's version now.</param>
/// <param name="Presence">Other people with the script open.</param>
/// <param name="Stamp">Goes up with anything that happens to the script; send it back as "seen".</param>
public record EditsPage(IReadOnlyList<StoredEdit> Edits, long Version, IReadOnlyList<Presence> Presence, long Stamp);

/// <summary>Why a request about a script was refused.</summary>
public class ScriptException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="ScriptException"/> class.</summary>
    public ScriptException(ScriptError error, string message)
        : base(message) => Error = error;

    /// <summary>Gets what went wrong.</summary>
    public ScriptError Error { get; }
}

/// <summary>What went wrong with a request about a script.</summary>
public enum ScriptError
{
    /// <summary>No such script, or not one this user may see.</summary>
    NotFound,

    /// <summary>Only the owner may do that.</summary>
    NotOwner,

    /// <summary>The request doesn't make sense (bad change, too big...).</summary>
    Invalid,

    /// <summary>The changes asked for are no longer kept: load the script again.</summary>
    Gone,
}
