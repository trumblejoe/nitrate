using System.Collections.Concurrent;
using System.IO.Compression;
using System.Text.Json;
using SharpCompress.Archives;

namespace Nitrate.Companion.Comics;

/// <summary>One page of a book: its entry name, picture size (0 when unknown) and bytes.</summary>
public record ComicPageInfo(string Name, int Width, int Height, long Size);

/// <summary>
/// A book's pages as the apps read them. <paramref name="Format"/>: "zip" (CBZ), "rar" (CBR),
/// "7z" (CB7), "tar" (CBT), "pdf" or "epub"; pdf and epub have no pages here (the apps take the
/// whole file).
/// </summary>
public record ComicListing(string Format, IReadOnlyList<ComicPageInfo> Pages, string? ComicInfo);

/// <summary>
/// Serves books page by page. A CBZ is read in place (a zip finds any entry at once); anything
/// else (CBR, which is often solid and can only be read from the start, CB7, CBT) is unpacked once
/// into the server's cache folder, which keeps the books used most recently within its limit.
/// </summary>
public sealed class ComicStore
{
    /// <summary>The unpacked books kept by default.</summary>
    public const long DefaultMaxBytes = 4L * 1024 * 1024 * 1024;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly string _cacheDir;
    private readonly long _maxBytes;
    private readonly ConcurrentDictionary<string, ComicListing> _zips = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _unpacking = new();

    /// <summary>Initializes a new instance of the <see cref="ComicStore"/> class.</summary>
    public ComicStore(string cacheDir, long maxBytes = DefaultMaxBytes)
    {
        _cacheDir = cacheDir;
        _maxBytes = maxBytes;
    }

    /// <summary>What kind of file this is, from its first bytes.</summary>
    public static string Format(string path)
    {
        Span<byte> head = stackalloc byte[512];
        int n;
        using (var f = File.OpenRead(path))
        {
            n = f.ReadAtLeast(head, head.Length, throwOnEndOfStream: false);
        }

        var b = head[..n];
        if (b.StartsWith("PK"u8))
        {
            // An EPUB is a zip whose first entry is "mimetype" saying so.
            return b.Length > 58 && b[30..38].SequenceEqual("mimetype"u8) && b[38..58].SequenceEqual("application/epub+zip"u8) ? "epub" : "zip";
        }

        if (b.StartsWith("Rar!"u8))
        {
            return "rar";
        }

        if (b.StartsWith(new byte[] { 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C }))
        {
            return "7z";
        }

        if (b.StartsWith("%PDF"u8))
        {
            return "pdf";
        }

        return b.Length >= 262 && b[257..262].SequenceEqual("ustar"u8) ? "tar" : "unknown";
    }

    /// <summary>The book's pages. <paramref name="key"/> names this file's version (item and modification time).</summary>
    public async Task<ComicListing> ListAsync(string key, string path, CancellationToken cancel = default)
    {
        var format = Format(path);
        switch (format)
        {
            case "zip":
                return _zips.GetOrAdd(key + "|" + File.GetLastWriteTimeUtc(path).Ticks, _ => ListZip(path));
            case "pdf":
            case "epub":
            case "unknown":
                return new ComicListing(format, [], null);
            default:
                var dir = await UnpackAsync(key, path, format, cancel).ConfigureAwait(false);
                return Read(dir) ?? throw new InvalidDataException("The book couldn't be unpacked.");
        }
    }

    /// <summary>Page <paramref name="index"/>'s bytes and content type, or null when there's no such page.</summary>
    public async Task<(Stream Data, string ContentType)?> PageAsync(string key, string path, int index, CancellationToken cancel = default)
    {
        var format = Format(path);
        if (format is "pdf" or "epub" or "unknown")
        {
            return null;
        }

        if (format == "zip")
        {
            var listing = await ListAsync(key, path, cancel).ConfigureAwait(false);
            if (index < 0 || index >= listing.Pages.Count)
            {
                return null;
            }

            var name = listing.Pages[index].Name;
            using var zip = ZipFile.OpenRead(path);
            var entry = zip.GetEntry(name) ?? throw new FileNotFoundException(name);
            var copy = new MemoryStream((int)entry.Length);
            await using (var s = entry.Open())
            {
                await s.CopyToAsync(copy, cancel).ConfigureAwait(false);
            }

            copy.Position = 0;
            return (copy, ComicPages.ContentType(name));
        }

        var dir = await UnpackAsync(key, path, format, cancel).ConfigureAwait(false);
        var pages = Read(dir)?.Pages ?? [];
        if (index < 0 || index >= pages.Count)
        {
            return null;
        }

        return (File.OpenRead(Path.Combine(dir, PageFile(index, pages[index].Name))), ComicPages.ContentType(pages[index].Name));
    }

