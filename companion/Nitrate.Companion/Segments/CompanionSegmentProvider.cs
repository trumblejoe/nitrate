using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model;
using MediaBrowser.Model.MediaSegments;
using Nitrate.Companion.Credits;
using Nitrate.Companion.Intros;

namespace Nitrate.Companion.Segments;

/// <summary>
/// Hands Jellyfin what the Companion's nightly tasks found, as media segments, so every app
/// (Nitrate's and Jellyfin's own) can skip them: an episode's Intro and Recap ("Find intros"), and
/// a title's final credits as an Outro ("Find scenes in the credits"). The Outro is only the last
/// stretch of credits, after any scene in them, so no app skipping it misses a scene. Listed
/// under each library's Media segment providers, where it can be switched off.
/// </summary>
public class CompanionSegmentProvider : IMediaSegmentProvider
{
    private readonly IntroStore _intros;
    private readonly CreditStore _credits;

    /// <summary>Initializes a new instance of the <see cref="CompanionSegmentProvider"/> class.</summary>
    public CompanionSegmentProvider(IntroStore intros, CreditStore credits)
    {
        _intros = intros;
        _credits = credits;
    }

    /// <inheritdoc />
    public string Name => "Nitrate Companion";

    /// <inheritdoc />
    public ValueTask<bool> Supports(BaseItem item) => ValueTask.FromResult(item is Episode or Movie);

    /// <inheritdoc />
    public Task<IReadOnlyList<MediaSegmentDto>> GetMediaSegments(MediaSegmentGenerationRequest request, CancellationToken cancellationToken) =>
        Task.FromResult(For(request.ItemId, _intros.Get(request.ItemId), _credits.Get(request.ItemId)));

    /// <summary>The segments for [itemId] from what was found.</summary>
    public static IReadOnlyList<MediaSegmentDto> For(Guid itemId, IntroResult? intros, CreditResult? credits)
    {
        MediaSegmentDto Segment(MediaSegmentType type, long startMs, long endMs) => new()
        {
            ItemId = itemId,
            Type = type,
            StartTicks = startMs * TimeSpan.TicksPerMillisecond,
            EndTicks = endMs * TimeSpan.TicksPerMillisecond,
        };

        var segments = new List<MediaSegmentDto>();
        if (intros?.Recap is { } recap)
        {
            segments.Add(Segment(MediaSegmentType.Recap, recap.StartMs, recap.EndMs));
        }

        if (intros?.Intro is { } intro)
        {
            segments.Add(Segment(MediaSegmentType.Intro, intro.StartMs, intro.EndMs));
        }

        if (credits is { OutroStartMs: { } start, OutroEndMs: { } end } && end > start)
        {
            segments.Add(Segment(MediaSegmentType.Outro, start, end));
        }

        return segments;
    }

    /// <inheritdoc />
    public Task CleanupExtractedData(Guid itemId, CancellationToken cancellationToken) => Task.CompletedTask;
}
