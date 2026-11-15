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

    private string PathOf(Guid id) => Path.Combine(_dir, id.ToString("N") + ".json");
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
}
