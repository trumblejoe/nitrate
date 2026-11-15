using System.Text.Json;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Scripts;
using Xunit;

namespace Nitrate.Companion.Tests;

public class ScriptHubTests
{
    private static readonly Guid Owner = Guid.NewGuid();
    private static readonly Guid Writer = Guid.NewGuid();
    private static readonly Guid Stranger = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly MemoryScriptStorage _storage = new();
    private readonly ScriptHub _hub;

    public ScriptHubTests() => _hub = new ScriptHub(_storage, _time);

    private static JsonElement Ops(string json) => JsonDocument.Parse(json).RootElement.Clone();

    private StoredScript Shared(string text = "Title: Night Shift\n\nINT. DINER - NIGHT")
    {
        var s = _hub.Create(Owner, text);
        return _hub.SetMembers(s.Id, Owner, new[] { Writer });
    }

    [Fact]
    public void OnlyTheOwnerAndInvitedPeopleCanOpenIt()
    {
        var s = Shared();
        Assert.Equal("Title: Night Shift\n\nINT. DINER - NIGHT", _hub.Get(s.Id, Writer).Text);
        Assert.Single(_hub.List(Writer));
        Assert.Empty(_hub.List(Stranger));
        var e = Assert.Throws<ScriptException>(() => _hub.Get(s.Id, Stranger));
        Assert.Equal(ScriptError.NotFound, e.Error);
        Assert.Equal(ScriptError.NotOwner, Assert.Throws<ScriptException>(() => _hub.SetMembers(s.Id, Writer, new[] { Stranger })).Error);
        Assert.Equal(ScriptError.NotOwner, Assert.Throws<ScriptException>(() => _hub.Delete(s.Id, Writer)).Error);
    }

    [Fact]
    public void ChangesAreTakenOnlyOnTheLatestVersion()
    {
        var s = Shared("Sam walks in.");
        var first = _hub.Edit(s.Id, Writer, "e1", 0, Ops("""[3, " slowly", 10]"""), 10);
        Assert.Equal((EditOutcome.Accepted, 1L), first);
        Assert.Equal("Sam slowly walks in.", _hub.Get(s.Id, Owner).Text);

        // Made on version 0 by the owner at the same time: refused until rebased.
        var stale = _hub.Edit(s.Id, Owner, "o1", 0, Ops("""[13, " Jo looks up."]"""), null);
        Assert.Equal((EditOutcome.Stale, 1L), stale);
        var rebased = _hub.Edit(s.Id, Owner, "o1", 1, Ops("""[20, " Jo looks up."]"""), null);
        Assert.Equal((EditOutcome.Accepted, 2L), rebased);
        Assert.Equal("Sam slowly walks in. Jo looks up.", _hub.Get(s.Id, Writer).Text);

        // Sent again after a lost answer: not applied twice.
        Assert.Equal((EditOutcome.Accepted, 1L), _hub.Edit(s.Id, Writer, "e1", 0, Ops("""[3, " slowly", 10]"""), null));
        Assert.Equal(2, _hub.Get(s.Id, Writer).Version);
    }

    [Fact]
    public void ChangesThatDoNotFitAreRefused()
    {
        var s = Shared("abc");
        Assert.Equal(ScriptError.Invalid, Assert.Throws<ScriptException>(() => _hub.Edit(s.Id, Writer, "x", 0, Ops("[2]"), null)).Error);
        Assert.Equal(ScriptError.Invalid, Assert.Throws<ScriptException>(() => _hub.Edit(s.Id, Writer, "y", 0, Ops("[5]"), null)).Error);
        Assert.Equal(ScriptError.Invalid, Assert.Throws<ScriptException>(() => _hub.Edit(s.Id, Writer, "z", 0, Ops("""{"a":1}"""), null)).Error);
        Assert.Equal(ScriptError.Invalid, Assert.Throws<ScriptException>(() => _hub.Edit(s.Id, Writer, "w", 0, Ops("[0, 3]"), null)).Error);
        Assert.Equal("ab!", TextOps.Apply("abc", Ops("""[2, "!", -1]""")));
    }

    [Fact]
    public async Task OthersWaitingGetTheChangeAndWhereTheCursorIs()
    {
        var s = Shared("abc");
        var waiting = _hub.WaitAsync(s.Id, Owner, 0, null, TimeSpan.FromSeconds(30), default);
        Assert.False(waiting.IsCompleted);
        _hub.Edit(s.Id, Writer, "e1", 0, Ops("""[3, "d"]"""), 4);
        var page = await waiting.WaitAsync(TimeSpan.FromSeconds(5));
        var edit = Assert.Single(page.Edits);
        Assert.Equal(1, edit.Version);
        Assert.Equal(Writer, edit.UserId);
        var here = Assert.Single(page.Presence);
        Assert.Equal((Writer, 4, 1L), (here.UserId, here.Cursor, here.Version));

        // Someone only moving their cursor answers too, once past what the poller has seen.
        var next = _hub.WaitAsync(s.Id, Owner, 1, page.Stamp, TimeSpan.FromSeconds(30), default);
        _hub.SetPresence(s.Id, Writer, 1, 1);
        var moved = await next.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Empty(moved.Edits);
        Assert.Equal(1, Assert.Single(moved.Presence).Cursor);

        // People who go quiet leave.
        _time.Advance(ScriptHub.PresenceLifetime + TimeSpan.FromSeconds(1));
        Assert.Empty((await _hub.WaitAsync(s.Id, Owner, 1, null, TimeSpan.Zero, default)).Presence);
    }

    [Fact]
    public async Task AppsTooFarBehindAreToldToLoadAgain()
    {
        var s = Shared("");
        for (var i = 0; i < ScriptHub.KeptEdits + 5; i++)
        {
            _hub.Edit(s.Id, Writer, $"e{i}", i, Ops(i == 0 ? """["x"]""" : $$"""[{{i}}, "x"]"""), null);
        }

        var e = await Assert.ThrowsAsync<ScriptException>(() => _hub.WaitAsync(s.Id, Owner, 1, null, TimeSpan.Zero, default));
        Assert.Equal(ScriptError.Gone, e.Error);
        Assert.Equal(5, (await _hub.WaitAsync(s.Id, Owner, ScriptHub.KeptEdits, null, TimeSpan.Zero, default)).Edits.Count);
    }

    [Fact]
    public void MembersLeaveTheOwnerDeletesAndItAllSurvivesARestart()
    {
        var s = Shared("keep me");
        _hub.Edit(s.Id, Writer, "e1", 0, Ops("""[7, "!"]"""), null);

        var restarted = new ScriptHub(_storage, _time);
        var back = restarted.Get(s.Id, Writer);
        Assert.Equal(("keep me!", 1L), (back.Text, back.Version));
        Assert.Equal("e1", Assert.Single(back.Edits).EditId);

        restarted.Leave(s.Id, Writer);
        Assert.Empty(restarted.List(Writer));
        Assert.Equal(ScriptError.Invalid, Assert.Throws<ScriptException>(() => restarted.Leave(s.Id, Owner)).Error);
        restarted.Delete(s.Id, Owner);
        Assert.Empty(_storage.Saved);
    }

    [Fact]
    public void TitlesComeFromTheTitlePageOrFirstLine()
    {
        Assert.Equal("Night Shift", TextOps.TitleOf("Title: Night Shift\nAuthor: Me"));
        Assert.Equal("INT. DINER - NIGHT", TextOps.TitleOf("\n\nINT. DINER - NIGHT\n"));
        Assert.Equal("Untitled", TextOps.TitleOf("  \n"));
    }
}
