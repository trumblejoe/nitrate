using System.Text.Json;

namespace Nitrate.Companion.Channels;

/// <summary>Why a channel request was refused.</summary>
public enum ChannelError
{
    /// <summary>No such channel, or not one this user can watch.</summary>
    NotFound,

    /// <summary>Only admins change channels.</summary>
    NotAdmin,

    /// <summary>The channel as sent can't be saved (the message says why).</summary>
    Invalid,
}

/// <summary>A refused channel request.</summary>
public class ChannelException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="ChannelException"/> class.</summary>
    public ChannelException(ChannelError error, string message)
        : base(message) => Error = error;

    /// <summary>Gets why.</summary>
    public ChannelError Error { get; }
}

/// <summary>A channel as an admin sends it.</summary>
public record ChannelEdit(
    string Name,
    int Number,
    IReadOnlyList<ChannelSource> Sources,
    ChannelMix Mix,
    int AlignMinutes,
    IReadOnlyList<ChannelBlock>? Blocks,
    bool Everyone,
    IReadOnlyList<Guid>? Users);

/// <summary>A program in the guide, for one viewer.</summary>
public record GuideProgram(Guid ItemId, DateTimeOffset Start, DateTimeOffset End, string Title, string? Subtitle, string Kind, string? Block, bool Available);

/// <summary>Where channels are kept.</summary>
public interface IChannelStorage
{
    /// <summary>Every channel.</summary>
    IEnumerable<StoredChannel> LoadAll();

    /// <summary>Saves one.</summary>
    void Save(StoredChannel channel);

    /// <summary>Forgets one.</summary>
    void Delete(Guid id);
}

/// <summary>One JSON file per channel.</summary>
public class FileChannelStorage : IChannelStorage
{
    private static readonly JsonSerializerOptions Json = new();
    private readonly string _dir;

    /// <summary>Initializes a new instance of the <see cref="FileChannelStorage"/> class.</summary>
    public FileChannelStorage(string dir)
    {
        _dir = dir;
        Directory.CreateDirectory(dir);
    }

    /// <inheritdoc />
    public IEnumerable<StoredChannel> LoadAll()
    {
        foreach (var file in Directory.EnumerateFiles(_dir, "*.json"))
        {
            StoredChannel? channel = null;
            try
            {
                channel = JsonSerializer.Deserialize<StoredChannel>(File.ReadAllText(file), Json);
            }
            catch (JsonException)
            {
                // Left alone rather than lost.
            }

            if (channel is not null)
            {
                yield return channel;
            }
        }
    }

    /// <inheritdoc />
    public void Save(StoredChannel channel)
    {
        var path = Path.Combine(_dir, channel.Id.ToString("N") + ".json");
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(channel, Json));
        File.Move(temp, path, true);
    }

    /// <inheritdoc />
    public void Delete(Guid id) => File.Delete(Path.Combine(_dir, id.ToString("N") + ".json"));
}

/// <summary>
/// The household's channels: made by admins, each watchable by everyone or by the people it's
/// granted to. A channel's schedule is made a few days ahead when someone looks at the guide or
/// tunes in, and kept, so everyone sees the same guide; watching is always on demand from the
/// start of a program (see <see cref="ChannelScheduler"/>).
/// </summary>
public class ChannelHub
{
    /// <summary>How far ahead the guide goes.</summary>
    public static readonly TimeSpan Ahead = TimeSpan.FromDays(3);

    private readonly IChannelStorage _storage;
    private readonly IChannelLibrary _library;
    private readonly TimeProvider _time;
    private readonly TimeZoneInfo _tz;
    private readonly object _lock = new();
    private readonly Dictionary<Guid, StoredChannel> _channels;

    /// <summary>Initializes a new instance of the <see cref="ChannelHub"/> class.</summary>
    public ChannelHub(IChannelStorage storage, IChannelLibrary library, TimeProvider time, TimeZoneInfo? tz = null)
    {
        _storage = storage;
        _library = library;
        _time = time;
        _tz = tz ?? TimeZoneInfo.Local;
        _channels = storage.LoadAll().ToDictionary(c => c.Id);
    }

    /// <summary>The channels <paramref name="userId"/> can watch (admins: all), by number.</summary>
    public IReadOnlyList<StoredChannel> List(Guid userId, bool admin)
    {
        lock (_lock)
        {
            return _channels.Values.Where(c => CanWatch(c, userId, admin)).OrderBy(c => c.Number).ThenBy(c => c.Name, StringComparer.OrdinalIgnoreCase).ToList();
        }
    }

    /// <summary>One channel the user can watch.</summary>
    public StoredChannel Get(Guid id, Guid userId, bool admin)
    {
        lock (_lock)
        {
            return _channels.TryGetValue(id, out var c) && CanWatch(c, userId, admin) ? c : throw NotFound();
        }
    }

    /// <summary>Makes a channel (admins).</summary>
    public StoredChannel Create(ChannelEdit edit, Guid by, bool admin)
    {
        RequireAdmin(admin);
        Validate(edit);
        lock (_lock)
        {
            var c = new StoredChannel { Id = Guid.NewGuid(), CreatedBy = by, Playout = new Playout { Seed = Random.Shared.Next() } };
            Apply(c, edit);
            _channels[c.Id] = c;
            ChannelScheduler.Extend(c, _library.Expand, _time.GetUtcNow(), _time.GetUtcNow() + Ahead, _tz);
            _storage.Save(c);
            return c;
        }
    }

