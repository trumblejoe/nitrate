using System.Text.RegularExpressions;

namespace Nitrate.Companion.Comics;

/// <summary>Which of an archive's entries are pages, and their order. Keep in step with core <c>comics/ComicPages</c>.</summary>
public static partial class ComicPages
{
    /// <summary>File extensions read as pages.</summary>
    public static readonly IReadOnlySet<string> ImageExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "jxl",
    };

    /// <summary>The page images among the entry paths, in reading order (numbers compared as numbers), without hidden files or macOS leftovers.</summary>
    public static List<string> Order(IEnumerable<string> entries)
    {
        var pages = entries.Where(IsPage).ToList();
        pages.Sort(Natural);
        return pages;
    }

    /// <summary>True for an image entry that isn't hidden or a macOS leftover.</summary>
    public static bool IsPage(string entry)
    {
        var e = entry.Replace('\\', '/');
        var name = e[(e.LastIndexOf('/') + 1)..];
        var dot = name.LastIndexOf('.');
        return dot > 0 && ImageExtensions.Contains(name[(dot + 1)..]) && !name.StartsWith('.') && !e.Contains("__MACOSX", StringComparison.Ordinal);
    }

    /// <summary>Natural order: digit runs by value, the rest without case.</summary>
    public static int Natural(string a, string b)
    {
        var x = Chunks().Matches(a.ToLowerInvariant().Replace('\\', '/'));
        var y = Chunks().Matches(b.ToLowerInvariant().Replace('\\', '/'));
        for (var i = 0; i < Math.Min(x.Count, y.Count); i++)
        {
            var p = x[i].Value;
            var q = y[i].Value;
            int c;
            if (char.IsDigit(p[0]) && char.IsDigit(q[0]))
            {
                var pt = p.TrimStart('0');
                var qt = q.TrimStart('0');
                c = pt.Length.CompareTo(qt.Length);
                if (c == 0)
                {
                    c = string.CompareOrdinal(pt, qt);
                }
            }
            else
            {
                c = string.CompareOrdinal(p, q);
            }

            if (c != 0)
            {
                return c;
            }
        }

        return x.Count.CompareTo(y.Count);
    }

    /// <summary>The content type for a page entry.</summary>
    public static string ContentType(string entry) => Path.GetExtension(entry).ToLowerInvariant() switch
    {
        ".png" => "image/png",
        ".webp" => "image/webp",
        ".gif" => "image/gif",
        ".avif" => "image/avif",
        ".bmp" => "image/bmp",
        ".jxl" => "image/jxl",
        _ => "image/jpeg",
    };

    [GeneratedRegex(@"\d+|\D+")]
    private static partial Regex Chunks();
}
