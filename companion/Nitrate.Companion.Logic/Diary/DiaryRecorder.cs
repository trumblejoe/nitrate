using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Hosting;

namespace Nitrate.Companion.Diary;

/// <summary>
/// Writes the diary as people watch, in any app: when playback of a film or episode stops having
/// reached the end (the server marks it played and resets its resume point).
/// </summary>
public class DiaryRecorder : IHostedService
{
    private readonly DiaryHub _diary;
    private readonly IUserDataManager _userData;
    private readonly Nitrate.Companion.Partners.GroupPlays _groupPlays;

    /// <summary>Initializes a new instance of the <see cref="DiaryRecorder"/> class.</summary>
    public DiaryRecorder(DiaryHub diary, IUserDataManager userData, Nitrate.Companion.Partners.GroupPlays groupPlays)
    {
        _groupPlays = groupPlays;
        _diary = diary;
        _userData = userData;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _userData.UserDataSaved += OnSaved;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _userData.UserDataSaved -= OnSaved;
        return Task.CompletedTask;
    }

    /// <summary>The library's details of a title, as the diary keeps them (null: not a film or episode).</summary>
    public static DiaryTitle? Describe(BaseItem item) => item switch
    {
        Episode e => new DiaryTitle(e.Id, "Episode", e.Name, e.SeriesId, e.SeriesName, e.ParentIndexNumber, e.IndexNumber, e.ProductionYear, Runtime(e), e.Genres ?? Array.Empty<string>()),
        Movie m => new DiaryTitle(m.Id, "Movie", m.Name, null, null, null, null, m.ProductionYear, Runtime(m), m.Genres ?? Array.Empty<string>()),
        _ => null,
    };

    private static long? Runtime(BaseItem item) => item.RunTimeTicks is { } t ? t / TimeSpan.TicksPerMillisecond : null;

    private void OnSaved(object? sender, UserDataSaveEventArgs e)
    {
        if (!(Plugin.Instance?.Configuration.EnableDiary ?? true) || e.SaveReason != UserDataSaveReason.PlaybackFinished)
        {
            return;
        }

        // Stopped at the end: played, with the resume point cleared (stopping partway keeps one).
        if (e.UserData is not { Played: true, PlaybackPositionTicks: 0 } || Describe(e.Item) is not { } title)
        {
            return;
        }

        // Watched for groups: kept as a group watch, apart from the person's own.
        _diary.Finished(e.UserId, title, _groupPlays.For(e.UserId, e.Item.Id));
    }
}
