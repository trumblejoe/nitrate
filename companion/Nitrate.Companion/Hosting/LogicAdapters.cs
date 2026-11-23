using System.Diagnostics;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Model;
using MediaBrowser.Model.MediaSegments;
using MediaBrowser.Model.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;

namespace Nitrate.Companion.Hosting
{
    /// <summary>
    /// A scheduled task Jellyfin knows from the start, run by the current logic. Jellyfin keys a
    /// task's triggers and history by its type's full name, so each adapter keeps the name the task
    /// had before the split (never rename them). A run cut short by a swap carries on in the new logic.
    /// </summary>
    public abstract class LogicTask : IScheduledTask
    {
        private readonly LogicHost _host;

        /// <summary>Initializes a new instance of the <see cref="LogicTask"/> class.</summary>
        protected LogicTask(LogicHost host) => _host = host;

        /// <inheritdoc />
        public abstract string Key { get; }

        /// <inheritdoc />
        public string Name => Current()?.Name ?? FallbackName;

        /// <inheritdoc />
        public string Description => Current()?.Description ?? FallbackDescription;

        /// <inheritdoc />
        public string Category => "Nitrate Companion";

        /// <summary>Gets the key the logic's own task is found by.</summary>
        public string TaskKey => GetType().FullName!;

        /// <summary>Gets the name shown before a logic runs.</summary>
        protected abstract string FallbackName { get; }

        /// <summary>Gets the description shown before a logic runs.</summary>
        protected abstract string FallbackDescription { get; }

        /// <summary>Gets the time of day the task runs by default.</summary>
        protected abstract TimeSpan DefaultTime { get; }

        /// <inheritdoc />
        public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() =>
            Current()?.GetDefaultTriggers().ToList() ?? [new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = DefaultTime.Ticks }];

