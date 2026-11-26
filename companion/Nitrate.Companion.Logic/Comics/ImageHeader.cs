using System.Buffers.Binary;

namespace Nitrate.Companion.Comics;

/// <summary>
/// A picture's size read from the start of its file (JPEG, PNG, GIF, WebP, BMP), without decoding
/// it. Keep in step with core <c>comics/ImageSize</c>.
/// </summary>
public static class ImageHeader
{
    /// <summary>How much of a file is read to find its size (a JPEG's frame header can sit after large EXIF data).</summary>
    public const int ReadBytes = 256 * 1024;

    /// <summary>Width and height, or null when the format isn't known or the start is cut short.</summary>
    public static (int Width, int Height)? Size(ReadOnlySpan<byte> b)
    {
        if (b.Length >= 24 && b[0] == 0x89 && b[1] == (byte)'P' && b[2] == (byte)'N' && b[3] == (byte)'G')
        {
            return (BinaryPrimitives.ReadInt32BigEndian(b[16..]), BinaryPrimitives.ReadInt32BigEndian(b[20..]));
        }

        if (b.Length >= 10 && b[0] == (byte)'G' && b[1] == (byte)'I' && b[2] == (byte)'F')
        {
            return (BinaryPrimitives.ReadUInt16LittleEndian(b[6..]), BinaryPrimitives.ReadUInt16LittleEndian(b[8..]));
        }

        if (b.Length >= 26 && b[0] == (byte)'B' && b[1] == (byte)'M')
        {
            return (Math.Abs(BinaryPrimitives.ReadInt32LittleEndian(b[18..])), Math.Abs(BinaryPrimitives.ReadInt32LittleEndian(b[22..])));
        }

        if (b.Length >= 30 && b[..4].SequenceEqual("RIFF"u8) && b[8..12].SequenceEqual("WEBP"u8))
        {
            var chunk = b[12..16];
            if (chunk.SequenceEqual("VP8X"u8))
            {
                return (1 + (b[24] | (b[25] << 8) | (b[26] << 16)), 1 + (b[27] | (b[28] << 8) | (b[29] << 16)));
            }

            if (chunk.SequenceEqual("VP8L"u8))
            {
                var bits = BinaryPrimitives.ReadUInt32LittleEndian(b[21..]);
                return (1 + (int)(bits & 0x3FFF), 1 + (int)((bits >> 14) & 0x3FFF));
            }

            if (chunk.SequenceEqual("VP8 "u8))
            {
                return (BinaryPrimitives.ReadUInt16LittleEndian(b[26..]) & 0x3FFF, BinaryPrimitives.ReadUInt16LittleEndian(b[28..]) & 0x3FFF);
            }

            return null;
        }

        if (b.Length >= 4 && b[0] == 0xFF && b[1] == 0xD8)
        {
            var i = 2;
            while (i + 9 < b.Length)
            {
                if (b[i] != 0xFF)
                {
                    i++;
                    continue;
                }

                var marker = b[i + 1];
                if (marker == 0xFF)
                {
                    i++;
                    continue;
                }

                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7))
                {
                    i += 2;
                    continue;
                }

                var length = BinaryPrimitives.ReadUInt16BigEndian(b[(i + 2)..]);
                // Start-of-frame markers, apart from DHT (C4), JPG (C8) and DAC (CC).
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC)
                {
                    return (BinaryPrimitives.ReadUInt16BigEndian(b[(i + 7)..]), BinaryPrimitives.ReadUInt16BigEndian(b[(i + 5)..]));
                }

                i += 2 + length;
            }
        }

        return null;
    }

    /// <summary>
    /// Whether the picture can be see-through, from the start of its file: PNG with an alpha
    /// channel or a tRNS chunk before its image data, WebP with its alpha flag, SVG → true; JPEG,
    /// PNG without either, lossy WebP → false; null when it doesn't tell. Keep in step with core
    /// <c>comics/ImageSize.transparent</c>.
    /// </summary>
    public static bool? Transparent(ReadOnlySpan<byte> b)
    {
        if (b.Length >= 26 && b[0] == 0x89 && b[1] == (byte)'P' && b[2] == (byte)'N' && b[3] == (byte)'G')
        {
            var colour = b[25];
            if (colour == 4 || colour == 6)
            {
                return true;
            }

            var i = 8;
            while (i + 8 <= b.Length)
            {
                var length = BinaryPrimitives.ReadInt32BigEndian(b[i..]);
                var type = b.Slice(i + 4, 4);
                if (type.SequenceEqual("tRNS"u8))
                {
                    return true;
                }

                if (type.SequenceEqual("IDAT"u8) || type.SequenceEqual("IEND"u8))
                {
                    return false;
                }

                if (length < 0)
                {
                    return null;
                }

                i += 12 + length;
            }

            return null;
        }

        if (b.Length >= 30 && b[..4].SequenceEqual("RIFF"u8) && b[8..12].SequenceEqual("WEBP"u8))
        {
            var chunk = b[12..16];
            if (chunk.SequenceEqual("VP8X"u8))
            {
                return (b[20] & 0x10) != 0;
            }

            if (chunk.SequenceEqual("VP8L"u8))
            {
                return ((b[24] >> 4) & 1) == 1;
            }

            return chunk.SequenceEqual("VP8 "u8) ? false : null;
        }

        if (b.Length >= 2 && b[0] == 0xFF && b[1] == 0xD8)
        {
            return false;
        }

        var head = System.Text.Encoding.UTF8.GetString(b[..Math.Min(b.Length, 512)]).TrimStart();
        return head.StartsWith("<svg", StringComparison.Ordinal) || (head.StartsWith("<?xml", StringComparison.Ordinal) && head.Contains("<svg", StringComparison.Ordinal)) ? true : null;
    }
}
