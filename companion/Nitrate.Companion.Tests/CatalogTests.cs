using System.Net;
using Nitrate.Companion.Catalogs;
using Xunit;

namespace Nitrate.Companion.Tests;

public class CatalogTests
{
    private static CatalogSettings Opds(string url = "http://books.lan:25600/opds/v1.2/catalog") =>
        new() { Id = "c1", Name = "Komga", Url = url, User = "home", Password = "secret" };

    private static string Temp()
    {
        var d = Path.Combine(Path.GetTempPath(), "nitrate-catalogs-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(d);
        return d;
    }

    /// <summary>Answers from a table; records what was asked and with which sign-in.</summary>
    private sealed class Fake : HttpMessageHandler
    {
        public readonly List<(string Url, string? Auth)> Asked = [];
        public readonly Dictionary<string, Func<HttpResponseMessage>> Answers = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!.ToString();
            Asked.Add((url, request.Headers.Authorization?.ToString()));
            return Task.FromResult(Answers.TryGetValue(url, out var a) ? a() : new HttpResponseMessage(HttpStatusCode.NotFound));
        }
    }

    [Fact]
    public void OnlyTheCatalogsOwnServerIsReached()
    {
        var c = Opds();
        Assert.True(CatalogAccess.Allows(c, "http://books.lan:25600/api/v1/books/7/file", out _));
        Assert.True(CatalogAccess.Allows(c, "http://BOOKS.lan:25600/opds/v1.2/series", out _));
        Assert.False(CatalogAccess.Allows(c, "http://books.lan:8096/System/Info", out _));
        Assert.False(CatalogAccess.Allows(c, "https://books.lan:25600/opds", out _));
        Assert.False(CatalogAccess.Allows(c, "http://169.254.169.254/latest/meta-data", out _));
        Assert.False(CatalogAccess.Allows(c, "http://evil@books.lan:25600/opds", out _));
        Assert.False(CatalogAccess.Allows(c, "file:///etc/passwd", out _));
        Assert.False(CatalogAccess.Allows(c, "/opds/relative", out _));
    }

    [Fact]
    public void AppsNeverSeeSignInDetails()
    {
        var info = CatalogAccess.Public(new CatalogSettings { Id = "c2", Name = " CLU ", Url = "http://clu:5000", Kind = "clu", Token = "tok" });
        Assert.Equal(new CatalogInfo("c2", "CLU", "http://clu:5000", "CLU"), info);
        Assert.Equal("Bearer tok", CatalogAccess.Authorization(new CatalogSettings { Token = " tok ", User = "x" }));
        Assert.Equal("Basic aG9tZTpzZWNyZXQ=", CatalogAccess.Authorization(Opds()));
        Assert.Null(CatalogAccess.Authorization(new CatalogSettings()));
        // Unfinished entries aren't offered.
        Assert.Single(CatalogAccess.Usable([Opds(), new CatalogSettings { Id = "x", Name = "No address" }, new CatalogSettings { Id = "y", Name = "Bad", Url = "ftp://x" }]));
    }

    [Fact]
    public async Task FetchesWithTheSignInAndFollowsRedirectsOnlyAtHome()
    {
        var fake = new Fake();
        fake.Answers["http://books.lan:25600/opds/v1.2/catalog"] = () => new HttpResponseMessage(HttpStatusCode.Found) { Headers = { Location = new Uri("/opds/v1.2/home", UriKind.Relative) } };
        fake.Answers["http://books.lan:25600/opds/v1.2/home"] = () => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<feed/>") };
        fake.Answers["http://books.lan:25600/away"] = () => new HttpResponseMessage(HttpStatusCode.Redirect) { Headers = { Location = new Uri("http://elsewhere/") } };
        var fetcher = new CatalogFetcher(new HttpClient(fake), Temp());

        using (var r = await fetcher.GetAsync(Opds(), "http://books.lan:25600/opds/v1.2/catalog", default))
        {
            Assert.Equal("<feed/>", await r.Content.ReadAsStringAsync());
        }

        Assert.All(fake.Asked, a => Assert.Equal("Basic aG9tZTpzZWNyZXQ=", a.Auth));
        var off = await Assert.ThrowsAsync<CatalogFetchException>(() => fetcher.GetAsync(Opds(), "http://books.lan:25600/away", default));
        Assert.Equal(400, off.Status);
        Assert.DoesNotContain(fake.Asked, a => a.Url.StartsWith("http://elsewhere", StringComparison.Ordinal));
    }

    [Fact]
    public async Task BooksAreFetchedOnceAndKeptWithinTheLimit()
    {
        var fake = new Fake();
        foreach (var n in new[] { 1, 2, 3 })
        {
            fake.Answers[$"http://books.lan:25600/book/{n}"] = () => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(new byte[400]) };
        }

        var dir = Temp();
        var fetcher = new CatalogFetcher(new HttpClient(fake), dir, maxBytes: 900);
        var (first, key) = await fetcher.BookAsync(Opds(), "http://books.lan:25600/book/1", default);
        var again = await fetcher.BookAsync(Opds(), "http://books.lan:25600/book/1", default);
        Assert.Equal(first, again.Path);
        Assert.StartsWith("cat-", key);
        Assert.Single(fake.Asked);

        File.SetLastWriteTimeUtc(first, DateTime.UtcNow.AddHours(-1));
        await fetcher.BookAsync(Opds(), "http://books.lan:25600/book/2", default);
        await fetcher.BookAsync(Opds(), "http://books.lan:25600/book/3", default);
        Assert.False(File.Exists(first));
        Assert.Equal(2, Directory.GetFiles(dir).Length);

        await Assert.ThrowsAsync<CatalogFetchException>(() => fetcher.BookAsync(Opds(), "http://books.lan:25600/missing", default));
    }

    [Fact]
    public void PositionsArePerPerson()
    {
        var positions = new CatalogPositions(Temp());
        var sam = Guid.NewGuid();
        var kim = Guid.NewGuid();
        positions.Set(sam, "c1", "urn:book:7", 12);
        positions.Set(kim, "c1", "urn:book:7", 3);
        positions.Set(sam, "c1", "urn:book:7", 14);
        Assert.Equal(14, positions.Get(sam, "c1", "urn:book:7"));
        Assert.Equal(3, positions.Get(kim, "c1", "urn:book:7"));
        Assert.Null(positions.Get(sam, "c2", "urn:book:7"));
    }
}
