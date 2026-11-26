using System.Collections.Concurrent;

namespace Nitrate.Companion.Images;

/// <summary>A size guide line the app sends: an image kind (for one kind of item, or any) and what's good.</summary>
/// <param name="Type">Jellyfin's image type ("Primary", "Backdrop"…).</param>
/// <param name="ItemKind">The kind of item it's for ("Episode"), or null for every other kind.</param>
/// <param name="MinWidth">Narrower than this is small.</param>
/// <param name="Ratio">Width over height it should be near (within 15%), or null when any shape will do (logos).</param>
public record SizeRule(string Type, string? ItemKind, int MinWidth, double? Ratio);

/// <summary>What the app asks the survey for. The rules are the app's (core <c>images/ImageGuide</c>).</summary>
/// <param name="LibraryId">One library, or null for every library.</param>
/// <param name="Expected">Per kind of item ("Movie"), the image types it should have.</param>
/// <param name="Sizes">The size guide.</param>
/// <param name="Only">Problems listed: "missing", "small", "shape", "gone", or null for any.</param>
/// <param name="OnlyType">Problems listed only for this image type, or null.</param>
/// <param name="Limit">Most problems listed (the total is counted anyway).</param>
public record ImageOverviewPost(
    Guid? LibraryId,
    IReadOnlyDictionary<string, string[]>? Expected,
    IReadOnlyList<SizeRule>? Sizes,
    string? Only = null,
    string? OnlyType = null,
    int Limit = 300);

/// <summary>One image of an item, as the server's database has it.</summary>
/// <param name="Type">Its type.</param>
/// <param name="Index">Its place among its type (backdrops).</param>
/// <param name="Path">Its file (or address, when not saved).</param>
/// <param name="Width">Width the database knows (0 when not known).</param>
/// <param name="Height">Height the database knows (0 when not known).</param>
/// <param name="Local">Whether it's a file on the server (not just an address).</param>
/// <param name="Modified">When it last changed.</param>
public record SurveyImage(string Type, int Index, string? Path, int Width, int Height, bool Local, DateTime Modified);

/// <summary>One item and its images.</summary>
/// <param name="Id">The item.</param>
/// <param name="Name">Its name.</param>
/// <param name="Kind">Its kind ("Movie", "Episode"…).</param>
/// <param name="SeriesName">The show, for seasons and episodes.</param>
/// <param name="Season">The season number, for episodes.</param>
/// <param name="Episode">The episode (or season) number.</param>
/// <param name="Images">Its images.</param>
public record SurveyItem(Guid Id, string Name, string Kind, string? SeriesName, int? Season, int? Episode, IReadOnlyList<SurveyImage> Images);

/// <summary>What's known of an image's file: bytes (null when gone) and its real size.</summary>
/// <param name="Bytes">The file's size, or null when the file isn't there.</param>
/// <param name="Width">Width read from the file when the database had none.</param>
/// <param name="Height">Height read from the file when the database had none.</param>
public record ImageFile(long? Bytes, int? Width, int? Height);

/// <summary>Totals for one image type.</summary>
/// <param name="Type">The type.</param>
/// <param name="Count">How many images.</param>
/// <param name="Bytes">Their files' size.</param>
/// <param name="Small">How many are narrower than the guide.</param>
/// <param name="Misshapen">How many are far from the guide's shape.</param>
/// <param name="UnknownSize">How many have no known width and height.</param>
/// <param name="NotSaved">How many are only an address (not a file on the server).</param>
/// <param name="Gone">How many point at a file that isn't there.</param>
public record TypeTotals(string Type, int Count, long Bytes, int Small, int Misshapen, int UnknownSize, int NotSaved, int Gone);

