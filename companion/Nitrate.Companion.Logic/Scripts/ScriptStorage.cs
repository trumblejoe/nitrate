using System.Text.Json;

namespace Nitrate.Companion.Scripts;

/// <summary>Where shared scripts are kept between server restarts.</summary>
public interface IScriptStorage
{
    /// <summary>Every saved script.</summary>
    IEnumerable<StoredScript> LoadAll();

    /// <summary>Saves one script (whole).</summary>
    void Save(StoredScript script);

    /// <summary>Forgets one script.</summary>
    void Delete(Guid id);

    /// <summary>Adds a change to the script's full history.</summary>
    void AppendLog(Guid id, StoredEdit edit);

    /// <summary>Changes from <paramref name="fromVersion"/> on, oldest first, at most <paramref name="limit"/>.</summary>
    IReadOnlyList<StoredEdit> ReadLog(Guid id, long fromVersion, int limit);

    /// <summary>Forgets a script's history.</summary>
    void DeleteLog(Guid id);
}

/// <summary>One JSON file per script, in the plugin's data folder.</summary>
public class FileScriptStorage : IScriptStorage
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly string _dir;

    /// <summary>Initializes a new instance of the <see cref="FileScriptStorage"/> class.</summary>
    public FileScriptStorage(string dir)
    {
        _dir = dir;
        Directory.CreateDirectory(dir);
    }

    /// <inheritdoc />
    public IEnumerable<StoredScript> LoadAll()
    {
        foreach (var file in Directory.EnumerateFiles(_dir, "*.json"))
        {
            StoredScript? script = null;
            try
            {
                script = JsonSerializer.Deserialize<StoredScript>(File.ReadAllText(file), Json);
            }
            catch (JsonException)
            {
                // A damaged file is left alone (and skipped) rather than lost.
            }

            if (script is not null)
            {
                yield return script;
            }
        }
    }

    /// <inheritdoc />
    public void Save(StoredScript script)
    {
        var path = PathOf(script.Id);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(script, Json));
        File.Move(temp, path, overwrite: true);
    }

    /// <inheritdoc />
    public void Delete(Guid id) => File.Delete(PathOf(id));

    /// <inheritdoc />
    public void AppendLog(Guid id, StoredEdit edit) =>
        File.AppendAllText(LogOf(id), JsonSerializer.Serialize(edit, Json) + "\n");

    /// <inheritdoc />
    public IReadOnlyList<StoredEdit> ReadLog(Guid id, long fromVersion, int limit)
    {
        var path = LogOf(id);
        if (!File.Exists(path))
        {
            return Array.Empty<StoredEdit>();
        }

        var result = new List<StoredEdit>();
        foreach (var line in File.ReadLines(path))
        {
            if (line.Length == 0)
            {
                continue;
            }

            StoredEdit? edit;
            try
            {
                edit = JsonSerializer.Deserialize<StoredEdit>(line, Json);
            }
            catch (JsonException)
            {
                // A line cut short (the server stopped mid-write) is skipped.
                continue;
            }

            if (edit is not null && edit.Version >= fromVersion)
            {
                result.Add(edit);
                if (result.Count >= limit)
                {
                    break;
                }
            }
        }

        return result;
    }

    /// <inheritdoc />
    public void DeleteLog(Guid id) => File.Delete(LogOf(id));

    private string PathOf(Guid id) => Path.Combine(_dir, id.ToString("N") + ".json");

    private string LogOf(Guid id) => Path.Combine(_dir, id.ToString("N") + ".log");
}

/// <summary>Keeps scripts in memory only (tests).</summary>
public class MemoryScriptStorage : IScriptStorage
{
    /// <summary>Gets what's been saved, by id, as JSON (so tests see what a file would hold).</summary>
    public Dictionary<Guid, string> Saved { get; } = new();

    /// <inheritdoc />
    public IEnumerable<StoredScript> LoadAll() => Saved.Values.Select(s => JsonSerializer.Deserialize<StoredScript>(s)!).ToList();

    /// <inheritdoc />
    public void Save(StoredScript script) => Saved[script.Id] = JsonSerializer.Serialize(script);

    /// <inheritdoc />
    public void Delete(Guid id) => Saved.Remove(id);

    /// <summary>Gets each script's full history, as JSON lines.</summary>
    public Dictionary<Guid, List<string>> Logs { get; } = new();

    /// <inheritdoc />
    public void AppendLog(Guid id, StoredEdit edit)
    {
        if (!Logs.TryGetValue(id, out var log))
        {
            Logs[id] = log = new List<string>();
        }

        log.Add(JsonSerializer.Serialize(edit));
    }

    /// <inheritdoc />
    public IReadOnlyList<StoredEdit> ReadLog(Guid id, long fromVersion, int limit) =>
        (Logs.TryGetValue(id, out var log) ? log : new List<string>())
            .Select(l => JsonSerializer.Deserialize<StoredEdit>(l)!).Where(e => e.Version >= fromVersion).Take(limit).ToList();

    /// <inheritdoc />
    public void DeleteLog(Guid id) => Logs.Remove(id);
}
