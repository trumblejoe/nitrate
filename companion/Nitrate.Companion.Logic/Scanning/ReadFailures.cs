namespace Nitrate.Companion.Scanning;

/// <summary>One file that couldn't be read: which version, how many times in a row, and when last.</summary>
/// <param name="Version">The file's version then (a changed file is tried at once).</param>
/// <param name="Count">Failures in a row.</param>
/// <param name="LastAt">When it last failed.</param>
public record ReadFailure(string Version, int Count, DateTimeOffset LastAt);

/// <summary>
/// Files a nightly task couldn't read (damaged, unplayable, gone missing for a moment), so they
/// aren't read again every night: tried again after a day, then a week, then once a month, and at
/// once when the file changes. Kept per task in the plugin's database.
/// </summary>
public class ReadFailures
{
    private readonly Nitrate.Companion.Storage.CompanionDb? _db;
    private readonly string _task;
    private readonly TimeProvider _time;
    private readonly object _lock = new();
    private readonly Dictionary<string, ReadFailure> _failures;

    /// <summary>Initializes a new instance of the <see cref="ReadFailures"/> class for [task] ([db] null: in memory only).</summary>
    public ReadFailures(Nitrate.Companion.Storage.CompanionDb? db, string task, TimeProvider? time = null)
    {
        _db = db;
        _task = task;
        _time = time ?? TimeProvider.System;
        _failures = db?.Failures<ReadFailure>(task) ?? new();
    }

    /// <summary>How long to wait after [count] failures in a row.</summary>
    public static TimeSpan WaitAfter(int count) => count switch
    {
        <= 1 => TimeSpan.FromDays(1),
        2 => TimeSpan.FromDays(7),
        _ => TimeSpan.FromDays(30),
    };

    /// <summary>Whether [item] ([part] of it, e.g. "whole") may be read now: never failed, changed since, or waited long enough.</summary>
    public bool MayRead(Guid item, string version, string part = "")
    {
        lock (_lock)
        {
            return !_failures.TryGetValue(Key(item, part), out var f) || f.Version != version || _time.GetUtcNow() - f.LastAt >= WaitAfter(f.Count);
        }
    }

    /// <summary>Notes that reading failed.</summary>
    public void Failed(Guid item, string version, string part = "")
    {
        lock (_lock)
        {
            var key = Key(item, part);
            var count = _failures.TryGetValue(key, out var f) && f.Version == version ? f.Count + 1 : 1;
            _failures[key] = new ReadFailure(version, count, _time.GetUtcNow());
            _db?.PutFailure(_task, key, _failures[key]);
        }
    }

    /// <summary>Notes that reading worked (forgets earlier failures).</summary>
    public void Succeeded(Guid item, string part = "")
    {
        lock (_lock)
        {
            var key = Key(item, part);
            if (_failures.Remove(key))
            {
                _db?.DeleteFailures(_task, new[] { key });
            }
        }
    }

    /// <summary>Forgets items no longer in the library.</summary>
    public void KeepOnly(ISet<Guid> present)
    {
        lock (_lock)
        {
            var gone = _failures.Keys.Where(k => !Guid.TryParseExact(k[..32], "N", out var id) || !present.Contains(id)).ToList();
            gone.ForEach(k => _failures.Remove(k));
            _db?.DeleteFailures(_task, gone);
        }
    }

    /// <summary>How many files are waiting to be tried again.</summary>
    public int Count
    {
        get
        {
            lock (_lock)
            {
                return _failures.Count;
            }
        }
    }

    private static string Key(Guid item, string part) => item.ToString("N") + (part.Length > 0 ? ":" + part : string.Empty);
}

/// <summary>
/// One media file read at a time across the file-reading tasks: Jellyfin may start several at once
/// (two share 3am), and reading two films' worth of disk side by side is slower for both and harder
/// on the disks. Each task takes the gate around each file it reads (never for a whole run), so two
/// tasks interleave file by file, and an admin's requested read goes next.
/// </summary>
public static class FileReading
{
    /// <summary>Taken around each file read (<see cref="ReadGate.EnterAsync"/>).</summary>
    public static readonly ReadGate Gate = new();
}