/// <summary>Totals for one kind of item.</summary>
/// <param name="Kind">The kind.</param>
/// <param name="Items">How many there are.</param>
/// <param name="Complete">How many have every expected image, none small, misshapen or gone.</param>
/// <param name="Bytes">Their images' size.</param>
/// <param name="Missing">Per expected type, how many lack it.</param>
public record KindTotals(string Kind, int Items, int Complete, long Bytes, IReadOnlyDictionary<string, int> Missing);

/// <summary>Something wrong with one image.</summary>
/// <param name="Type">Its type.</param>
/// <param name="Index">Its place among its type.</param>
/// <param name="Width">Its width, when known.</param>
/// <param name="Height">Its height, when known.</param>
/// <param name="Flaw">"small", "shape" or "gone".</param>
public record ImageFlaw(string Type, int Index, int? Width, int? Height, string Flaw);

/// <summary>An item with something missing or wrong.</summary>
/// <param name="Id">The item.</param>
/// <param name="Name">Its name.</param>
/// <param name="Kind">Its kind.</param>
/// <param name="SeriesName">The show.</param>
/// <param name="Season">The season number.</param>
/// <param name="Episode">The episode number.</param>
/// <param name="Missing">Expected types it has none of.</param>
/// <param name="Flaws">Its images' flaws.</param>
public record ImageProblem(Guid Id, string Name, string Kind, string? SeriesName, int? Season, int? Episode, IReadOnlyList<string> Missing, IReadOnlyList<ImageFlaw> Flaws);

/// <summary>One of the biggest image files.</summary>
/// <param name="Id">Its item.</param>
/// <param name="Name">The item's name.</param>
/// <param name="Kind">The item's kind.</param>
/// <param name="SeriesName">The show, for seasons and episodes.</param>
/// <param name="Season">The season number.</param>
/// <param name="Episode">The episode number.</param>
/// <param name="Type">The image's type.</param>
/// <param name="Index">Its place among its type.</param>
/// <param name="Bytes">The file's size.</param>
/// <param name="Width">Its width, when known.</param>
/// <param name="Height">Its height, when known.</param>
public record BigImage(Guid Id, string Name, string Kind, string? SeriesName, int? Season, int? Episode, string Type, int Index, long Bytes, int? Width, int? Height);

/// <summary>The survey: totals, kinds, the problems asked for and the biggest files.</summary>
/// <param name="Items">Items looked at.</param>
/// <param name="Images">Images they have.</param>
/// <param name="Bytes">All their files' size.</param>
/// <param name="Types">Per image type.</param>
/// <param name="Kinds">Per kind of item.</param>
/// <param name="Problems">Items with something missing or wrong (as asked, at most the limit).</param>
/// <param name="ProblemsTotal">How many such items there are in all.</param>
/// <param name="Largest">The biggest files.</param>
public record ImageOverview(int Items, int Images, long Bytes, IReadOnlyList<TypeTotals> Types, IReadOnlyList<KindTotals> Kinds, IReadOnlyList<ImageProblem> Problems, int ProblemsTotal, IReadOnlyList<BigImage> Largest);

/// <summary>
/// Library images overview: what every item's images are, how big their files are, and which are
/// missing, small, the wrong shape or gone, against the guide the app sends (so the rules live in
/// the apps). Reads the server's database and the files' sizes only; a file's size (and its width
/// and height when the database has none) is kept per path and change time.
/// </summary>
public static class ImageSurvey
{
    /// <summary>How far from the guide's shape an image may be (15%).</summary>
    public const double ShapeSlack = 0.15;

    /// <summary>How many of the biggest files are listed.</summary>
    public const int LargestCount = 20;

