using System.Text.Json;

namespace Nitrate.Companion.Partners;

/// <summary>One of a watch group's titles: a show, film, playlist or collection.</summary>
public class GroupTitle
{
    /// <summary>Gets or sets the title's id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets its name.</summary>
    public string Name { get; set; } = string.Empty;
}

/// <summary>
/// People and the titles they watch together (shows, films, playlists, collections; e.g. Severance
/// and The Bear with Tommy), with one schedule and one Group Watch Session time. Groups stored
/// before titles were a list have only <see cref="SeriesId"/>/<see cref="SeriesName"/>, read as a
/// one-title list (<see cref="PartnerHub"/> moves them in on load); both are still written, as
/// the first title, so an older plugin reads the group as before.
/// </summary>
public class WatchGroup
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets the first title (the only one, for groups stored before <see cref="Titles"/>).</summary>
    public Guid SeriesId { get; set; }

    /// <summary>Gets or sets the first title's name.</summary>
    public string SeriesName { get; set; } = string.Empty;

    /// <summary>Gets or sets the group's titles, in the order they were added.</summary>
    public List<GroupTitle> Titles { get; set; } = new();

    /// <summary>Gets or sets who's in it (two or more).</summary>
    public List<Guid> Members { get; set; } = new();

    /// <summary>Gets or sets since when.</summary>
    public DateTimeOffset Since { get; set; }

    /// <summary>Gets or sets how the titles are let out, and whether the group watches together (null: all at once, Together off).</summary>
    public GroupSchedule? Schedule { get; set; }

    /// <summary>Gets or sets a change to the schedule waiting for the others' votes.</summary>
    public ScheduleChange? Pending { get; set; }

    /// <summary>
    /// Gets or sets the group's own watched record: which parts of its titles each member watched
    /// as far as this group goes. Every group keeps one; Jellyfin keeps one watched state per
    /// person, so someone in several groups on one title is counted per group here, and group
    /// marks never change anyone's own (Jellyfin) watched state or another group's record.
    /// </summary>
    public List<GroupWatch> Watched { get; set; } = new();

    /// <summary>
    /// Gets or sets a value indicating whether <see cref="Watched"/> is this group's record. False
    /// for groups stored before every group kept one: a group that watched separately is seeded
    /// once from each member's own watched state then; a together-only group keeps its record.
    /// </summary>
    public bool RecordKept { get; set; }

    /// <summary>Whether <paramref name="titleId"/> is one of its titles.</summary>
    public bool HasTitle(Guid titleId) => Titles.Any(t => t.Id == titleId);

    /// <summary>"Severance & The Bear".</summary>
    public string TitleNames => Titles.Count == 0 ? SeriesName : string.Join(" & ", Titles.Select(t => t.Name));
}

/// <summary>One part (an episode, a film of a list) a member watched as far as a group goes.</summary>
public class GroupWatch
{
    /// <summary>Gets or sets who.</summary>
    public Guid Member { get; set; }

    /// <summary>Gets or sets the part.</summary>
    public Guid ItemId { get; set; }

    /// <summary>Gets or sets when it was first counted; null when the record was started from what had already been watched (before the schedule).</summary>
    public DateTimeOffset? At { get; set; }
}

/// <summary>A request to watch a show together, waiting for the other person.</summary>
public class PartnerRequest
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets who asked.</summary>
    public Guid From { get; set; }

    /// <summary>Gets or sets who it's for; empty when no such (visible or private) account: it then just waits and lapses, like an unanswered one.</summary>
    public Guid To { get; set; }

    /// <summary>Gets or sets what the asker chose or typed, shown back to them.</summary>
    public string ToLabel { get; set; } = string.Empty;

    /// <summary>Gets or sets the group it invites to (empty: a new group with the asker).</summary>
    public Guid GroupId { get; set; }

    /// <summary>Gets or sets the first title (a show, film, playlist or collection).</summary>
    public Guid SeriesId { get; set; }

    /// <summary>Gets or sets the first title's name.</summary>
    public string SeriesName { get; set; } = string.Empty;

    /// <summary>Gets or sets the titles a new group starts with (empty for invites stored before: <see cref="SeriesId"/> alone).</summary>
    public List<GroupTitle> Titles { get; set; } = new();

    /// <summary>Gets or sets when it was asked.</summary>
    public DateTimeOffset At { get; set; }

    /// <summary>Gets or sets the schedule a new group starts with (accepting means agreeing to it).</summary>
    public GroupSchedule? Schedule { get; set; }

    /// <summary>Gets or sets a value indicating whether the other person said no (the asker still sees it waiting until it lapses).</summary>
    public bool Declined { get; set; }
}