    /// <summary>How much the unpacked books take.</summary>
    public long CachedBytes() => Directory.Exists(_cacheDir)
        ? new DirectoryInfo(_cacheDir).EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => f.Length)
        : 0;

    private static ComicListing ListZip(string path)
    {
        using var zip = ZipFile.OpenRead(path);
        var files = zip.Entries.Where(e => !e.FullName.EndsWith('/')).ToList();
        var order = ComicPages.Order(files.Select(e => e.FullName));
        var buffer = new byte[ImageHeader.ReadBytes];
        var pages = order.Select(name =>
        {
            var entry = zip.GetEntry(name)!;
            using var s = entry.Open();
            var n = s.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);
            var size = ImageHeader.Size(buffer.AsSpan(0, n));
            return new ComicPageInfo(name, size?.Width ?? 0, size?.Height ?? 0, entry.Length);
        }).ToList();
        string? info = null;
        if (files.FirstOrDefault(e => e.Name.Equals("ComicInfo.xml", StringComparison.OrdinalIgnoreCase)) is { } ci)
        {
            using var r = new StreamReader(ci.Open());
            info = r.ReadToEnd();
        }

        return new ComicListing("zip", pages, info);
    }

    private static string PageFile(int index, string name) => $"page-{index:D4}{Path.GetExtension(name).ToLowerInvariant()}";

    private static void Touch(string dir)
    {
        try
        {
            Directory.SetLastWriteTimeUtc(dir, DateTime.UtcNow);
        }
        catch (IOException)
        {
        }
    }

    private static ComicListing? Read(string dir)
    {
        var file = Path.Combine(dir, "listing.json");
        if (!File.Exists(file))
        {
            return null;
        }

        Touch(dir);
        return JsonSerializer.Deserialize<ComicListing>(File.ReadAllText(file), Json);
    }

    private string Dir(string key) => Path.Combine(_cacheDir, string.Concat(key.Split(Path.GetInvalidFileNameChars())));

    /// <summary>
    /// Unpacks every page once, reading the archive from start to end (the only way into a solid
    /// RAR), into the cache; concurrent asks for the same book wait for the one doing it.
    /// </summary>
    private async Task<string> UnpackAsync(string key, string path, string format, CancellationToken cancel)
    {
        var version = key + "-" + File.GetLastWriteTimeUtc(path).Ticks;
        var dir = Dir(version);
        if (File.Exists(Path.Combine(dir, "listing.json")))
        {
            return dir;
        }

        var gate = _unpacking.GetOrAdd(version, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancel).ConfigureAwait(false);
        try
        {
            if (File.Exists(Path.Combine(dir, "listing.json")))
            {
                return dir;
            }

            // Older versions of this book go.
            if (Directory.Exists(_cacheDir))
            {
                foreach (var old in Directory.GetDirectories(_cacheDir, Path.GetFileName(Dir(key)) + "-*"))
                {
                    Directory.Delete(old, true);
                }
            }

            var part = dir + ".part";
            if (Directory.Exists(part))
            {
                Directory.Delete(part, true);
            }

            Directory.CreateDirectory(part);
            await Task.Run(() => Unpack(path, format, part), cancel).ConfigureAwait(false);
            Directory.Move(part, dir);
            Prune(keep: dir);
            return dir;
        }
        finally
        {
            gate.Release();
            _unpacking.TryRemove(version, out _);
        }
    }

    private static void Unpack(string path, string format, string dir)
    {
        using var archive = ArchiveFactory.OpenArchive(new FileInfo(path));
        var names = archive.Entries.Where(e => !e.IsDirectory && e.Key is not null).Select(e => e.Key!).ToList();
        var order = ComicPages.Order(names);
        var index = order.Select((n, i) => (n, i)).ToDictionary(p => p.n, p => p.i);
        var sizes = new ComicPageInfo[order.Count];
        string? info = null;
        var buffer = new byte[ImageHeader.ReadBytes];
        void Take(string name, Func<Stream> open)
        {
            if (index.TryGetValue(name, out var i))
            {
                var file = Path.Combine(dir, PageFile(i, name));
                using (var s = open())
                using (var o = File.Create(file))
                {
                    s.CopyTo(o);
                }

                using var r = File.OpenRead(file);
                var n = r.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);
                var size = ImageHeader.Size(buffer.AsSpan(0, n));
                sizes[i] = new ComicPageInfo(name, size?.Width ?? 0, size?.Height ?? 0, r.Length);
            }
            else if (Path.GetFileName(name.Replace('\\', '/')).Equals("ComicInfo.xml", StringComparison.OrdinalIgnoreCase))
            {
                using var t = new StreamReader(open());
                info = t.ReadToEnd();
            }
        }

        if (archive.IsSolid || format == "7z")
        {
            // Solid: each entry can only be reached by reading those before it, so all in one pass.
            using var reader = archive.ExtractAllEntries();
            while (reader.MoveToNextEntry())
            {
                if (!reader.Entry.IsDirectory && reader.Entry.Key is { } name)
                {
                    Take(name, reader.OpenEntryStream);
                }
            }
        }
        else
        {
            foreach (var entry in archive.Entries)
            {
                if (!entry.IsDirectory && entry.Key is { } name)
                {
                    Take(name, entry.OpenEntryStream);
                }
            }
        }

        var pages = sizes.Select((p, i) => p ?? new ComicPageInfo(order[i], 0, 0, 0)).ToList();
        File.WriteAllText(Path.Combine(dir, "listing.json"), JsonSerializer.Serialize(new ComicListing(format, pages, info), Json));
    }

    /// <summary>Keeps the unpacked books within the limit, the ones used longest ago going first.</summary>
    private void Prune(string keep)
    {
        var books = new DirectoryInfo(_cacheDir).GetDirectories()
            .Select(d => (Dir: d, Size: d.EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => f.Length)))
            .OrderBy(d => d.Dir.LastWriteTimeUtc)
            .ToList();
        var total = books.Sum(b => b.Size);
        foreach (var (d, size) in books)
        {
            if (total <= _maxBytes)
            {
                break;
            }

            if (d.FullName == Path.GetFullPath(keep))
            {
                continue;
            }

            d.Delete(true);
            total -= size;
        }
    }
}
