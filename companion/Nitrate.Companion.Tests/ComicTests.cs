using System.Formats.Tar;
using System.IO.Compression;
using Nitrate.Companion.Comics;
using Xunit;

namespace Nitrate.Companion.Tests;

public class ComicTests
{
    private static byte[] Png(int w, int h)
    {
        var b = new byte[33];
        new byte[] { 0x89, (byte)'P', (byte)'N', (byte)'G', 13, 10, 26, 10, 0, 0, 0, 13, (byte)'I', (byte)'H', (byte)'D', (byte)'R' }.CopyTo(b, 0);
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(16), w);
        System.Buffers.Binary.BinaryPrimitives.WriteInt32BigEndian(b.AsSpan(20), h);
        return b;
    }

    private static byte[] Jpeg(int w, int h) =>
    [
        0xFF, 0xD8, 0xFF, 0xE0, 0, 4, 0, 0, // APP0, length 4
        0xFF, 0xC4, 0, 3, 0, // DHT (not a frame)
        0xFF, 0xC0, 0, 11, 8, (byte)(h >> 8), (byte)h, (byte)(w >> 8), (byte)w, 3, 0, 0, 0, 0,
    ];

    private static string Temp()
    {
        var d = Path.Combine(Path.GetTempPath(), "nitrate-comics-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(d);
        return d;
    }

    [Fact]
    public void SizesComeFromHeaders()
    {
        Assert.Equal((1200, 1800), ImageHeader.Size(Png(1200, 1800)));
        Assert.Equal((3000, 2000), ImageHeader.Size(Jpeg(3000, 2000)));
        Assert.Equal((5, 7), ImageHeader.Size(new byte[] { (byte)'G', (byte)'I', (byte)'F', (byte)'8', (byte)'9', (byte)'a', 5, 0, 7, 0 }));
        Assert.Null(ImageHeader.Size(new byte[] { 1, 2, 3 }));
    }

    [Fact]
    public void PagesInNaturalOrder() =>
        Assert.Equal(
            new[] { "a/page2.jpg", "a/page10.jpg", "b/01.png" },
            ComicPages.Order(new[] { "b/01.png", "a/page10.jpg", "__MACOSX/a/._page2.jpg", "a/page2.jpg", "a/.hidden.jpg", "ComicInfo.xml" }));

    [Fact]
    public async Task CbzIsReadInPlace()
    {
        var dir = Temp();
        var book = Path.Combine(dir, "book.cbz");
        using (var zip = ZipFile.Open(book, ZipArchiveMode.Create))
        {
            foreach (var (name, data) in new[] { ("p10.png", Png(10, 20)), ("p2.jpg", Jpeg(40, 20)) })
            {
                using var s = zip.CreateEntry(name).Open();
                s.Write(data);
            }

            using var ci = new StreamWriter(zip.CreateEntry("ComicInfo.xml").Open());
            ci.Write("<ComicInfo><Series>Moon</Series></ComicInfo>");
        }

        var store = new ComicStore(Path.Combine(dir, "cache"));
        var listing = await store.ListAsync("k", book);
        Assert.Equal("zip", listing.Format);
        Assert.Equal(new[] { "p2.jpg", "p10.png" }, listing.Pages.Select(p => p.Name));
        Assert.Equal((40, 20), (listing.Pages[0].Width, listing.Pages[0].Height));
        Assert.Contains("Moon", listing.ComicInfo);
        var page = await store.PageAsync("k", book, 1);
        Assert.Equal("image/png", page!.Value.ContentType);
        Assert.Equal(Png(10, 20), ((MemoryStream)page.Value.Data).ToArray());
        Assert.Null(await store.PageAsync("k", book, 2));
        Assert.Equal(0, store.CachedBytes());
    }

    [Fact]
    public async Task OtherArchivesAreUnpackedOnce()
    {
        var dir = Temp();
        var book = Path.Combine(dir, "book.cbt");
        await using (var f = File.Create(book))
        await using (var tar = new TarWriter(f))
        {
            foreach (var (name, data) in new[] { ("2.png", Png(30, 10)), ("1.png", Png(10, 30)) })
            {
                var e = new PaxTarEntry(TarEntryType.RegularFile, name) { DataStream = new MemoryStream(data) };
                await tar.WriteEntryAsync(e);
            }
        }

        Assert.Equal("tar", ComicStore.Format(book));
        var store = new ComicStore(Path.Combine(dir, "cache"));
        var listing = await store.ListAsync("k", book);
        Assert.Equal("tar", listing.Format);
        Assert.Equal(new[] { "1.png", "2.png" }, listing.Pages.Select(p => p.Name));
        Assert.Equal(30, listing.Pages[1].Width);
        var page = await store.PageAsync("k", book, 1);
        using (var s = page!.Value.Data)
        using (var m = new MemoryStream())
        {
            s.CopyTo(m);
            Assert.Equal(Png(30, 10), m.ToArray());
        }

        Assert.True(store.CachedBytes() > 0);
        Assert.Single(Directory.GetDirectories(Path.Combine(dir, "cache")));
    }

    [Fact]
    public async Task OldestUnpackedGoFirst()
    {
        var dir = Temp();
        var store = new ComicStore(Path.Combine(dir, "cache"), maxBytes: 1);
        foreach (var k in new[] { "a", "b" })
        {
            var book = Path.Combine(dir, k + ".cbt");
            await using (var f = File.Create(book))
            await using (var tar = new TarWriter(f))
            {
                await tar.WriteEntryAsync(new PaxTarEntry(TarEntryType.RegularFile, "1.png") { DataStream = new MemoryStream(Png(1, 1)) });
            }

            await store.ListAsync(k, book);
        }

        var left = Directory.GetDirectories(Path.Combine(dir, "cache")).Select(Path.GetFileName).ToList();
        Assert.Single(left);
        Assert.StartsWith("b-", left[0]);
    }

    [Fact]
    public void PdfAndEpubAreTakenWhole()
    {
        var dir = Temp();
        var pdf = Path.Combine(dir, "a.pdf");
        File.WriteAllText(pdf, "%PDF-1.7\n");
        Assert.Equal("pdf", ComicStore.Format(pdf));
        var epub = Path.Combine(dir, "a.epub");
        using (var zip = ZipFile.Open(epub, ZipArchiveMode.Create))
        {
            using var s = new StreamWriter(zip.CreateEntry("mimetype", CompressionLevel.NoCompression).Open());
            s.Write("application/epub+zip");
        }

        Assert.Equal("epub", ComicStore.Format(epub));
    }
}
