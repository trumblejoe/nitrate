using System.Text.Json;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Devices;
using Xunit;

namespace Nitrate.Companion.Tests;

public class DeviceTests
{
    private sealed class MemoryStorage : IDeviceReportStorage
    {
        private List<DeviceReport> _reports = new();

        public List<DeviceReport> Load() => _reports.ToList();

        public void Save(List<DeviceReport> reports) => _reports = reports.ToList();
    }

    private static JsonElement Json(string text) => JsonDocument.Parse(text).RootElement;

    [Fact]
    public void EachDeviceKeepsItsLatestSummary()
    {
        var time = new FakeTimeProvider(DateTimeOffset.FromUnixTimeMilliseconds(1_000));
        var hub = new DeviceHub(new MemoryStorage(), time);
        var joe = Guid.NewGuid();
        var sam = Guid.NewGuid();
        hub.Report(joe, "joe", "tv-1", "Living room", Json("""{"converted":3}"""));
        time.Advance(TimeSpan.FromSeconds(5));
        hub.Report(sam, "sam", "phone-1", "Sam's phone", Json("""{"converted":0}"""));
        time.Advance(TimeSpan.FromSeconds(5));
        hub.Report(joe, "joe", "tv-1", "Living room", Json("""{"converted":1}"""));
        var all = hub.All();
        Assert.Equal(2, all.Count);
        Assert.Equal("tv-1", all[0].DeviceId);
        Assert.Equal(1, all[0].Summary.GetProperty("converted").GetInt32());
        Assert.True(hub.Forget(sam, "phone-1"));
        Assert.Single(hub.All());
    }

    [Fact]
    public void OnlyObjectsOfAReasonableSizeAreKept()
    {
        var hub = new DeviceHub(new MemoryStorage(), new FakeTimeProvider());
        Assert.Throws<DeviceReportException>(() => hub.Report(Guid.NewGuid(), "joe", "tv", "TV", Json("[1,2]")));
        Assert.Throws<DeviceReportException>(() => hub.Report(Guid.NewGuid(), "joe", "", "TV", Json("{}")));
        var big = "{\"x\":\"" + new string('a', DeviceHub.MaxSummaryBytes) + "\"}";
        Assert.Throws<DeviceReportException>(() => hub.Report(Guid.NewGuid(), "joe", "tv", "TV", Json(big)));
    }
}