/// <summary>Everything kept: who made themselves private, watch groups and open requests.</summary>
public class PartnerState
{
    /// <summary>Gets or sets the people who made themselves private (admins can also mark people in the plugin's settings).</summary>
    public List<Guid> SelfPrivate { get; set; } = new();

    /// <summary>Gets or sets the watch groups.</summary>
    public List<WatchGroup> Groups { get; set; } = new();

    /// <summary>Gets or sets the open requests.</summary>
    public List<PartnerRequest> Requests { get; set; } = new();

    /// <summary>Gets or sets the groups' watch party nights that something happened to (postponed, skipped, started…); kept for a couple of days.</summary>
    public List<PartyNight> Parties { get; set; } = new();
}

/// <summary>Where the partner state is kept.</summary>
public interface IPartnerStorage
{
    /// <summary>The saved state (empty if none).</summary>
    PartnerState Load();

    /// <summary>Saves it.</summary>
    void Save(PartnerState state);
}

/// <summary>
/// The partners' state (groups, invites, schedules and their votes, private accounts) as one row in
/// the plugin's database: small, written when someone changes something. The older partners.json
/// is moved in once.
/// </summary>
public class DbPartnerStorage : IPartnerStorage
{
    private const string Kind = "state";
    private const string Key = "partners";
    private readonly Nitrate.Companion.Storage.CompanionDb _db;

    /// <summary>Initializes a new instance of the <see cref="DbPartnerStorage"/> class ([legacyJson]: the older file, moved in once).</summary>
    public DbPartnerStorage(Nitrate.Companion.Storage.CompanionDb db, string? legacyJson = null)
    {
        _db = db;
        if (legacyJson != null && File.Exists(legacyJson))
        {
            if (db.Pair(Kind, Key) == null)
            {
                db.PutPair(Kind, Key, File.ReadAllText(legacyJson));
            }

            File.Delete(legacyJson);
        }
    }

    /// <inheritdoc />
    public PartnerState Load()
    {
        var json = _db.Pair(Kind, Key);
        try
        {
            return json is null ? new PartnerState() : JsonSerializer.Deserialize<PartnerState>(json) ?? new PartnerState();
        }
        catch (JsonException)
        {
            // Keep the damaged copy for a look; start empty.
            _db.PutPair(Kind, Key + ".damaged", json!);
            return new PartnerState();
        }
    }

    /// <inheritdoc />
    public void Save(PartnerState state) => _db.PutPair(Kind, Key, JsonSerializer.Serialize(state));
}

/// <summary>The server's accounts, as partners need them.</summary>
public interface IPartnerDirectory
{
    /// <summary>Enabled accounts.</summary>
    IReadOnlyList<(Guid Id, string Name)> Users();

    /// <summary>The enabled account named exactly <paramref name="name"/> (any case).</summary>
    (Guid Id, string Name)? Find(string name);

    /// <summary>An enabled account's name, or null.</summary>
    string? NameOf(Guid id);

    /// <summary>Whether an admin marked the account private in the plugin's settings.</summary>
    bool AdminPrivate(Guid id);
}

/// <summary>Where someone is in a group's title.</summary>
/// <param name="Watched">What of it they've watched (episodes, films...).</param>
/// <param name="Last">The furthest they've got ("S2 E4", or a film's name), if anywhere.</param>
/// <param name="NextId">What comes after it (their next up), if anything.</param>
/// <param name="Order">The title's parts in watching order (empty when not known).</param>
/// <param name="FurthestAt">When they watched the furthest part they've seen.</param>
public record ShowProgress(IReadOnlyList<Guid> Watched, string? Last, Guid? NextId, IReadOnlyList<Guid>? Order = null, DateTimeOffset? FurthestAt = null);

/// <summary>People's watched state, as partners need it.</summary>
public interface IWatchState
{
    /// <summary>Where <paramref name="userId"/> is in a group's title (a show, film, playlist or collection).</summary>
    ShowProgress Progress(Guid userId, Guid titleId);

    /// <summary>Whether <paramref name="itemId"/> is part of the title (an episode of the show, an item of the list, the film itself).</summary>
    bool Belongs(Guid titleId, Guid itemId, Guid userId);

    /// <summary>What the title holds, in watching order (one library read, no watched state).</summary>
    IReadOnlyList<Guid> Order(Guid titleId, Guid userId) => Progress(userId, titleId).Order ?? Array.Empty<Guid>();

    /// <summary>"S2 E4" for an episode, else its name (null when unknown).</summary>
    string? Label(Guid itemId) => null;
}
