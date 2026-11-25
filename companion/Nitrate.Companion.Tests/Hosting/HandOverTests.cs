using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Nitrate.Companion.Chat;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Insights;
using Nitrate.Companion.Logic;
using Nitrate.Companion.Partners;
using Nitrate.Companion.QueryTimes;
using Nitrate.Companion.Recommendations;
using Nitrate.Companion.Sso;
using Nitrate.Companion.Together;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>What the real logic keeps only in memory reaches the next logic, and its long polls end when it's swapped out.</summary>
public sealed class HandOverTests : IDisposable
{
    private static readonly Guid Sam = Guid.Parse("00000000-0000-0000-0000-00000000000a");
    private static readonly Guid Alex = Guid.Parse("00000000-0000-0000-0000-00000000000b");
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public async Task InMemoryStateIsHandedToTheNextLogic()
    {
        using var stopping = new CancellationTokenSource();
        var first = new CompanionLogic(Context(new Dictionary<string, byte[]>(), stopping.Token));
        var s = first.Services;
        s.GetRequiredService<ChatHub>().Post("room", Sam, "Sam", ChatKind.Text, "hello", 1000, [Sam, Alex]);
        s.GetRequiredService<ChatHub>().Post("room", Alex, "Alex", ChatKind.Text, "hi", 2000);
        s.GetRequiredService<InviteHub>().Send(Sam, "Sam", [Alex], "group-1", "item", "A film", _ => true);
        s.GetRequiredService<GroupNotes>().ImportState([new BroughtState(Guid.Parse("00000000-0000-0000-0000-0000000000c1"), Guid.Parse("00000000-0000-0000-0000-0000000000c2"), Sam, DateTimeOffset.UtcNow)]);
        s.GetRequiredService<GroupNotes>().ImportAlong([new AlongState(Guid.Parse("00000000-0000-0000-0000-0000000000c1"), Guid.Parse("00000000-0000-0000-0000-0000000000c3"), Guid.Parse("00000000-0000-0000-0000-0000000000c4"), Sam, DateTimeOffset.UtcNow)]);
        s.GetRequiredService<SsoService>().ImportState([new PendingSignInState("ABCD2345", "secret", new SsoDevice("d", "TV", "Nitrate", "1"), DateTimeOffset.UtcNow.AddMinutes(5), "state", "nonce", "verifier", "https://x/cb", Sam, "sam", null)]);
        s.GetRequiredService<QueryTimeStore>().Record(Sam, "run1-1", 42, "GET /Items");
        s.GetRequiredService<BecauseStore>().Put(Sam, new BecauseFacts(DateTimeOffset.UtcNow, [new BecauseRecent(Alex, "Movie", "Seed", null, null)], [new BecauseSimilar(Alex, [Sam])]));
        s.GetRequiredService<SittingRecorder>().ImportState([new SittingState("play|item", DateTimeOffset.UtcNow.AddMinutes(-20), DateTimeOffset.UtcNow, false, 1_200_000)]);
        s.GetRequiredService<TogetherRecorder>().ImportState([new TogetherSeenState("session", Alex, [Sam, Alex], DateTimeOffset.UtcNow, "syncplay-1")]);
        s.GetRequiredService<PartnerHub>().ImportSessions([new GroupSessionState("syncplay-1", [Guid.Parse("00000000-0000-0000-0000-0000000000c2")], DateTimeOffset.UtcNow)]);
        var state = first.ExportState();
        await first.DisposeAsync();
        Assert.Equal(
            [HandOver.Because, HandOver.Brought, HandOver.Chat, HandOver.GroupSessions, HandOver.Invites, HandOver.QueryTimes, HandOver.SessionChats, HandOver.Sittings, HandOver.Sso, HandOver.Together, HandOver.WatchSessions],
            state.Keys.Order().ToArray());

        var next = new CompanionLogic(Context(state, stopping.Token));
        var n = next.Services;
        var chat = n.GetRequiredService<ChatHub>();
        var page = await chat.WaitAsync("room", -1, TimeSpan.Zero, CancellationToken.None, viewer: Alex);
        Assert.Equal(["hello", "hi"], page.Messages.Select(m => m.Text));
        Assert.Equal(2, page.Latest);
        Assert.Equal(3, chat.Post("room", Sam, "Sam", ChatKind.Text, "again", null).Seq);
        Assert.DoesNotContain((await chat.WaitAsync("room", -1, TimeSpan.Zero, CancellationToken.None, viewer: Guid.NewGuid())).Messages, m => m.Text == "hello");
        Assert.Equal("A film", Assert.Single(n.GetRequiredService<InviteHub>().For(Alex)).ItemName);
        Assert.Single(n.GetRequiredService<GroupNotes>().ExportState());
        Assert.Equal(Sam, Assert.Single(n.GetRequiredService<GroupNotes>().ExportAlong()).By);
        Assert.Equal((Sam, "sam"), n.GetRequiredService<SsoService>().Find("abcd-2345")!.User);
        Assert.Equal(42, Assert.Single(n.GetRequiredService<QueryTimeStore>().For(Sam, "run1")).Ms);
        Assert.Equal("Seed", n.GetRequiredService<BecauseStore>().Get(Sam)!.Recent[0].Name);
        Assert.Equal(1_200_000, Assert.Single(n.GetRequiredService<SittingRecorder>().ExportState()).WatchedMs);
        var seen = Assert.Single(n.GetRequiredService<TogetherRecorder>().ExportState());
        Assert.Equal([Sam, Alex], seen.Present);
        Assert.Equal("syncplay-1", seen.Group);
        var session = Assert.Single(n.GetRequiredService<PartnerHub>().ExportSessions());
        Assert.Equal(("syncplay-1", Guid.Parse("00000000-0000-0000-0000-0000000000c2")), (session.Key, Assert.Single(session.Groups)));
        await next.DisposeAsync();
    }

    [Fact]
    public async Task UnknownOrBrokenStateIsSkipped()
    {
        var state = new Dictionary<string, byte[]>
        {
            ["chat.v9"] = [1, 2, 3],
            [HandOver.Invites] = "not json"u8.ToArray(),
        };
        var logic = new CompanionLogic(Context(state, CancellationToken.None));
        Assert.Empty(logic.Services.GetRequiredService<InviteHub>().For(Alex));
        await logic.DisposeAsync();
    }

    [Fact]
    public async Task BeingSwappedOutEndsChatAndScriptWaits()
    {
        using var stopping = new CancellationTokenSource();
        var logic = new CompanionLogic(Context(new Dictionary<string, byte[]>(), stopping.Token));
        var chat = logic.Services.GetRequiredService<ChatHub>();
        chat.Post("room", Sam, "Sam", ChatKind.Text, "hello", null);
        var waiting = chat.WaitAsync("room", 1, TimeSpan.FromSeconds(30), CancellationToken.None, viewer: Sam);
        await Task.Delay(100);
        Assert.False(waiting.IsCompleted);
        await stopping.CancelAsync();
        var page = await waiting.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(page.Messages);
        Assert.Equal(1, page.Latest);
        await logic.DisposeAsync();
    }

    private LogicContext Context(IReadOnlyDictionary<string, byte[]> state, CancellationToken stopping) => new()
    {
        HostServices = NullServer.Services(_dir.Path),
        LoggerFactory = NullLoggerFactory.Instance,
        Version = "1.0.0.1",
        State = state,
        Stopping = stopping,
    };
}
