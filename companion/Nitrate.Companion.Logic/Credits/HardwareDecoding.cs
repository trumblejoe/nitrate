using System.Collections.Concurrent;
using MediaBrowser.Model.Configuration;
using MediaBrowser.Model.Entities;

namespace Nitrate.Companion.Credits;

/// <summary>
/// The server's hardware decoding, as set in Jellyfin (Dashboard → Playback → Transcoding): which
/// acceleration, which device, and which codecs (and bit depths) it decodes. Nitrate has no
/// setting of its own: what Jellyfin uses for playback, the credits reader uses too.
/// </summary>
/// <param name="Type">Jellyfin's acceleration.</param>
/// <param name="VaapiDevice">Jellyfin's VA-API device (a render node).</param>
/// <param name="QsvDevice">Jellyfin's QSV device (a render node on Linux, an adapter index on Windows).</param>
/// <param name="Codecs">The codecs Jellyfin decodes in hardware (its "Enable hardware decoding for" ticks).</param>
/// <param name="Hevc10">10-bit HEVC too.</param>
/// <param name="Vp910">10-bit VP9 too.</param>
/// <param name="HevcRext10">HEVC RExt 8/10-bit (4:2:2, 4:4:4) too.</param>
/// <param name="HevcRext12">HEVC RExt 12-bit too.</param>
public sealed record HardwareDecoding(
    HardwareAccelerationType Type,
    string? VaapiDevice,
    string? QsvDevice,
    IReadOnlyCollection<string> Codecs,
    bool Hevc10 = false,
    bool Vp910 = false,
    bool HevcRext10 = false,
    bool HevcRext12 = false)
{
    /// <summary>The render node when Jellyfin names none.</summary>
    public const string DefaultRenderNode = "/dev/dri/renderD128";

    private static readonly ConcurrentDictionary<string, string> FailedKeys = new();

    /// <summary>Jellyfin's encoding options as this reads them.</summary>
    public static HardwareDecoding From(EncodingOptions options) => new(
        options.HardwareAccelerationType,
        options.VaapiDevice,
        options.QsvDevice,
        options.HardwareDecodingCodecs ?? Array.Empty<string>(),
        options.EnableDecodingColorDepth10Hevc,
        options.EnableDecodingColorDepth10Vp9,
        options.EnableDecodingColorDepth10HevcRext,
        options.EnableDecodingColorDepth12HevcRext);

    /// <summary>
    /// ffmpeg's input arguments to decode a [codec] video ([bitDepth], [pixelFormat] as Jellyfin
    /// read them) in hardware the way the server is set up, with the frames handed back in system
    /// memory (no <c>-hwaccel_output_format</c>: ffmpeg downloads each frame before the filters,
    /// which scale it to 320×180 yuv420p as for software); null for software: no acceleration set,
    /// one this doesn't use (V4L2, RKMPP), the codec or its bit depth not ticked in Jellyfin, or
    /// [supported] (the server's ffmpeg, <c>IMediaEncoder.SupportsHwaccel</c>) lacking it.
    /// </summary>
    public HardwareChoice? For(string? codec, int? bitDepth, string? pixelFormat, bool windows, Func<string, bool>? supported = null)
    {
        var c = Normalize(codec);
        if (c == null || !Codecs.Any(x => string.Equals(Normalize(x), c, StringComparison.Ordinal)))
        {
            return null;
        }

        var depth = bitDepth ?? (pixelFormat?.Contains("10", StringComparison.Ordinal) == true ? 10 : pixelFormat?.Contains("12", StringComparison.Ordinal) == true ? 12 : 8);
        var rext = pixelFormat != null && (pixelFormat.Contains("422", StringComparison.Ordinal) || pixelFormat.Contains("444", StringComparison.Ordinal));
        var depthOk = c switch
        {
            "hevc" when rext => depth <= 10 ? HevcRext10 : depth == 12 && HevcRext12,
            "hevc" => depth <= 8 || (depth == 10 && Hevc10),
            "vp9" => depth <= 8 || (depth == 10 && Vp910),
            "av1" => depth <= 10,
            // H.264 High 10, MPEG-2, VC-1, MPEG-4, VP8: 8-bit 4:2:0 only.
            _ => depth <= 8 && !rext,
        };
        if (!depthOk)
        {
            return null;
        }

        static string Node(string? device) => device is { Length: > 0 } d && d.StartsWith('/') ? d : DefaultRenderNode;
        HardwareChoice? choice = Type switch
        {
            HardwareAccelerationType.vaapi when !windows => new("vaapi", new[] { "-hwaccel", "vaapi", "-hwaccel_device", Node(VaapiDevice) }),
            // Intel QSV decodes through D3D11 on Windows and VA-API on Linux, as Jellyfin's own pipeline does.
            HardwareAccelerationType.qsv when windows => new("d3d11va", QsvDevice is { Length: > 0 } index && int.TryParse(index, out _)
                ? new[] { "-hwaccel", "d3d11va", "-hwaccel_device", index }
                : new[] { "-hwaccel", "d3d11va" }),
            HardwareAccelerationType.qsv => new("vaapi", new[] { "-hwaccel", "vaapi", "-hwaccel_device", Node(QsvDevice) }),
            // NVIDIA (NVDEC).
            HardwareAccelerationType.nvenc => new("cuda", new[] { "-hwaccel", "cuda" }),
            // AMD AMF: D3D11 on Windows, VA-API on Linux.
            HardwareAccelerationType.amf when windows => new("d3d11va", new[] { "-hwaccel", "d3d11va" }),
            HardwareAccelerationType.amf => new("vaapi", new[] { "-hwaccel", "vaapi", "-hwaccel_device", Node(VaapiDevice) }),
            HardwareAccelerationType.videotoolbox => new("videotoolbox", new[] { "-hwaccel", "videotoolbox" }),
            _ => null,
        };
        if (choice == null || (supported != null && !supported(choice.Name)) || Failed(choice.Name, c, depth))
        {
            return null;
        }

        return choice with { Key = Key(choice.Name, c, depth) };
    }

    /// <summary>Whether [hwaccel] failed for [codec] at [depth] in this process (it isn't tried again).</summary>
    public static bool Failed(string hwaccel, string codec, int depth) => FailedKeys.ContainsKey(Key(hwaccel, codec, depth));

    /// <summary>Remembers, for this process, that [choice] failed: that codec is read in software from now on.</summary>
    public static void MarkFailed(HardwareChoice choice, string why) => FailedKeys[choice.Key] = why;

    /// <summary>Forgets failures (tests).</summary>
    public static void ForgetFailures() => FailedKeys.Clear();

    /// <summary>
    /// Runs [read] in hardware ([choice]), and again in software when that fails or reads no frames
    /// (only remembering the failure when software does read some, or hardware threw); [count] says
    /// how many frames a result holds. Returns the result and the hardware it used (null: software).
    /// </summary>
    public static async Task<(T Result, HardwareChoice? Used)> RunAsync<T>(HardwareChoice? choice, Func<HardwareChoice?, Task<T>> read, Func<T, int> count, Action<string>? log = null)
    {
        if (choice != null && !FailedKeys.ContainsKey(choice.Key))
        {
            T result;
            try
            {
                result = await read(choice).ConfigureAwait(false);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                MarkFailed(choice, e.Message);
                log?.Invoke($"{choice.Name} failed ({e.Message}); reading in software from now on");
                return (await read(null).ConfigureAwait(false), null);
            }

            if (count(result) > 0)
            {
                return (result, choice);
            }

            var software = await read(null).ConfigureAwait(false);
            if (count(software) > 0)
            {
                MarkFailed(choice, "no frames");
                log?.Invoke($"{choice.Name} read no frames; reading in software from now on");
            }

            return (software, null);
        }

        return (await read(null).ConfigureAwait(false), null);
    }

    private static string Key(string hwaccel, string codec, int depth) => $"{hwaccel}|{codec}|{depth}";

    private static string? Normalize(string? codec) => codec?.Trim().ToLowerInvariant() switch
    {
        null or "" => null,
        "h265" or "hevc" => "hevc",
        "mpeg2" or "mpeg2video" => "mpeg2video",
        "wmv3" or "vc1" => "vc1",
        var other => other,
    };
}

/// <summary>Hardware decoding for one file: ffmpeg's <c>-hwaccel</c> name and the input arguments.</summary>
/// <param name="Name">The <c>-hwaccel</c> used (vaapi, cuda, d3d11va, videotoolbox).</param>
/// <param name="Arguments">Input arguments, before <c>-i</c>.</param>
/// <param name="Key">What a failure is remembered under (hwaccel, codec, bit depth).</param>
public sealed record HardwareChoice(string Name, IReadOnlyList<string> Arguments, string Key = "");
