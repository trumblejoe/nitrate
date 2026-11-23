using System.Text.Json.Serialization;

namespace Nitrate.Companion.Channels;

/// <summary>What a lineup entry plays.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<SourceKind>))]
public enum SourceKind
{
    /// <summary>A show's episodes in order (specials left out).</summary>
    Series,

    /// <summary>One movie or episode (a season: its episodes).</summary>
    Item,

    /// <summary>A collection's titles (shows: their episodes).</summary>
    Collection,

    /// <summary>A playlist's entries, in its order.</summary>
    Playlist,

    /// <summary>A library's movies (a shows library: every show's episodes).</summary>
    Library,
}

/// <summary>How a lineup's sources take turns.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<ChannelMix>))]
public enum ChannelMix
{
    /// <summary>One from each source in turn, like a TV channel.</summary>
    Rotate,

    /// <summary>All of the first source, then the next.</summary>
    Sequential,

    /// <summary>A source picked at random each time.</summary>
    Shuffle,
}

/// <summary>One entry in a lineup.</summary>
/// <param name="Kind">What it is.</param>
/// <param name="Id">The show, title, collection, playlist or library.</param>
/// <param name="Name">Its name, as shown in the editor.</param>
/// <param name="Shuffle">Its own titles in a shuffled order (each round a new one) rather than in order.</param>
public record ChannelSource(SourceKind Kind, Guid Id, string Name, bool Shuffle = false)
{
    /// <summary>Gets the key its place in the order is kept under.</summary>
    [JsonIgnore]
    public string Key => $"{Kind}:{Id:N}";
}

/// <summary>A time slot with its own lineup ("Saturday mornings: cartoons").</summary>
/// <param name="Name">Its name in the guide.</param>
/// <param name="Days">Days it runs on, 0 = Sunday … 6 = Saturday.</param>
/// <param name="StartMinute">Start, minutes after midnight (server time).</param>
/// <param name="EndMinute">End; at or before the start: past midnight.</param>
/// <param name="Sources">What it plays.</param>
/// <param name="Mix">How they take turns.</param>
public record ChannelBlock(string Name, IReadOnlyList<int> Days, int StartMinute, int EndMinute, IReadOnlyList<ChannelSource> Sources, ChannelMix Mix = ChannelMix.Rotate);

/// <summary>A program on a channel's schedule, with what it changed so a later edit can undo it.</summary>
public class ScheduledProgram
{
    /// <summary>Gets or sets what plays.</summary>
    public Guid ItemId { get; set; }

    /// <summary>Gets or sets when it starts on the schedule.</summary>
    public DateTimeOffset Start { get; set; }

    /// <summary>Gets or sets when it ends (the next starts here, or at the next boundary).</summary>
    public DateTimeOffset End { get; set; }

    /// <summary>Gets or sets the time slot's name, if it's in one.</summary>
    public string? Block { get; set; }

    /// <summary>Gets or sets the lineup it came from ("main" or "b0"...).</summary>
    public string Lineup { get; set; } = "main";

    /// <summary>Gets or sets the source's cursor key.</summary>
    public string Cursor { get; set; } = string.Empty;

    /// <summary>Gets or sets the source's place before this program.</summary>
    public SourceCursor? CursorBefore { get; set; }

    /// <summary>Gets or sets the lineup's turn before this program.</summary>
    public int TurnBefore { get; set; }
}

/// <summary>Where a source has got to.</summary>
public class SourceCursor
{
    /// <summary>Gets or sets the last title scheduled.</summary>
    public Guid? LastId { get; set; }

    /// <summary>Gets or sets the place after it (used when that title is gone).</summary>
    public int Index { get; set; }

    /// <summary>Gets or sets how many times round (a new shuffle each time).</summary>
    public int Cycle { get; set; }

    /// <summary>A copy.</summary>
    public SourceCursor Copy() => new() { LastId = LastId, Index = Index, Cycle = Cycle };
}

/// <summary>A channel's schedule as made so far.</summary>
public class Playout
{
    /// <summary>Gets or sets the programs, in time order.</summary>
    public List<ScheduledProgram> Programs { get; set; } = new();

    /// <summary>Gets or sets each source's place.</summary>
    public Dictionary<string, SourceCursor> Cursors { get; set; } = new();

    /// <summary>Gets or sets each lineup's turn.</summary>
    public Dictionary<string, int> Turns { get; set; } = new();

    /// <summary>Gets or sets the seed for shuffles.</summary>
    public int Seed { get; set; }
}

/// <summary>A channel, as kept on the server.</summary>
public class StoredChannel
{
    /// <summary>Gets or sets its id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets its name.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets its number in the guide.</summary>
    public int Number { get; set; }

    /// <summary>Gets or sets what it plays outside time slots.</summary>
    public List<ChannelSource> Sources { get; set; } = new();

    /// <summary>Gets or sets how those take turns.</summary>
    public ChannelMix Mix { get; set; } = ChannelMix.Rotate;

    /// <summary>Gets or sets the boundary programs start on, in minutes (0: straight after each other).</summary>
    public int AlignMinutes { get; set; }

    /// <summary>Gets or sets its time slots.</summary>
    public List<ChannelBlock> Blocks { get; set; } = new();

    /// <summary>Gets or sets a value indicating whether everyone on the server can watch it.</summary>
    public bool Everyone { get; set; } = true;

    /// <summary>Gets or sets who else can (when not everyone).</summary>
    public List<Guid> Users { get; set; } = new();

    /// <summary>Gets or sets who made it.</summary>
    public Guid CreatedBy { get; set; }

    /// <summary>Gets or sets when it last changed.</summary>
    public DateTimeOffset UpdatedAt { get; set; }

    /// <summary>Gets or sets its schedule.</summary>
    public Playout Playout { get; set; } = new();
}

/// <summary>Something a source plays: a title and how long it runs.</summary>
public record PlayItem(Guid Id, long RuntimeMs);

/// <summary>How a title is shown in the guide.</summary>
public record ProgramLabel(string Title, string? Subtitle, string Kind);

/// <summary>The library, as channels need it.</summary>
public interface IChannelLibrary
{
    /// <summary>What <paramref name="source"/> plays, in its own order.</summary>
    IReadOnlyList<PlayItem> Expand(ChannelSource source);

    /// <summary>Whether <paramref name="userId"/> may watch the title (it exists and their access and parental controls allow it).</summary>
    bool CanSee(Guid itemId, Guid userId);

    /// <summary>The title's name for the guide (null: gone).</summary>
    ProgramLabel? Describe(Guid itemId);
}
