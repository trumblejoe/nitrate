using System.Reflection;
using MediaBrowser.Model.Tasks;
using Nitrate.Companion.SmartCollections;
using Xunit;

namespace Nitrate.Companion.Tests;

/// <summary>After library changes, the smart collections task runs: found by its key, as the server knows the shell's task.</summary>
public class SmartCollectionTriggerTests
{
    /// <summary>Answers an interface from a table of member name → result; records calls.</summary>
    public class Fake : DispatchProxy
    {
        public Dictionary<string, Func<object?[]?, object?>> Answers { get; } = new();

        public List<string> Calls { get; } = new();

        public static T Of<T>(Action<Fake> setup)
            where T : class
        {
            var proxy = Create<T, Fake>();
            setup((proxy as Fake)!);
            return proxy;
        }

        protected override object? Invoke(MethodInfo? method, object?[]? args)
        {
            var name = method!.Name.Replace("get_", string.Empty, StringComparison.Ordinal);
            Calls.Add(name);
            return Answers.TryGetValue(name, out var answer) ? answer(args) : (method.ReturnType.IsValueType ? Activator.CreateInstance(method.ReturnType) : null);
        }
    }

    private static IScheduledTaskWorker Worker(string key, TaskState state) => Fake.Of<IScheduledTaskWorker>(f =>
    {
        f.Answers["ScheduledTask"] = _ => Fake.Of<IScheduledTask>(t => t.Answers["Key"] = _ => key);
        f.Answers["State"] = _ => state;
    });

    [Fact]
    public void TheTaskRunsByItsKeyAndNotTwice()
    {
        var other = Worker("SomethingElse", TaskState.Idle);
        var ours = Worker(SmartCollectionJob.TaskKey, TaskState.Idle);
        var executed = new List<IScheduledTaskWorker>();
        var tasks = Fake.Of<ITaskManager>(f =>
        {
            f.Answers["ScheduledTasks"] = _ => new[] { other, ours };
            f.Answers["Execute"] = args => { executed.Add((IScheduledTaskWorker)args![0]!); return null; };
        });
        SmartCollectionWatcher.RunNow(tasks);
        Assert.Same(ours, Assert.Single(executed));

        // Already running: left alone.
        var running = Fake.Of<ITaskManager>(f =>
        {
            f.Answers["ScheduledTasks"] = _ => new[] { Worker(SmartCollectionJob.TaskKey, TaskState.Running) };
            f.Answers["Execute"] = _ => throw new InvalidOperationException("ran twice");
        });
        SmartCollectionWatcher.RunNow(running);
    }
}
