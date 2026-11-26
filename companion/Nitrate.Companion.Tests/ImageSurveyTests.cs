using Nitrate.Companion.Images;
using Xunit;

namespace Nitrate.Companion.Tests;

public class ImageSurveyTests
{
    private static readonly DateTime When = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private static readonly ImageOverviewPost Guide = new(
        null,
        new Dictionary<string, string[]>
        {
            ["Movie"] = new[] { "Primary", "Backdrop", "Logo" },
            ["Episode"] = new[] { "Primary" },
        },
        new[]
        {
            new SizeRule("Primary", null, 1000, 2.0 / 3),
            new SizeRule("Primary", "Episode", 1280, 16.0 / 9),
            new SizeRule("Backdrop", null, 1920, 16.0 / 9),
            new SizeRule("Logo", null, 800, null, Transparent: true),
        });

    private static Guid G(int n) => new(n, 0, 0, new byte[8]);

    private static SurveyImage Img(string type, int w, int h, string path, int index = 0, bool local = true) => new(type, index, path, w, h, local, When);

    private static ImageFile Files(SurveyImage i, bool see = false) => i.Path switch
    {
        "gone.jpg" => new ImageFile(null, null, null),
        "flat.jpg" => new ImageFile(900, null, null, see ? false : null),
        "clear.png" => new ImageFile(900, null, null, see ? true : null),
        "nosize.jpg" => new ImageFile(500, 400, 600),
        _ => new ImageFile(i.Path!.Length * 100L, null, null),
    };

    [Fact]
    public void CountsTypesBytesAndProblems()
    {
        var items = new[]
        {
            // Complete: poster, backdrop and logo, all good.
            new SurveyItem(G(1), "Alpha", "Movie", null, null, null, new[] { Img("Primary", 2000, 3000, "a.jpg"), Img("Backdrop", 3840, 2160, "ab.jpg"), Img("Logo", 1600, 620, "al.png") }),
            // Missing a logo, small poster, landscape backdrop… fine, square backdrop: wrong shape.
            new SurveyItem(G(2), "Beta", "Movie", null, null, null, new[] { Img("Primary", 600, 900, "b.jpg"), Img("Backdrop", 2000, 2000, "bb.jpg") }),
            // An episode whose still is gone, another with no image at all.
            new SurveyItem(G(3), "Pilot", "Episode", "Show", 1, 1, new[] { Img("Primary", 1920, 1080, "gone.jpg") }),
            new SurveyItem(G(4), "Two", "Episode", "Show", 1, 2, Array.Empty<SurveyImage>()),
        };

        var o = ImageSurvey.Run(items, Guide, Files);

        Assert.Equal(4, o.Items);
        Assert.Equal(6, o.Images);
        Assert.Equal(("a.jpg".Length + "ab.jpg".Length + "al.png".Length + "b.jpg".Length + "bb.jpg".Length) * 100L, o.Bytes);
        var primary = o.Types.Single(t => t.Type == "Primary");
        Assert.Equal(3, primary.Count);
        Assert.Equal(1, primary.Small);
        Assert.Equal(1, primary.Gone);
        Assert.Equal(1, o.Types.Single(t => t.Type == "Backdrop").Misshapen);

        var movies = o.Kinds.Single(k => k.Kind == "Movie");
        Assert.Equal(2, movies.Items);
        Assert.Equal(1, movies.Complete);
        Assert.Equal(1, movies.Missing["Logo"]);
        Assert.Equal(0, movies.Missing["Primary"]);
        Assert.Equal(1, o.Kinds.Single(k => k.Kind == "Episode").Missing["Primary"]);

        Assert.Equal(3, o.ProblemsTotal);
        var beta = o.Problems.Single(p => p.Name == "Beta");
        Assert.Equal(new[] { "Logo" }, beta.Missing);
        Assert.Contains(beta.Flaws, f => f is { Type: "Primary", Flaw: "small" });
        Assert.Contains(beta.Flaws, f => f is { Type: "Backdrop", Flaw: "shape" });
        Assert.Contains(o.Problems.Single(p => p.Name == "Pilot").Flaws, f => f.Flaw == "gone");
        // Shows' episodes in order after the films.
        Assert.Equal(new[] { "Beta", "Pilot", "Two" }, o.Problems.Select(p => p.Name));
        Assert.Equal("ab.jpg".Length * 100L, o.Largest[0].Bytes);
    }

    [Fact]
    public void EpisodeStillsUseTheirOwnGuideAndUnknownSizesAreRead()
    {
        var items = new[]
        {
            // 1280×720 is good for an episode though narrow for a poster; the database had no size for the second.
            new SurveyItem(G(1), "Pilot", "Episode", "Show", 1, 1, new[] { Img("Primary", 1280, 720, "e.jpg") }),
            new SurveyItem(G(2), "Gamma", "Movie", null, null, null, new[] { Img("Primary", 0, 0, "nosize.jpg") }),
        };

        var o = ImageSurvey.Run(items, Guide with { Only = "small" }, Files);

        Assert.Equal(0, o.Types.Single(t => t.Type == "Primary").UnknownSize);
        var gamma = Assert.Single(o.Problems);
        Assert.Equal("Gamma", gamma.Name);
        Assert.Contains(gamma.Flaws, f => f is { Flaw: "small", Width: 400, Height: 600 });
    }