        /// <inheritdoc />
        public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
        {
            while (true)
            {
                using var lease = await _host.AcquireAsync(cancellationToken).ConfigureAwait(false);
                if (lease is null || !lease.Logic.Tasks.TryGetValue(TaskKey, out var task))
                {
                    return;
                }

                using var run = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, lease.Stopping);
                try
                {
                    await task.ExecuteAsync(progress, run.Token).ConfigureAwait(false);
                    return;
                }
                catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && lease.Stopping.IsCancellationRequested)
                {
                    // Swapped out mid-run: the next logic picks up (results are kept per file, so done work stays done).
                }
            }
        }

        private IScheduledTask? Current()
        {
            using var lease = _host.TryAcquire();
            return lease is not null && lease.Logic.Tasks.TryGetValue(TaskKey, out var task) ? task : null;
        }
    }

    /// <summary>The segment provider Jellyfin lists per library ("Nitrate Companion"), answered by the current logic.</summary>
    public sealed class LogicSegments : IMediaSegmentProvider
    {
        private readonly LogicHost _host;

        /// <summary>Initializes a new instance of the <see cref="LogicSegments"/> class.</summary>
        public LogicSegments(LogicHost host) => _host = host;

        /// <inheritdoc />
        public string Name => "Nitrate Companion";

        /// <inheritdoc />
        public async ValueTask<bool> Supports(BaseItem item)
        {
            using var lease = await _host.AcquireAsync(CancellationToken.None).ConfigureAwait(false);
            return lease?.Logic.Segments is { } segments ? await segments.Supports(item).ConfigureAwait(false) : item is Episode or Movie;
        }

        /// <inheritdoc />
        public async Task<IReadOnlyList<MediaSegmentDto>> GetMediaSegments(MediaSegmentGenerationRequest request, CancellationToken cancellationToken)
        {
            using var lease = await _host.AcquireAsync(cancellationToken).ConfigureAwait(false);
            return lease?.Logic.Segments is { } segments ? await segments.GetMediaSegments(request, cancellationToken).ConfigureAwait(false) : [];
        }

        /// <inheritdoc />
        public async Task CleanupExtractedData(Guid itemId, CancellationToken cancellationToken)
        {
            using var lease = await _host.AcquireAsync(cancellationToken).ConfigureAwait(false);
            if (lease?.Logic.Segments is { } segments)
            {
                await segments.CleanupExtractedData(itemId, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    /// <summary>
    /// Times every request to the server and tells the current logic (its query timing keeps only
    /// the ones Nitrate tagged). Never holds the logic while the rest of the server answers.
    /// </summary>
    public sealed class LogicTimingMiddleware
    {
        private readonly RequestDelegate _next;
        private readonly LogicHost _host;

        /// <summary>Initializes a new instance of the <see cref="LogicTimingMiddleware"/> class.</summary>
        public LogicTimingMiddleware(RequestDelegate next, LogicHost host)
        {
            _next = next;
            _host = host;
        }

        /// <summary>Handles one request.</summary>
        public async Task InvokeAsync(HttpContext context)
        {
            var started = Stopwatch.GetTimestamp();
            using (var lease = _host.TryAcquire())
            {
                try
                {
                    lease?.Logic.RequestStarting(context);
                }
                catch (Exception)
                {
                    // Timing never gets in a request's way.
                }
            }

            try
            {
                await _next(context).ConfigureAwait(false);
            }
            finally
            {
                using var lease = _host.TryAcquire();
                try
                {
                    lease?.Logic.RequestFinished(context, Stopwatch.GetElapsedTime(started));
                }
                catch (Exception)
                {
                    // As above.
                }
            }
        }
    }

    /// <summary>Puts <see cref="LogicTimingMiddleware"/> first in the server's request pipeline.</summary>
    public sealed class LogicTimingStartupFilter : IStartupFilter
    {
        /// <inheritdoc />
        public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
        {
            app.UseMiddleware<LogicTimingMiddleware>();
            next(app);
        };
    }
}

// The scheduled tasks under the full names they had before the split: Jellyfin keeps their
// triggers and history by these names. Never rename or move them.
namespace Nitrate.Companion.Credits
{
    /// <summary>"Find scenes in the credits" (logic: <c>CreditScanJob</c>).</summary>
    public sealed class CreditScanner(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateFindCreditScenes";

        /// <inheritdoc />
        protected override string FallbackName => "Find scenes in the credits";

        /// <inheritdoc />
        protected override string FallbackDescription => "Reads the end of films (and episodes tagged as having one) so Nitrate can skip to scenes during and after the credits.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(4);
    }
}

namespace Nitrate.Companion.Intros
{
    /// <summary>"Find intros" (logic: <c>IntroScanJob</c>).</summary>
    public sealed class IntroScanner(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateFindIntros";

        /// <inheritdoc />
        protected override string FallbackName => "Find intros";

        /// <inheritdoc />
        protected override string FallbackDescription => "Compares the opening minutes of each season's episodes and marks the intro they share, so Skip Intro works in every app.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(1.5);
    }
}

namespace Nitrate.Companion.Recommendations
{
    /// <summary>"Make "Because you watched"" (logic: <c>BecauseRowsBuilder</c>).</summary>
    public sealed class BecauseBuilder(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateBecauseYouWatched";

        /// <inheritdoc />
        protected override string FallbackName => "Make \"Because you watched\"";

        /// <inheritdoc />
        protected override string FallbackDescription => "Works out each person's \"Because you watched\" rows overnight from the server's database (no media files are read), so Nitrate's Home doesn't ask for them as it opens.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(2);
    }
}

namespace Nitrate.Companion.Lines
{
    /// <summary>"Index spoken lines" (logic: <c>LineIndexJob</c>).</summary>
    public sealed class LineIndexer(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateIndexSpokenLines";

        /// <inheritdoc />
        protected override string FallbackName => "Index spoken lines";

        /// <inheritdoc />
        protected override string FallbackDescription => "Reads the subtitles of films and episodes so Nitrate can search what people say.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(3);
    }
}

namespace Nitrate.Companion.Timing
{
    /// <summary>"Check files' timing" (logic: <c>TimingScanJob</c>).</summary>
    public sealed class TimingScanner(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateCheckTiming";

        /// <inheritdoc />
        protected override string FallbackName => "Check files' timing";

        /// <inheritdoc />
        protected override string FallbackDescription => "Looks at how each film and episode was made for things that put sound and picture out of step (an offset at the start, gaps, faulty timestamps), listed in Nitrate's Library health.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(4.5);
    }
}

namespace Nitrate.Companion.SmartCollections
{
    /// <summary>"Update smart collections" (logic: <c>SmartCollectionJob</c>).</summary>
    public sealed class SmartCollectionUpdater(Hosting.LogicHost host) : Hosting.LogicTask(host)
    {
        /// <inheritdoc />
        public override string Key => "NitrateUpdateSmartCollections";

        /// <inheritdoc />
        protected override string FallbackName => "Update smart collections";

        /// <inheritdoc />
        protected override string FallbackDescription => "Keeps the Nitrate Companion's rule-based collections up to date.";

        /// <inheritdoc />
        protected override TimeSpan DefaultTime => TimeSpan.FromHours(5);
    }
}
