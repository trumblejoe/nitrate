using System.Text.Json;

namespace Nitrate.Companion.Partners;

/// <summary>People who watch one show together and see each other's progress in it.</summary>
public class WatchGroup
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets the show.</summary>
    public Guid SeriesId { get; set; }

    /// <summary>Gets or sets the show's name.</summary>
    public string SeriesName { get; set; } = string.Empty;

    /// <summary>Gets or sets who's in it (two or more).</summary>
    public List<Guid> Members { get; set; } = new();

    /// <summary>Gets or sets since when.</summary>
    public DateTimeOffset Since { get; set; }
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

    /// <summary>Gets or sets the show.</summary>
    public Guid SeriesId { get; set; }

    /// <summary>Gets or sets the show's name.</summary>
    public string SeriesName { get; set; } = string.Empty;

    /// <summary>Gets or sets when it was asked.</summary>
    public DateTimeOffset At { get; set; }

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
}

/// <summary>Where the partner state is kept.</summary>
public interface IPartnerStorage
{
    /// <summary>The saved state (empty if none).</summary>
    PartnerState Load();

    /// <summary>Saves it.</summary>
    void Save(PartnerState state);
}

/// <summary>One JSON file in the server's data folder.</summary>
public class FilePartnerStorage : IPartnerStorage
{
    private readonly string _path;

    /// <summary>Initializes a new instance of the <see cref="FilePartnerStorage"/> class.</summary>
    public FilePartnerStorage(string path)
    {
        _path = path;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    }

    /// <inheritdoc />
    public PartnerState Load()
    {
        try
        {
            return File.Exists(_path) ? JsonSerializer.Deserialize<PartnerState>(File.ReadAllText(_path)) ?? new PartnerState() : new PartnerState();
        }
        catch (JsonException)
        {
            // Keep the damaged file for a look; start empty.
            File.Copy(_path, _path + ".damaged", true);
            return new PartnerState();
        }
    }

    /// <inheritdoc />
    public void Save(PartnerState state)
    {
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(state));
        File.Move(temp, _path, true);
    }
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

/// <summary>Where someone is in a show.</summary>
/// <param name="Watched">Episodes they've watched.</param>
/// <param name="Last">The furthest episode they've watched ("S2 E4"), if any.</param>
/// <param name="NextId">The episode after it (their next up), if any.</param>
public record ShowProgress(IReadOnlyList<Guid> Watched, string? Last, Guid? NextId);

/// <summary>People's watched state, as partners need it.</summary>
public interface IWatchState
{
    /// <summary>Where <paramref name="userId"/> is in a show.</summary>
    ShowProgress Progress(Guid userId, Guid seriesId);

    /// <summary>The show an episode belongs to.</summary>
    Guid? SeriesOf(Guid itemId);

    /// <summary>Marks the episode watched for <paramref name="userId"/>.</summary>
    void MarkWatched(Guid userId, Guid itemId);
}
