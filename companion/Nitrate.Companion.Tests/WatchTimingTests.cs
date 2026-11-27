using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.WatchTiming;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>Timing Watched/Unwatched and each watch-state listener, then putting the listeners back as they were.</summary>
public class WatchTimingTests
{
    private static readonly Guid Admin = Guid.Parse("00000000-0000-0000-0000-0000000000a1");
    private static readonly Guid Someone = Guid.Parse("00000000-0000-0000-0000-0000000000a2");

    /// <summary>Stands in for Jellyfin's user data manager: the same event, raised as a save would.</summary>
    private sealed class Saves
    {
        public event EventHandler<UserDataSaveEventArgs>? UserDataSaved;

        public int Count => UserDataSaved?.GetInvocationList().Length ?? 0;

        public void Save(Guid user, string title) => UserDataSaved?.Invoke(this, new UserDataSaveEventArgs
        {
            UserId = user,
            Item = new Episode { Name = title, SeriesName = "The Night Reel", ParentIndexNumber = 2, IndexNumber = 5 },
            SaveReason = UserDataSaveReason.TogglePlayed,
            Keys = new List<string> { "k" },
            UserData = new MediaBrowser.Controller.Entities.UserItemData { Key = "k" },
        });
    }

    private static void Slow(object? sender, UserDataSaveEventArgs e) => Thread.Sleep(30);

    [Fact]
    public void TimesEachListenerOfTheAdminsSavesThenPutsThemBack()
    {
        var saves = new Saves();
        var order = new List<string>();
        saves.UserDataSaved += (_, _) => order.Add("first");
        saves.UserDataSaved += Slow;
        var timing = new WatchChangeTiming(saves, a => a == typeof(WatchTimingTests).Assembly ? "Test plugin" : null, TimeProvider.System);
        Assert.True(timing.Supported);

        timing.Start(Admin, TimeSpan.FromMinutes(3));
        saves.Save(Admin, "A secret title");
        // Someone else's save goes through as ever, and isn't kept.
        saves.Save(Someone, "Theirs");
        var added = 0;
        saves.UserDataSaved += (_, _) => added++;
        var report = timing.Report(Admin)!;
        var save = Assert.Single(report.Saves);
        // An episode by its show and number only.
        Assert.Equal("The Night Reel S2:E5", save.Title);
        Assert.Equal("TogglePlayed", save.Reason);
        Assert.Equal("Test plugin (WatchTimingTests)", save.Listeners[0].Name);
        Assert.True(save.Listeners[0].Ms >= 25);
        Assert.Equal(2, save.Listeners.Count);
        Assert.Null(timing.Report(Someone));

        timing.Stop();
        Assert.False(timing.Running);
        // As they were, in their order, with the one added meanwhile kept.
        Assert.Equal(3, saves.Count);
        order.Clear();
        saves.Save(Admin, "Again");
        Assert.Equal(["first"], order);
        Assert.Equal(1, added);
        // Not timing now.
        Assert.Single(timing.Report(Admin)!.Saves);
    }

    [Fact]
    public void StopsByItself()
    {
        var saves = new Saves();
        saves.UserDataSaved += Slow;
        var time = new FakeTimeProvider(DateTimeOffset.UnixEpoch);
        var timing = new WatchChangeTiming(saves, _ => null, time);
        timing.Start(Admin, TimeSpan.FromMinutes(2));
        Assert.True(timing.Running);
        time.Advance(TimeSpan.FromMinutes(3));
        Assert.False(timing.Running);
        Assert.Equal(1, saves.Count);
        Assert.Equal(nameof(Slow), saves.GetType().GetField("UserDataSaved", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.GetValue(saves) is Delegate d ? d.Method.Name : null);
    }

    [Fact]
    public void KeepsTheAdminsWatchedPresses()
    {
        var timing = new WatchChangeTiming(new Saves(), _ => null, TimeProvider.System);
        timing.RequestFinished(Admin, "POST", "/UserPlayedItems/abc", TimeSpan.FromMilliseconds(5));
        Assert.Empty(timing.Report(Admin)!.Presses);
        timing.Start(Admin, TimeSpan.FromMinutes(1));
        timing.RequestFinished(Admin, "POST", "/UserPlayedItems/abc", TimeSpan.FromMilliseconds(1900));
        timing.RequestFinished(Admin, "DELETE", "/Users/u/PlayedItems/abc", TimeSpan.FromMilliseconds(40));
        timing.RequestFinished(Admin, "GET", "/UserPlayedItems/abc", TimeSpan.FromMilliseconds(40));
        timing.RequestFinished(Someone, "POST", "/UserPlayedItems/abc", TimeSpan.FromMilliseconds(40));
        timing.RequestFinished(Admin, "POST", "/Items/abc", TimeSpan.FromMilliseconds(40));
        var presses = timing.Report(Admin)!.Presses;
        Assert.Equal([(true, 1900.0), (false, 40.0)], presses.Select(p => (p.Watched, p.Ms)));
    }
}
