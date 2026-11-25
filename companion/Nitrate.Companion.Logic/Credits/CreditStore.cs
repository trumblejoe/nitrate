using System.Text.Json;

namespace Nitrate.Companion.Credits;

/// <summary>What was found for one title, and which version of it was checked.</summary>
/// <param name="Version">The title's file and change date when checked (a new file is checked again).</param>
/// <param name="Skips">Scenes found (none: checked, nothing there).</param>
/// <param name="Checked">When.</param>
/// <param name="OutroStartMs">Where the final stretch of credits starts (to the end, after any scene), if it ends on credits.</param>
/// <param name="OutroEndMs">The title's end, when [OutroStartMs] is known.</param>
/// <param name="Format">How it was read: older results (without the final credits) are read again.</param>
/// <param name="OutroRejected">People said the final credits found were wrong: none are given for this version of the file.</param>
public record CreditResult(string Version, IReadOnlyList<CreditSkip> Skips, DateTimeOffset Checked, long? OutroStartMs = null, long? OutroEndMs = null, int Format = 0, bool OutroRejected = false)
{
    /// <summary>What's handed out: rejected final credits left out.</summary>
    public CreditResult Effective() => OutroRejected ? this with { OutroStartMs = null, OutroEndMs = null } : this;

    /// <summary>
    /// What results are read as now: 3 = 320×180 colour frames once a second with text, scroll and
    /// colour features and the dialogue vote (every title is read again once).
    /// </summary>
    public const int CurrentFormat = 3;
}

/// <summary>
/// The features of every frame read of a title's last part (never the pictures), for the admins'
/// report: 16 bytes a frame (<see cref="CreditFrameCodec"/>), base64.
/// </summary>
/// <param name="Version">The file version they were read from.</param>
/// <param name="StartMs">The first frame's time.</param>
/// <param name="IntervalMs">Time between frames.</param>
/// <param name="Data">The packed features.</param>
public record CreditFrameSet(string Version, long StartMs, long IntervalMs, string Data)
{
    /// <summary>The features, unpacked.</summary>
    public IReadOnlyList<CreditFeatures> Frames() => CreditFrameCodec.Unpack(Convert.FromBase64String(Data));
}

/// <summary>Packs features into 16 bytes a frame, rounded the way the report shows them.</summary>
public static class CreditFrameCodec
{
    /// <summary>Bytes per frame.</summary>
    public const int Size = 16;

    /// <summary>The features as bytes.</summary>
    public static byte[] Pack(IReadOnlyList<CreditFeatures> frames)
    {
        var bytes = new byte[frames.Count * Size];
        static byte B(double value) => (byte)Math.Clamp(Math.Round(value), 0, 255);
        for (var i = 0; i < frames.Count; i++)
        {
            var f = frames[i];
            var o = i * Size;
            bytes[o] = B(f.Dark * 250);
            bytes[o + 1] = B(f.Bright * 250);
            bytes[o + 2] = B(f.Mid * 250);
            bytes[o + 3] = B(f.Marks * 1000);
            bytes[o + 4] = B(f.Entropy * 50);
            bytes[o + 5] = B(f.Sharp * 250);
            bytes[o + 6] = B(f.Lines);
            bytes[o + 7] = B(f.LineInk * 250);
            bytes[o + 8] = B(f.Regular * 250);
            bytes[o + 9] = B(f.Symmetry * 250);
            bytes[o + 10] = B(f.Strokes * 250);
            bytes[o + 11] = B(f.StrokeWidth * 10);
            bytes[o + 12] = f.Saturation is { } s ? B(Math.Min(254, s)) : (byte)255;
            bytes[o + 13] = B(f.ScrollDy);
            bytes[o + 14] = B(f.ScrollMad * 10);
            bytes[o + 15] = B(f.StillMad * 10);
        }

        return bytes;
    }

    /// <summary>Bytes back into features.</summary>
    public static IReadOnlyList<CreditFeatures> Unpack(byte[] bytes)
    {
        var frames = new List<CreditFeatures>(bytes.Length / Size);
        for (var o = 0; o + Size <= bytes.Length; o += Size)
        {
            frames.Add(new CreditFeatures(
                bytes[o] / 250.0,
                bytes[o + 1] / 250.0,
                bytes[o + 2] / 250.0,
                bytes[o + 3] / 1000.0,
                bytes[o + 4] / 50.0,
                bytes[o + 5] / 250.0,
                bytes[o + 6],
                bytes[o + 7] / 250.0,
                bytes[o + 8] / 250.0,
                bytes[o + 9] / 250.0,
                bytes[o + 10] / 250.0,
                bytes[o + 11] / 10.0,
                bytes[o + 12] == 255 ? null : bytes[o + 12],
                bytes[o + 13],
                bytes[o + 14] / 10.0,
                bytes[o + 15] / 10.0));
        }

        return frames;
    }

    /// <summary>The features as kept (rounded), so the scan decides on exactly what the report shows.</summary>
    public static IReadOnlyList<CreditFeatures> Round(IReadOnlyList<CreditFeatures> frames) => Unpack(Pack(frames));
}

/// <summary>Results kept in the server's database, one per title (and each title's frame features, read one at a time).</summary>
public class CreditStore
{
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly object _lock = new();
    private const string FramesKind = "credit-frames";
    private readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, CreditFrameSet> _memoryFrames = new();
    private readonly Dictionary<Guid, CreditResult> _results;

    /// <summary>Initializes a new instance of the <see cref="CreditStore"/> class ([db] null: in memory only; [legacyJson]: results from before the database, moved in once).</summary>
    public CreditStore(Nitrate.Companion.Storage.CompanionDb? db, string? legacyJson = null)
    {
        _db = db;
        db?.ImportResults<CreditResult>("credits", legacyJson);
        _results = db?.Results<CreditResult>("credits") ?? new();
    }

    /// <summary>The result for [item], if it was checked.</summary>
    public CreditResult? Get(Guid item)
    {
        lock (_lock)
        {
            return _results.TryGetValue(item, out var r) ? r : null;
        }
    }

    /// <summary>Whether [item] needs checking (never checked, or checked as another version).</summary>
    public bool Needs(Guid item, string version) => Get(item) is not { } r || r.Version != version || r.Format < CreditResult.CurrentFormat;

    /// <summary>Keeps [item]'s result.</summary>
    public void Put(Guid item, CreditResult result)
    {
        lock (_lock)
        {
            _results[item] = result;
            _db?.PutResult("credits", item, result);
        }
    }

    /// <summary>[item]'s frame features, if kept.</summary>
    public CreditFrameSet? Frames(Guid item) => _db?.Result<CreditFrameSet>(FramesKind, item) ?? (_memoryFrames.TryGetValue(item, out var f) ? f : null);

    /// <summary>Keeps [item]'s frame features.</summary>
    public void PutFrames(Guid item, CreditFrameSet frames)
    {
        if (_db == null)
        {
            _memoryFrames[item] = frames;
        }
        else
        {
            _db.PutResult(FramesKind, item, frames);
        }
    }

    /// <summary>Forgets titles no longer in the library.</summary>
    public void KeepOnly(ISet<Guid> present)
    {
        lock (_lock)
        {
            var gone = _results.Keys.Where(k => !present.Contains(k)).ToList();
            if (gone.Count == 0)
            {
                return;
            }

            gone.ForEach(k => _results.Remove(k));
            _db?.DeleteResults("credits", gone);
            _db?.DeleteResults(FramesKind, gone);
        }
    }

}
