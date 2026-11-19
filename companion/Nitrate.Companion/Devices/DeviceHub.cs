using System.Text.Json;

namespace Nitrate.Companion.Devices;

/// <summary>
/// One device's latest playback summary, as its app sent it: how its library plays there, its test
/// plays and what its user saw at the TV and receiver. The summary itself is the app's own JSON,
/// kept as it came (only its size is limited), so newer apps can add to it without a plugin update.
/// </summary>
public record DeviceReport(Guid UserId, string UserName, string DeviceId, string DeviceName, long At, JsonElement Summary);

/// <summary>Where the reports are kept.</summary>
public interface IDeviceReportStorage
{
    /// <summary>Every report.</summary>
    List<DeviceReport> Load();

    /// <summary>Saves every report.</summary>
    void Save(List<DeviceReport> reports);
}

/// <summary>One JSON file in the server's data folder.</summary>
public class FileDeviceReportStorage : IDeviceReportStorage
{
    private readonly string _path;

    /// <summary>Initializes a new instance of the <see cref="FileDeviceReportStorage"/> class.</summary>
    public FileDeviceReportStorage(string path)
    {
        _path = path;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
    }

    /// <inheritdoc />
    public List<DeviceReport> Load()
    {
        try
        {
            return File.Exists(_path) ? JsonSerializer.Deserialize<List<DeviceReport>>(File.ReadAllText(_path)) ?? new() : new();
        }
        catch (JsonException)
        {
            return new();
        }
    }

    /// <inheritdoc />
    public void Save(List<DeviceReport> reports)
    {
        var temp = _path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(reports));
        File.Move(temp, _path, overwrite: true);
    }
}

/// <summary>A report was refused.</summary>
public class DeviceReportException(string message) : Exception(message);

/// <summary>
/// Household devices: each signed-in device sends its latest playback summary (one per person and
/// device, replacing the last); admins read them all, so they can see how every TV and phone in the
/// house plays the library without asking anyone to read a screen out.
/// </summary>
public class DeviceHub
{
    /// <summary>Largest summary kept (the overview lists converted titles; a big library stays well under this).</summary>
    public const int MaxSummaryBytes = 512 * 1024;

    /// <summary>Most reports kept; the oldest go first.</summary>
    public const int MaxReports = 200;

    private readonly IDeviceReportStorage _storage;
    private readonly TimeProvider _time;
    private readonly object _lock = new();

    /// <summary>Initializes a new instance of the <see cref="DeviceHub"/> class.</summary>
    public DeviceHub(IDeviceReportStorage storage, TimeProvider time)
    {
        _storage = storage;
        _time = time;
    }

    /// <summary>Keeps <paramref name="summary"/> as this person's report for <paramref name="deviceId"/>.</summary>
    public DeviceReport Report(Guid userId, string userName, string deviceId, string deviceName, JsonElement summary)
    {
        if (string.IsNullOrWhiteSpace(deviceId) || deviceId.Length > 200)
        {
            throw new DeviceReportException("A device id is needed.");
        }

        if (summary.ValueKind != JsonValueKind.Object)
        {
            throw new DeviceReportException("The summary must be a JSON object.");
        }

        if (summary.GetRawText().Length > MaxSummaryBytes)
        {
            throw new DeviceReportException("The summary is too large.");
        }

        var report = new DeviceReport(userId, userName, deviceId, deviceName.Length > 200 ? deviceName[..200] : deviceName, _time.GetUtcNow().ToUnixTimeMilliseconds(), summary.Clone());
        lock (_lock)
        {
            var all = _storage.Load();
            all.RemoveAll(r => r.UserId == userId && r.DeviceId == deviceId);
            all.Add(report);
            if (all.Count > MaxReports)
            {
                all = all.OrderByDescending(r => r.At).Take(MaxReports).ToList();
            }

            _storage.Save(all);
        }

        return report;
    }

    /// <summary>Every device's latest report, newest first.</summary>
    public IReadOnlyList<DeviceReport> All()
    {
        lock (_lock)
        {
            return _storage.Load().OrderByDescending(r => r.At).ToList();
        }
    }

    /// <summary>Forgets one report.</summary>
    public bool Forget(Guid userId, string deviceId)
    {
        lock (_lock)
        {
            var all = _storage.Load();
            var removed = all.RemoveAll(r => r.UserId == userId && r.DeviceId == deviceId) > 0;
            if (removed)
            {
                _storage.Save(all);
            }

            return removed;
        }
    }
}