    /// <summary>The survey of [items] by [post]'s rules, files read through [file].</summary>
    public static ImageOverview Run(IEnumerable<SurveyItem> items, ImageOverviewPost post, Func<SurveyImage, ImageFile> file)
    {
        var expected = post.Expected ?? new Dictionary<string, string[]>();
        var sizes = post.Sizes ?? Array.Empty<SizeRule>();
        var types = new Dictionary<string, TypeTotals>(StringComparer.OrdinalIgnoreCase);
        var kinds = new Dictionary<string, (int Items, int Complete, long Bytes, Dictionary<string, int> Missing)>(StringComparer.OrdinalIgnoreCase);
        var problems = new List<ImageProblem>();
        var largest = new List<BigImage>();
        int itemCount = 0, imageCount = 0, problemsTotal = 0;
        long bytes = 0;
        foreach (var item in items)
        {
            itemCount++;
            var wanted = expected.FirstOrDefault(e => string.Equals(e.Key, item.Kind, StringComparison.OrdinalIgnoreCase)).Value ?? Array.Empty<string>();
            var missing = wanted.Where(t => !item.Images.Any(i => string.Equals(i.Type, t, StringComparison.OrdinalIgnoreCase))).ToList();
            var flaws = new List<ImageFlaw>();
            long itemBytes = 0;
            foreach (var image in item.Images)
            {
                imageCount++;
                var f = image.Local ? file(image) : new ImageFile(null, null, null);
                var width = image.Width > 0 ? image.Width : f.Width;
                var height = image.Height > 0 ? image.Height : f.Height;
                var gone = image.Local && f.Bytes == null;
                var rule = sizes.FirstOrDefault(r => Same(r.Type, image.Type) && Same(r.ItemKind, item.Kind))
                    ?? sizes.FirstOrDefault(r => Same(r.Type, image.Type) && r.ItemKind == null);
                var small = rule != null && width is > 0 && width < rule.MinWidth;
                var shape = rule?.Ratio is { } ratio && width is > 0 && height is > 0 && Math.Abs(((double)width.Value / height.Value / ratio) - 1) > ShapeSlack;
                if (gone)
                {
                    flaws.Add(new ImageFlaw(image.Type, image.Index, width, height, "gone"));
                }

                if (small)
                {
                    flaws.Add(new ImageFlaw(image.Type, image.Index, width, height, "small"));
                }

                if (shape)
                {
                    flaws.Add(new ImageFlaw(image.Type, image.Index, width, height, "shape"));
                }

                var size = f.Bytes ?? 0;
                itemBytes += size;
                types.TryGetValue(image.Type, out var t);
                t ??= new TypeTotals(image.Type, 0, 0, 0, 0, 0, 0, 0);
                types[image.Type] = t with
                {
                    Count = t.Count + 1,
                    Bytes = t.Bytes + size,
                    Small = t.Small + (small ? 1 : 0),
                    Misshapen = t.Misshapen + (shape ? 1 : 0),
                    UnknownSize = t.UnknownSize + (width is > 0 && height is > 0 ? 0 : 1),
                    NotSaved = t.NotSaved + (image.Local ? 0 : 1),
                    Gone = t.Gone + (gone ? 1 : 0),
                };
                if (size > 0)
                {
                    Keep(largest, new BigImage(item.Id, item.Name, item.Kind, item.SeriesName, item.Season, item.Episode, image.Type, image.Index, size, width, height));
                }
            }

            bytes += itemBytes;
            if (!kinds.TryGetValue(item.Kind, out var k))
            {
                k = (0, 0, 0, wanted.ToDictionary(w => w, _ => 0, StringComparer.OrdinalIgnoreCase));
            }

            foreach (var m in missing)
            {
                k.Missing[m] = k.Missing.GetValueOrDefault(m) + 1;
            }

            var complete = missing.Count == 0 && flaws.Count == 0;
            kinds[item.Kind] = (k.Items + 1, k.Complete + (complete ? 1 : 0), k.Bytes + itemBytes, k.Missing);
            if (complete)
            {
                continue;
            }

            var shownMissing = missing.Where(m => post.OnlyType == null || Same(m, post.OnlyType)).ToList();
            var shownFlaws = flaws.Where(f => post.OnlyType == null || Same(f.Type, post.OnlyType)).ToList();
            var counts = post.Only?.ToLowerInvariant() switch
            {
                "missing" => shownMissing.Count > 0,
                "small" or "shape" or "gone" => shownFlaws.Any(f => f.Flaw == post.Only!.ToLowerInvariant()),
                _ => shownMissing.Count > 0 || shownFlaws.Count > 0,
            };
            if (!counts)
            {
                continue;
            }

            problemsTotal++;
            problems.Add(new ImageProblem(item.Id, item.Name, item.Kind, item.SeriesName, item.Season, item.Episode, missing, flaws));
        }

        var listed = problems
            .OrderBy(p => p.SeriesName ?? p.Name, StringComparer.OrdinalIgnoreCase)
            .ThenBy(p => p.SeriesName == null ? 0 : 1)
            .ThenBy(p => p.Season ?? -1)
            .ThenBy(p => p.Kind == "Season" ? -1 : p.Episode ?? -1)
            .ThenBy(p => p.Name, StringComparer.OrdinalIgnoreCase)
            .Take(Math.Clamp(post.Limit, 0, 2000))
            .ToList();
        return new ImageOverview(
            itemCount,
            imageCount,
            bytes,
            types.Values.OrderByDescending(t => t.Bytes).ThenBy(t => t.Type).ToList(),
            kinds.Select(kv => new KindTotals(kv.Key, kv.Value.Items, kv.Value.Complete, kv.Value.Bytes, kv.Value.Missing)).OrderByDescending(k => k.Items).ToList(),
            listed,
            problemsTotal,
            largest.OrderByDescending(b => b.Bytes).ToList());
    }

