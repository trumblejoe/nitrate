using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Nitrate.Companion.Recommendations;

/// <summary>
/// A few minutes after the server starts, makes "Because you watched" again (it's kept in memory
/// only) for everyone who watched something in the last month, one person at a time, so Home
/// rarely finds it missing.
/// </summary>
public class BecauseWarmup : BackgroundService
{
    /// <summary>How long after start it begins (the server has enough to do while starting).</summary>
    public static readonly TimeSpan Delay = TimeSpan.FromMinutes(3);

    /// <summary>Who counts as watching lately.</summary>
    public static readonly TimeSpan Recent = TimeSpan.FromDays(30);

    private readonly BecauseBuilder _builder;
    private readonly ILogger<BecauseWarmup> _log;

    /// <summary>Initializes a new instance of the <see cref="BecauseWarmup"/> class.</summary>
    public BecauseWarmup(BecauseBuilder builder, ILogger<BecauseWarmup> log)
    {
        _builder = builder;
        _log = log;
    }

    /// <inheritdoc />
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await Task.Delay(Delay, stoppingToken).ConfigureAwait(false);
            await _builder.BuildRecentAsync(Recent, stoppingToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // The server is stopping.
        }
        catch (Exception e)
        {
            _log.LogWarning("Because you watched: couldn't make rows after start: {Message}", e.Message);
        }
    }
}
