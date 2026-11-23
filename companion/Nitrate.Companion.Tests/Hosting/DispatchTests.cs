using System.Net;
using System.Text.RegularExpressions;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>
/// The shell's dispatcher and the logic's router, in a real server, answer exactly as MVC does for
/// the same controller actions: statuses, content types, bodies (JSON casing, ProblemDetails,
/// validation), auth, files and ranges.
/// </summary>
public sealed partial class DispatchTests : IAsyncLifetime
{
    private static readonly Guid Some = Guid.Parse("6f9619ff-8b86-d011-b42d-00cf4fc964ff");
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();
    private Nitrate.Companion.Hosting.LogicHost _host = null!;
    private TestServer _server = null!;

    /// <summary>The requests compared, as (method, path under the probe, user, admin, body, content type, range).</summary>
    public static TheoryData<string, string, string?, bool, string?, string, string?> Cases => new()
    {
        { "GET", "Probe/Object", "sam", false, null, "", null },
        { "GET", "Probe/List", "sam", false, null, "", null },
        { "GET", "Probe/Dict", "sam", false, null, "", null },
        { "GET", "Probe/Anonymous", "sam", false, null, "", null },
        { "GET", "Probe/Missing", "sam", false, null, "", null },
        { "GET", "Probe/Problem", "sam", false, null, "", null },
        { "GET", "Probe/Forbid", "sam", false, null, "", null },
        { "GET", "Probe/Status", "sam", false, null, "", null },
        { "GET", "Probe/Unauthorized", "sam", false, null, "", null },
        { "POST", "Probe/Accepted", "sam", false, null, "", null },
        { "POST", "Probe/Conflict", "sam", false, null, "", null },
        { "DELETE", $"Probe/Item/{Some}", "sam", false, null, "", null },
        { "DELETE", $"Probe/Item/{Guid.Empty}", "sam", false, null, "", null },
        { "GET", "Probe/Item/Literal", "sam", false, null, "", null },
        { "GET", "Probe/item/literal", "sam", false, null, "", null },
        { "GET", "Probe/Item/abc", "sam", false, null, "", null },
        { "GET", $"Probe/Query?name=a&since=5&flag=true&other_name=z&id={Some}", "sam", false, null, "", null },
        { "GET", $"Probe/Query?name=a&since=5&id={Some:N}&count=9", "sam", false, null, "", null },
        { "GET", "Probe/Query?since=5", "sam", false, null, "", null },
        { "GET", "Probe/Query?name=a&since=abc", "sam", false, null, "", null },
        { "GET", "Probe/Query?name=a&since=", "sam", false, null, "", null },
        { "GET", "Probe/Query?name=&since=1", "sam", false, null, "", null },
        { "GET", "Probe/Query?name=a&since=1&count=99999999999", "sam", false, null, "", null },
        { "GET", $"Probe/Route/{Some}/7", "sam", false, null, "", null },
        { "GET", "Probe/Route/nope/7", "sam", false, null, "", null },
        { "POST", "Probe/Body", "sam", false, "{\"Name\":\"n\",\"Count\":2,\"Items\":[{\"Label\":\"abc\"}]}", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "{\"name\":\"lower\"}", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "{nope", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "{\"Count\":1}", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "{\"Name\":\"n\",\"Items\":[{\"Label\":null}]}", "application/json", null },
        { "POST", "Probe/Body", "sam", false, "{\"Name\":\"n\"}", "text/plain", null },
        { "POST", "Probe/Body", "sam", false, "{\"Name\":\"n\"}", "application/json; charset=utf-8", null },
        { "POST", "Probe/Optional", "sam", false, null, "", null },
        { "POST", "Probe/Optional", "sam", false, "{\"Name\":\"given\"}", "application/json", null },
        { "PUT", "Probe/Dict", "sam", false, "{\"A\":\"1\",\"B\":null}", "application/json", null },
        { "GET", "Probe/Null", "sam", false, null, "", null },
        { "GET", "Probe/Async", "sam", false, null, "", null },
        { "GET", "Probe/File", "sam", false, null, "", null },
        { "GET", "Probe/File", "sam", false, null, "", "bytes=10-19" },
        { "GET", "Probe/File", "sam", false, null, "", "bytes=5000-" },
        { "GET", "Probe/Picture", "sam", false, null, "", null },
        { "GET", "Probe/Page", "sam", false, null, "", null },
        { "GET", "Probe/Redirect", "sam", false, null, "", null },
        { "GET", "Probe/Empty", "sam", false, null, "", null },
        { "GET", "Probe/Open", null, false, null, "", null },
        { "GET", "Probe/Object", null, false, null, "", null },
        { "GET", "Probe/Admin", "sam", false, null, "", null },
        { "GET", "Probe/Admin", "sam", true, null, "", null },
        { "GET", "Probe/Admin", null, false, null, "", null },
        { "GET", "Probe/Me", "sam", false, null, "", null },
        { "GET", "Bare/Missing", "sam", false, null, "", null },
        { "GET", "Bare/List", "sam", false, null, "", null },
        { "GET", "Probe/Accepted", "sam", false, null, "", null },
        { "GET", "Probe/Nope", "sam", false, null, "", null },
    };