    private static bool Same(string? a, string? b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    private static void Keep(List<BigImage> largest, BigImage image)
    {
        if (largest.Count < LargestCount)
        {
            largest.Add(image);
            return;
        }

        var least = 0;
        for (var i = 1; i < largest.Count; i++)
        {
            if (largest[i].Bytes < largest[least].Bytes)
            {
                least = i;
            }
        }

        if (image.Bytes > largest[least].Bytes)
        {
            largest[least] = image;
        }
    }
}

/// <summary>
/// Image files' sizes (and widths and heights the database lacks), kept per path and change time so
/// a second look at the overview reads no file again.
/// </summary>
public class ImageFiles
{
    private readonly ConcurrentDictionary<(string Path, DateTime Modified), ImageFile> _known = new();

    /// <summary>How many files are kept.</summary>
    public int Count => _known.Count;

    /// <summary>What's known of [image]'s file, read once.</summary>
    public ImageFile Of(SurveyImage image)
    {
        if (string.IsNullOrEmpty(image.Path))
        {
            return new ImageFile(null, null, null);
        }

        return _known.GetOrAdd((image.Path, image.Modified), _ => Read(image));
    }

    /// <summary>Reads a file's size, and its header when its width or height isn't known.</summary>
    public static ImageFile Read(SurveyImage image)
    {
        try
        {
            var info = new FileInfo(image.Path!);
            if (!info.Exists)
            {
                return new ImageFile(null, null, null);
            }

            if (image.Width > 0 && image.Height > 0)
            {
                return new ImageFile(info.Length, null, null);
            }

            using var stream = info.OpenRead();
            var buffer = new byte[(int)Math.Min(info.Length, Nitrate.Companion.Comics.ImageHeader.ReadBytes)];
            var read = 0;
            while (read < buffer.Length)
            {
                var n = stream.Read(buffer, read, buffer.Length - read);
                if (n == 0)
                {
                    break;
                }

                read += n;
            }

            var size = Nitrate.Companion.Comics.ImageHeader.Size(buffer.AsSpan(0, read));
            return new ImageFile(info.Length, size?.Width, size?.Height);
        }
        catch (IOException)
        {
            return new ImageFile(null, null, null);
        }
        catch (UnauthorizedAccessException)
        {
            return new ImageFile(null, null, null);
        }
    }
}
