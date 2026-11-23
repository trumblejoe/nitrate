using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace Nitrate.Companion.Catalogs;

/// <summary>A catalog refused, couldn't be reached, or answered something it shouldn't.</summary>
public class CatalogFetchException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="CatalogFetchException"/> class.</summary>
    public CatalogFetchException(string message, int status = 502)
        : base(message) => Status = status;

    /// <summary>Gets the HTTP status to answer the app with.</summary>
    public int Status { get; }
}

/// <summary>
/// Fetches from a catalog with its sign-in, only ever on its own server (redirects are followed
/// by hand, and only there), and keeps whole books fetched for reading in the cache folder (the
/// most recently read within its limit) so they can be served page by page.
/// </summary>
public sealed class CatalogFetcher
{
    /// <summary>Books kept by default.</summary>
    public const long DefaultMaxBytes = 2L * 1024 * 1024 * 1024;

    private const int MaxRedirects = 5;
    private readonly HttpClient _http;
    private readonly string _bookDir;
    private readonly long _maxBytes;
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _fetching = new();

    /// <summary>Initializes a new instance of the <see cref="CatalogFetcher"/> class.</summary>
    /// <param name="http">A client that doesn't follow redirects by itself.</param>
    /// <param name="bookDir">Where whole books are kept.</param>
    /// <param name="maxBytes">How much they may take.</param>
    public CatalogFetcher(HttpClient http, string bookDir, long maxBytes = DefaultMaxBytes)
    {
        _http = http;
        _bookDir = bookDir;
        _maxBytes = maxBytes;
    }

    /// <summary>A client for <see cref="CatalogFetcher"/>: redirects left to it, a generous timeout for big files.</summary>
    public static HttpClient NewClient() => new(new SocketsHttpHandler { AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.All })
    {
        Timeout = TimeSpan.FromMinutes(10),
    };

    /// <summary>
    /// GETs <paramref name="url"/> from <paramref name="catalog"/> (headers read, body still to
    /// come). The caller disposes the response. Anything off the catalog's server is refused.
    /// </summary>
    public async Task<HttpResponseMessage> GetAsync(CatalogSettings catalog, string url, CancellationToken cancel)
    {
        var current = url;
        for (var hop = 0; ; hop++)
        {
            if (!CatalogAccess.Allows(catalog, current, out var target))
            {
                throw new CatalogFetchException("That address isn't on the catalog's server.", 400);
            }

            using var request = new HttpRequestMessage(HttpMethod.Get, target);
            if (CatalogAccess.Authorization(catalog) is { } auth)
            {
                request.Headers.TryAddWithoutValidation("Authorization", auth);
            }

            HttpResponseMessage response;
            try
            {
                response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
            }
            catch (HttpRequestException e)
            {
                throw new CatalogFetchException("The catalog couldn't be reached: " + e.Message);
            }
            catch (TaskCanceledException) when (!cancel.IsCancellationRequested)
            {
                throw new CatalogFetchException("The catalog took too long to answer.", 504);
            }

            var status = (int)response.StatusCode;
            if (status is >= 300 and < 400 && response.Headers.Location is { } location)
            {
                response.Dispose();
                if (hop >= MaxRedirects)
                {
                    throw new CatalogFetchException("The catalog sent the request round in circles.");
                }

                current = new Uri(target, location).ToString();
                continue;
            }

            return response;
        }
    }

    /// <summary>
    /// The whole book at <paramref name="url"/>, fetched once into the cache folder: its local path
    /// and the key it's kept under.
    /// </summary>
    public async Task<(string Path, string Key)> BookAsync(CatalogSettings catalog, string url, CancellationToken cancel)
    {
        var key = "cat-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(catalog.Id + "\n" + url)))[..32].ToLowerInvariant();
        var file = Path.Combine(_bookDir, key);
        if (File.Exists(file))
        {
            File.SetLastWriteTimeUtc(file, DateTime.UtcNow);
            return (file, key);
        }

        var gate = _fetching.GetOrAdd(key, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancel).ConfigureAwait(false);
        try
        {
            if (File.Exists(file))
            {
                return (file, key);
            }

            Directory.CreateDirectory(_bookDir);
            using var response = await GetAsync(catalog, url, cancel).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                throw new CatalogFetchException($"The catalog answered {(int)response.StatusCode} for this book.", (int)response.StatusCode is 401 or 403 or 404 ? (int)response.StatusCode : 502);
            }

            var part = file + ".part";
            await using (var output = File.Create(part))
            {
                await response.Content.CopyToAsync(output, cancel).ConfigureAwait(false);
            }

            File.Move(part, file, overwrite: true);
            Prune(keep: file);
            return (file, key);
        }
        finally
        {
            gate.Release();
        }
    }

    /// <summary>Keeps the fetched books within the limit: the ones read longest ago go first.</summary>
    private void Prune(string keep)
    {
        var books = new DirectoryInfo(_bookDir).GetFiles().Where(f => !f.Name.EndsWith(".part", StringComparison.Ordinal)).OrderBy(f => f.LastWriteTimeUtc).ToList();
        var total = books.Sum(f => f.Length);
        foreach (var f in books)
        {
            if (total <= _maxBytes)
            {
                break;
            }

            if (f.FullName == keep)
            {
                continue;
            }

            total -= f.Length;
            try
            {
                f.Delete();
            }
            catch (IOException)
            {
            }
        }
    }
}