    /// <summary>
    /// Changes a channel (admins). What's on now stays; the rest of the schedule is made again
    /// from where each show had got to, or from the very beginning with <paramref name="startOver"/>.
    /// </summary>
    public StoredChannel Update(Guid id, ChannelEdit edit, bool admin, bool startOver = false)
    {
        RequireAdmin(admin);
        Validate(edit);
        lock (_lock)
        {
            var c = _channels.TryGetValue(id, out var found) ? found : throw NotFound();
            Apply(c, edit);
            var now = _time.GetUtcNow();
            if (startOver)
            {
                c.Playout = new Playout { Seed = Random.Shared.Next() };
            }
            else
            {
                ChannelScheduler.DropFuture(c, now);
            }

            ChannelScheduler.Extend(c, _library.Expand, now, now + Ahead, _tz);
            _storage.Save(c);
            return c;
        }
    }

    /// <summary>Deletes a channel (admins).</summary>
    public void Delete(Guid id, bool admin)
    {
        RequireAdmin(admin);
        lock (_lock)
        {
            if (!_channels.Remove(id))
            {
                throw NotFound();
            }

            _storage.Delete(id);
        }
    }

    /// <summary>The guide between <paramref name="from"/> and <paramref name="to"/> for the user's channels.</summary>
    public IReadOnlyList<(StoredChannel Channel, IReadOnlyList<GuideProgram> Programs)> Guide(Guid userId, bool admin, DateTimeOffset from, DateTimeOffset to)
    {
        var now = _time.GetUtcNow();
        to = to > now + Ahead + TimeSpan.FromDays(5) ? now + Ahead + TimeSpan.FromDays(5) : to;
        lock (_lock)
        {
            return List(userId, admin).Select(c =>
            {
                Ensure(c, to);
                IReadOnlyList<GuideProgram> programs = c.Playout.Programs.Where(p => p.End > from && p.Start < to).Select(p => Show(p, userId)).ToList();
                return (c, programs);
            }).ToList();
        }
    }

    /// <summary>
    /// Tuning in: the program on now, from its start, then the ones after it in order (skipping
    /// any the user can't watch), <paramref name="count"/> in all.
    /// </summary>
    public IReadOnlyList<GuideProgram> Tune(Guid id, Guid userId, bool admin, int count)
    {
        lock (_lock)
        {
            var c = Get(id, userId, admin);
            var now = _time.GetUtcNow();
            Ensure(c, now + TimeSpan.FromDays(1));
            return c.Playout.Programs.Where(p => p.End > now).Select(p => Show(p, userId)).Where(p => p.Available).Take(Math.Clamp(count, 1, 200)).ToList();
        }
    }

    private void Ensure(StoredChannel c, DateTimeOffset until)
    {
        var now = _time.GetUtcNow();
        var target = until > now + Ahead ? until : now + Ahead;
        // Made a day further than asked at a time, so most requests change nothing.
        if (c.Playout.Programs.Count == 0 || c.Playout.Programs[^1].End < until || c.Playout.Programs[0].End < now - ChannelScheduler.KeepPast)
        {
            if (ChannelScheduler.Extend(c, _library.Expand, now, target + TimeSpan.FromDays(1), _tz))
            {
                _storage.Save(c);
            }
        }
    }

    private GuideProgram Show(ScheduledProgram p, Guid userId)
    {
        var label = _library.Describe(p.ItemId);
        return new GuideProgram(p.ItemId, p.Start, p.End, label?.Title ?? "Not in the library any more", label?.Subtitle, label?.Kind ?? "Unknown", p.Block, label is not null && _library.CanSee(p.ItemId, userId));
    }

    private static bool CanWatch(StoredChannel c, Guid userId, bool admin) => admin || c.Everyone || c.Users.Contains(userId);

    private static void Apply(StoredChannel c, ChannelEdit e)
    {
        c.Name = e.Name.Trim();
        c.Number = e.Number;
        c.Sources = e.Sources.ToList();
        c.Mix = e.Mix;
        c.AlignMinutes = e.AlignMinutes;
        c.Blocks = (e.Blocks ?? Array.Empty<ChannelBlock>()).ToList();
        c.Everyone = e.Everyone;
        c.Users = (e.Users ?? Array.Empty<Guid>()).Distinct().ToList();
        c.UpdatedAt = DateTimeOffset.UtcNow;
    }

    private static void Validate(ChannelEdit e)
    {
        if (string.IsNullOrWhiteSpace(e.Name))
        {
            throw new ChannelException(ChannelError.Invalid, "A channel needs a name.");
        }

        if (e.Sources.Count == 0 && (e.Blocks ?? Array.Empty<ChannelBlock>()).All(b => b.Sources.Count == 0))
        {
            throw new ChannelException(ChannelError.Invalid, "Add something for the channel to play.");
        }

        if (e.AlignMinutes is < 0 or > 60 || (e.AlignMinutes > 0 && 60 % e.AlignMinutes != 0))
        {
            throw new ChannelException(ChannelError.Invalid, "Programs can start on 5, 10, 15, 30 or 60 minute marks.");
        }

        foreach (var b in e.Blocks ?? Array.Empty<ChannelBlock>())
        {
            if (b.Days.Count == 0 || b.Days.Any(d => d is < 0 or > 6) || b.StartMinute is < 0 or >= 1440 || b.EndMinute is < 0 or > 1440)
            {
                throw new ChannelException(ChannelError.Invalid, $"The time slot \"{b.Name}\" needs days and a time of day.");
            }
        }
    }

    private static void RequireAdmin(bool admin)
    {
        if (!admin)
        {
            throw new ChannelException(ChannelError.NotAdmin, "Only admins can change channels.");
        }
    }

    private static ChannelException NotFound() => new(ChannelError.NotFound, "No such channel.");
}