    /// <inheritdoc />
    public async Task InitializeAsync()
    {
        (_host, _) = HostingKit.Host(_dir, HostingKit.Package("1.0.0.1"));
        await _host.StartAsync(CancellationToken.None);
        _server = await TestServer.StartAsync(_host);
    }

    /// <inheritdoc />
    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        await _host.DisposeAsync();
        _dir.Dispose();
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public async Task TheShellAnswersAsMvcDoes(string method, string path, string? user, bool admin, string? body, string contentType, string? range)
    {
        var direct = await Send($"/Direct/{path}");
        var shell = await Send($"/Nitrate/{path}");
        Assert.Equal(direct.Status, shell.Status);
        Assert.Equal(direct.ContentType, shell.ContentType);
        Assert.True(direct.Body == shell.Body, $"direct: {direct.Body}\nshell:  {shell.Body}");
        Assert.Equal(direct.Headers, shell.Headers);

        async Task<(HttpStatusCode Status, string? ContentType, string Body, string Headers)> Send(string url)
        {
            using var request = Requests.As(new HttpMethod(method), url, user, admin, body, contentType);
            if (range is not null)
            {
                request.Headers.Add("Range", range);
            }

            using var response = await _server.Client.SendAsync(request);
            var text = await response.Content.ReadAsStringAsync();
            var headers = string.Join(
                "|",
                new[] { "Content-Range", "Accept-Ranges", "Location", "Cache-Control", "WWW-Authenticate" }
                    .Select(h => h + "=" + string.Join(",", response.Headers.TryGetValues(h, out var v) ? v : response.Content.Headers.TryGetValues(h, out var c) ? c : [])));
            return (response.StatusCode, response.Content.Headers.ContentType?.ToString(), TraceId().Replace(text, "\"traceId\":\"*\""), headers);
        }
    }

    [Fact]
    public async Task AnswersAreTheLogicsOwnJsonInPascalCase()
    {
        using var response = await _server.Client.SendAsync(Requests.As(HttpMethod.Get, "/Nitrate/Probe/Object"));
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("{\"Name\":\"x\",\"Count\":3,\"Numbers\":[1,2],\"Kind\":\"Two\",\"At\":\"2023-11-14T22:13:20+00:00\"}", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task WithoutALogicTheApiIsUnavailable()
    {
        await using var empty = HostingKit.Temp() is var dir ? new NoLogic(dir) : null!;
        await empty.Host.StartAsync(CancellationToken.None);
        await using var server = await TestServer.StartAsync(empty.Host);
        using var response = await server.Client.SendAsync(Requests.As(HttpMethod.Get, "/Nitrate/Probe/Object"));
        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
    }

    [GeneratedRegex("\"traceId\":\"[^\"]*\"")]
    private static partial Regex TraceId();

    private sealed class NoLogic : IAsyncDisposable
    {
        private readonly HostingKit.TempDir _dir;

        public NoLogic(HostingKit.TempDir dir)
        {
            _dir = dir;
            Host = HostingKit.Host(dir, null).Host;
        }

        public Nitrate.Companion.Hosting.LogicHost Host { get; }

        public async ValueTask DisposeAsync()
        {
            await Host.DisposeAsync();
            _dir.Dispose();
        }
    }
}
