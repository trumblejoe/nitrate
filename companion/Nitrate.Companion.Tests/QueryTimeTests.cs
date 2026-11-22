using System.Security.Claims;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.QueryTimes;
using Xunit;

namespace Nitrate.Companion.Tests;

public class QueryTimeTests
{
    private readonly FakeTimeProvider _time = new(DateTimeOffset.FromUnixTimeMilliseconds(1_000));
    private readonly Guid _sam = Guid.NewGuid();
    private readonly Guid _kim = Guid.NewGuid();

    [Fact]
    public void EachUserReadsOnlyTheirOwnRun()
    {
        var store = new QueryTimeStore(_time);
        store.Record(_sam, "abc123-1", 40);
        store.Record(_sam, "abc123-2", 900);
        store.Record(_sam, "zzz999-1", 5);
        store.Record(_kim, "abc123-3", 7);
        var sams = store.For(_sam, "abc123");
        Assert.Equal(new[] { "abc123-1", "abc123-2" }, sams.Select(t => t.Tag));
        Assert.Equal(900, sams[1].Ms);
        Assert.Single(store.For(_kim, "abc123"));
        Assert.Empty(store.For(Guid.NewGuid(), "abc123"));
    }

    [Fact]
    public void OddTagsAreIgnoredAndOldTimesLapse()
    {
        var store = new QueryTimeStore(_time);
        store.Record(_sam, "Not a tag!", 1);
        store.Record(_sam, new string('a', 41), 1);
        store.Record(_sam, "run-1", 10);
        Assert.Single(store.For(_sam, "run"));
        _time.Advance(QueryTimeStore.Keep + TimeSpan.FromMinutes(1));
        Assert.Empty(store.For(_sam, "run"));
        for (var i = 0; i < QueryTimeStore.PerUser + 10; i++)
        {
            store.Record(_sam, $"run-{i}", i);
        }

        Assert.Equal(QueryTimeStore.PerUser, store.For(_sam, "run").Count);
    }

    [Fact]
    public async Task TheMiddlewareTimesTaggedRequestsOfTheSignedInUser()
    {
        var store = new QueryTimeStore(_time);
        var middleware = new QueryTimingMiddleware(
            ctx =>
            {
                // The server's sign-in check runs further down the pipeline.
                ctx.User = new ClaimsPrincipal(new ClaimsIdentity(new[] { new Claim("Jellyfin-UserId", _sam.ToString()) }, "test"));
                return Task.Delay(30);
            },
            store);
        var tagged = new DefaultHttpContext();
        tagged.Request.Headers[QueryTimeStore.Header] = "run7-1";
        await middleware.InvokeAsync(tagged);
        await middleware.InvokeAsync(new DefaultHttpContext());
        Assert.True(store.Active);
        var times = store.For(_sam, "run7");
        Assert.Single(times);
        Assert.True(times[0].Ms >= 25, $"{times[0].Ms} ms");
    }
}
