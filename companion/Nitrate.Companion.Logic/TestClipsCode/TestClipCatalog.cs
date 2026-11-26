using System.Reflection;

namespace Nitrate.Companion.TestClipsCode;

/// <summary>What a check clip tests: the TV's picture or the receiver's sound.</summary>
public enum TestClipKind
{
    /// <summary>An HDR or Dolby Vision picture: does the TV switch?</summary>
    Picture,

    /// <summary>A sound format: does the receiver show it, and does every speaker play?</summary>
    Sound,

    /// <summary>For Nitrate's playback test, played by the app itself (not offered in the TV and receiver check).</summary>
    Playback,
}

/// <summary>One check clip shipped inside the plugin.</summary>
/// <param name="Id">Its id (the file name without extension).</param>
/// <param name="Title">What it is, e.g. "HDR10 · 4K".</param>
/// <param name="Kind">Picture or sound.</param>
/// <param name="Format">The format a device would name it by: "HDR10", "HLG", "DOVI", "eac3", "truehd"…</param>
/// <param name="LookFor">What to look for on the TV or receiver.</param>
/// <param name="ContentType">Its MIME type.</param>
/// <param name="Size">Its size in bytes.</param>
public record TestClip(string Id, string Title, TestClipKind Kind, string Format, string LookFor, string ContentType, long Size);

/// <summary>
/// The TV and receiver check clips (tools/generate-badge-clips.sh), embedded in the plugin so any
/// household device can play them through the server and see whether its TV shows the HDR or
/// Dolby Vision badge, and its receiver the sound format, without needing a film that has them.
/// </summary>
public static class TestClipCatalog
{
    private const string Prefix = "Nitrate.Companion.TestClips.";

    private static readonly (string File, string Title, TestClipKind Kind, string Format, string LookFor)[] Known =
    [
        ("hdr10.mp4", "HDR10 · 4K", TestClipKind.Picture, "HDR10", "The TV shows HDR (or HDR10), or switches to its HDR picture mode."),
        ("dolby_vision_81.mp4", "Dolby Vision 8.1 · 4K", TestClipKind.Picture, "DOVI", "A Dolby Vision TV shows Dolby Vision; other HDR TVs show HDR10."),
        ("dolby_vision_5.mp4", "Dolby Vision 5 · 4K", TestClipKind.Picture, "DOVI", "Dolby Vision with normal colours. Green or purple colours mean it isn't processed as Dolby Vision."),
        ("hlg.mp4", "HLG · 4K", TestClipKind.Picture, "HLG", "The TV shows HLG (or HDR), or switches to its HDR picture mode."),
        ("dolby_digital_51.mkv", "Dolby Digital 5.1", TestClipKind.Sound, "ac3", "The receiver shows Dolby Digital, and each speaker plays when it's named."),
        ("dolby_digital_plus_51.mkv", "Dolby Digital Plus 5.1", TestClipKind.Sound, "eac3", "The receiver shows Dolby Digital Plus (DD+), and each speaker plays when it's named."),
        ("dolby_truehd_51.mkv", "Dolby TrueHD 5.1", TestClipKind.Sound, "truehd", "The receiver shows Dolby TrueHD, and each speaker plays when it's named."),
        ("dts_51.mkv", "DTS 5.1", TestClipKind.Sound, "dts", "The receiver shows DTS, and each speaker plays when it's named."),
        ("pcm_71.mkv", "7.1 lossless (PCM)", TestClipKind.Sound, "pcm", "The receiver shows multichannel PCM, and all eight speakers play when named."),
        ("dolby_vision_7_fel_heavy.mkv", "Dolby Vision 7 FEL · 4K, disc-like", TestClipKind.Playback, "DOVI",
            "Played by Nitrate's playback test: dual-layer Dolby Vision at disc-like bitrate, converted and as its base layer."),
    ];

    // The clips are embedded in the shell (they never change, so logic updates stay small).
    private static Assembly Assembly => typeof(Plugin).Assembly;

    /// <summary>The clips this build carries.</summary>
    public static IReadOnlyList<TestClip> All()
    {
        var names = Assembly.GetManifestResourceNames().ToHashSet(StringComparer.Ordinal);
        var clips = new List<TestClip>();
        foreach (var (file, title, kind, format, lookFor) in Known)
        {
            if (!names.Contains(Prefix + file))
            {
                continue;
            }

            using var stream = Assembly.GetManifestResourceStream(Prefix + file);
            clips.Add(new TestClip(Path.GetFileNameWithoutExtension(file), title, kind, format, lookFor, ContentTypeOf(file), stream?.Length ?? 0));
        }

        return clips;
    }

    /// <summary>Opens a clip by its id, or null when there's no such clip.</summary>
    public static (Stream Stream, string ContentType)? Open(string id)
    {
        var file = Known.Select(k => k.File).FirstOrDefault(f => Path.GetFileNameWithoutExtension(f) == id);
        if (file is null)
        {
            return null;
        }

        var stream = Assembly.GetManifestResourceStream(Prefix + file);
        return stream is null ? null : (stream, ContentTypeOf(file));
    }

    private static string ContentTypeOf(string file) => file.EndsWith(".mkv", StringComparison.Ordinal) ? "video/x-matroska" : "video/mp4";
}