/// <summary>
/// A lock for one file read at a time, where requested reads (an admin waiting on a Credits check)
/// go before the nightly tasks' next file: whoever holds it hands it to the longest-waiting
/// priority waiter, else the longest-waiting normal one. A waiter that's cancelled leaves the queue.
/// </summary>
public sealed class ReadGate
{
    private readonly object _lock = new();
    private readonly LinkedList<TaskCompletionSource<IDisposable>> _priority = new();
    private readonly LinkedList<TaskCompletionSource<IDisposable>> _normal = new();
    private bool _busy;

    /// <summary>Whether a read holds the gate now.</summary>
    public bool Busy
    {
        get
        {
            lock (_lock)
            {
                return _busy;
            }
        }
    }

    /// <summary>Requested reads waiting.</summary>
    public int PriorityWaiting
    {
        get
        {
            lock (_lock)
            {
                return _priority.Count;
            }
        }
    }

    /// <summary>Scheduled reads waiting.</summary>
    public int Waiting
    {
        get
        {
            lock (_lock)
            {
                return _normal.Count;
            }
        }
    }

    /// <summary>
    /// Waits for the gate ([priority]: ahead of every normal waiter); dispose what it gives to
    /// let the next one in. Cancelling [cancellationToken] while waiting leaves the queue.
    /// </summary>
    public Task<IDisposable> EnterAsync(CancellationToken cancellationToken = default, bool priority = false)
    {
        cancellationToken.ThrowIfCancellationRequested();
        TaskCompletionSource<IDisposable> waiter;
        LinkedListNode<TaskCompletionSource<IDisposable>> node;
        lock (_lock)
        {
            if (!_busy)
            {
                _busy = true;
                return Task.FromResult<IDisposable>(new Lease(this));
            }

            waiter = new TaskCompletionSource<IDisposable>(TaskCreationOptions.RunContinuationsAsynchronously);
            node = (priority ? _priority : _normal).AddLast(waiter);
        }

        if (cancellationToken.CanBeCanceled)
        {
            var registration = cancellationToken.Register(() =>
            {
                lock (_lock)
                {
                    // Only while still queued: once handed the gate, the lease is the waiter's to give back.
                    if (node.List == null)
                    {
                        return;
                    }

                    node.List.Remove(node);
                }

                waiter.TrySetCanceled(cancellationToken);
            });
            _ = waiter.Task.ContinueWith(_ => registration.Dispose(), CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
        }

        return waiter.Task;
    }

    /// <summary>Runs [read] holding the gate.</summary>
    public async Task<T> RunAsync<T>(Func<Task<T>> read, CancellationToken cancellationToken = default, bool priority = false)
    {
        using var lease = await EnterAsync(cancellationToken, priority).ConfigureAwait(false);
        return await read().ConfigureAwait(false);
    }

    /// <summary>Runs [read] holding the gate.</summary>
    public async Task RunAsync(Func<Task> read, CancellationToken cancellationToken = default, bool priority = false)
    {
        using var lease = await EnterAsync(cancellationToken, priority).ConfigureAwait(false);
        await read().ConfigureAwait(false);
    }

    private void Release()
    {
        TaskCompletionSource<IDisposable>? next;
        lock (_lock)
        {
            var list = _priority.Count > 0 ? _priority : _normal;
            next = list.First?.Value;
            if (next == null)
            {
                _busy = false;
                return;
            }

            list.RemoveFirst();
        }

        // Still busy: handed straight to the next waiter.
        next.TrySetResult(new Lease(this));
    }

    private sealed class Lease : IDisposable
    {
        private ReadGate? _gate;

        public Lease(ReadGate gate) => _gate = gate;

        public void Dispose() => Interlocked.Exchange(ref _gate, null)?.Release();
    }
}
