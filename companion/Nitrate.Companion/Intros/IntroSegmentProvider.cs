using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model;
using MediaBrowser.Model.MediaSegments;

namespace Nitrate.Companion.Intros;

/// <summary>
/// Hands Jellyfin the intros <see cref="IntroScanner"/> found, as Intro media segments, so every
/// app (Nitrate's and Jellyfin's own) offers Skip Intro. Listed under each library's Media segment
/// providers, where it can be switched off.
/// </summary>
public class IntroSegmentProvider : IMediaSegmentProvider
{
    private readonly IntroStore _store;

    /// <summary>Initializes a new instance of the <see cref="IntroSegmentProvider"/> class.</summary>
    public IntroSegmentProvider(IntroStore store) => _store = store;

    /// <inheritdoc />
    public string Name => "Nitrate Companion intros";

    /// <inheritdoc />
    public ValueTask<bool> Supports(BaseItem item) => ValueTask.FromResult(item is Episode);

    /// <inheritdoc />
    public Task<IReadOnlyList<MediaSegmentDto>> GetMediaSegments(MediaSegmentGenerationRequest request, CancellationToken cancellationToken)
    {
        IReadOnlyList<MediaSegmentDto> segments = _store.Get(request.ItemId)?.Intro is { } intro
            ? new[]
            {
                new MediaSegmentDto
                {
                    ItemId = request.ItemId,
                    Type = MediaSegmentType.Intro,
                    StartTicks = intro.StartMs * TimeSpan.TicksPerMillisecond,
                    EndTicks = intro.EndMs * TimeSpan.TicksPerMillisecond,
                },
            }
            : Array.Empty<MediaSegmentDto>();
        return Task.FromResult(segments);
    }

    /// <inheritdoc />
    public Task CleanupExtractedData(Guid itemId, CancellationToken cancellationToken) => Task.CompletedTask;
}