    [Fact]
    public void OnlyAndOnlyTypeNarrowTheListNotTheTotals()
    {
        var items = new[]
        {
            new SurveyItem(G(1), "A", "Movie", null, null, null, new[] { Img("Primary", 2000, 3000, "a.jpg") }),
            new SurveyItem(G(2), "B", "Movie", null, null, null, new[] { Img("Primary", 500, 750, "b.jpg"), Img("Backdrop", 3840, 2160, "bb.jpg"), Img("Logo", 900, 300, "l.png") }),
        };

        var missingLogos = ImageSurvey.Run(items, Guide with { Only = "missing", OnlyType = "Logo" }, Files);
        Assert.Equal(new[] { "A" }, missingLogos.Problems.Select(p => p.Name));
        Assert.Equal(1, missingLogos.ProblemsTotal);
        Assert.Equal(2, missingLogos.Kinds.Single().Items);

        var limited = ImageSurvey.Run(items, Guide with { Limit = 1 }, Files);
        Assert.Single(limited.Problems);
        Assert.Equal(2, limited.ProblemsTotal);
    }

    [Fact]
    public void AddressesAreCountedButNotRead()
    {
        var read = 0;
        var items = new[] { new SurveyItem(G(1), "A", "Movie", null, null, null, new[] { Img("Primary", 2000, 3000, "https://example.org/p.jpg", local: false) }) };

        var o = ImageSurvey.Run(items, Guide, (i, see) => { read++; return Files(i, see); });

        Assert.Equal(0, read);
        Assert.Equal(1, o.Types.Single().NotSaved);
        Assert.Equal(0, o.Types.Single().Gone);
    }

    [Fact]
    public void FilesAreReadOncePerChange()
    {
        var dir = Directory.CreateTempSubdirectory();
        try
        {
            var path = Path.Combine(dir.FullName, "p.png");
            var png = new byte[40];
            new byte[] { 0x89, (byte)'P', (byte)'N', (byte)'G' }.CopyTo(png, 0);
            png[18] = 0x03; png[19] = 0xE8; // width 1000
            png[22] = 0x05; png[23] = 0xDC; // height 1500
            File.WriteAllBytes(path, png);
            var files = new ImageFiles();
            var image = Img("Primary", 0, 0, path);

            Assert.Equal(new ImageFile(40, 1000, 1500), files.Of(image));
            File.Delete(path);
            Assert.Equal(new ImageFile(40, 1000, 1500), files.Of(image));
            Assert.Equal(new ImageFile(null, null, null), files.Of(image with { Modified = When.AddDays(1) }));
        }
        finally
        {
            dir.Delete(true);
        }
    }

    [Fact]
    public void LogosThatArentSeeThroughAreOpaque()
    {
        var items = new[]
        {
            new SurveyItem(G(1), "A", "Movie", null, null, null, new[] { Img("Primary", 2000, 3000, "a.jpg"), Img("Backdrop", 3840, 2160, "b.jpg"), Img("Logo", 1600, 620, "flat.jpg") }),
            new SurveyItem(G(2), "B", "Movie", null, null, null, new[] { Img("Primary", 2000, 3000, "c.jpg"), Img("Backdrop", 3840, 2160, "d.jpg"), Img("Logo", 1600, 620, "clear.png") }),
        };

        var o = ImageSurvey.Run(items, Guide with { Only = "opaque" }, Files);

        Assert.Equal(1, o.Types.Single(t => t.Type == "Logo").Opaque);
        Assert.Equal(0, o.Types.Single(t => t.Type == "Primary").Opaque);
        var a = Assert.Single(o.Problems);
        Assert.Equal("A", a.Name);
        Assert.Contains(a.Flaws, f => f is { Type: "Logo", Flaw: "opaque" });
    }

    [Fact]
    public void SeeThroughIsReadFromTheHeader()
    {
        static byte[] Png(byte colour, params string[] chunks)
        {
            var b = new List<byte> { 0x89, (byte)'P', (byte)'N', (byte)'G', 13, 10, 26, 10, 0, 0, 0, 13 };
            b.AddRange("IHDR"u8.ToArray());
            b.AddRange(new byte[] { 0, 0, 3, 32, 0, 0, 1, 54, 8, colour, 0, 0, 0, 0, 0, 0, 0 });
            foreach (var c in chunks)
            {
                b.AddRange(new byte[] { 0, 0, 0, 6 });
                b.AddRange(System.Text.Encoding.ASCII.GetBytes(c));
                b.AddRange(new byte[10]);
            }

            return b.ToArray();
        }

        Assert.True(Nitrate.Companion.Comics.ImageHeader.Transparent(Png(6, "IDAT")));
        Assert.True(Nitrate.Companion.Comics.ImageHeader.Transparent(Png(3, "PLTE", "tRNS", "IDAT")));
        Assert.False(Nitrate.Companion.Comics.ImageHeader.Transparent(Png(2, "IDAT")));
        Assert.Null(Nitrate.Companion.Comics.ImageHeader.Transparent(Png(2, "pHYs")));
        Assert.False(Nitrate.Companion.Comics.ImageHeader.Transparent(new byte[] { 0xFF, 0xD8, 0xFF }));
    }
}
