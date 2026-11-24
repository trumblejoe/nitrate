using System.Reflection;
using MediaBrowser.Common.Configuration;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.Extensions.DependencyInjection;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Logic;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>The real logic: every controller action is served, with its auth, and the package runs and unloads in its own context.</summary>
public sealed class RealLogicTests : IDisposable
{
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public void EveryActionIsServedOnceWithBindableParameters()
    {
        var router = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly);
        var actions = LogicServices.Controllers
            .SelectMany(c => c.GetMethods(BindingFlags.Instance | BindingFlags.Public).Where(m => m.GetCustomAttributes<HttpMethodAttribute>().Any()))
            .ToList();
        Assert.True(actions.Count > 90, $"only {actions.Count} actions");
        var routes = router.Routes.ToList();
        Assert.Equal(actions.Sum(a => a.GetCustomAttributes<HttpMethodAttribute>().Sum(h => h.HttpMethods.Count())), routes.Count);
        Assert.Equal(routes.Count, routes.Distinct(StringComparer.OrdinalIgnoreCase).Count());
        foreach (var action in actions)
        {
            foreach (var p in action.GetParameters())
            {
                Assert.True(
                    p.ParameterType == typeof(CancellationToken) || p.GetCustomAttributes().Any(a => a is FromBodyAttribute or FromQueryAttribute or FromRouteAttribute or FromServicesAttribute),
                    $"{action.DeclaringType!.Name}.{action.Name}({p.Name}) has no binding source the router knows");
            }
        }

        // The shell serves updates itself; the logic never did.
        Assert.DoesNotContain(routes, r => r.Contains("Nitrate/Updates", StringComparison.OrdinalIgnoreCase));
    }

    [Theory]
    [InlineData("GET", "/Nitrate/Companion", false)]
    [InlineData("GET", "/nitrate/companion", false)]
    [InlineData("GET", "/Nitrate/Chat/6f9619ff-8b86-d011-b42d-00cf4fc964ff/Messages", false)]
    [InlineData("PUT", "/Nitrate/Notes/Brought/6f9619ff-8b86-d011-b42d-00cf4fc964ff", false)]
    [InlineData("DELETE", "/Nitrate/Notes/abc", false)]
    [InlineData("GET", "/Nitrate/Chats/6f9619ff-8b86-d011-b42d-00cf4fc964ff", false)]
    [InlineData("PUT", "/Nitrate/Chats/Session/6f9619ff-8b86-d011-b42d-00cf4fc964ff", false)]
    [InlineData("PUT", "/Nitrate/Chats/Favourites/6f9619ff-8b86-d011-b42d-00cf4fc964ff", false)]
    [InlineData("GET", "/Nitrate/Settings", false)]
    [InlineData("POST", "/Nitrate/Settings", false)]
    [InlineData("GET", "/Nitrate/Sso", true)]
    [InlineData("POST", "/Nitrate/Sso/Poll", true)]
    [InlineData("GET", "/Nitrate/Sso/Callback", true)]
    [InlineData("GET", "/Nitrate/TestClips/hdr10", false)]
    [InlineData("GET", "/Nitrate/Comics/6f9619ff-8b86-d011-b42d-00cf4fc964ff/Pages/3", false)]
    [InlineData("GET", "/Nitrate/Partners/People", false)]
    public void RoutesKeepTheirAuth(string method, string path, bool open)
    {
        var router = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly);
        var match = router.Match(method, path);
        Assert.Equal(RouteMatchKind.Found, match.Kind);
        Assert.Equal(open, match.AllowAnonymous);
        if (!open)
        {
            Assert.Equal([null], match.Policies);
        }
    }

    /// <summary>
    /// The server's JSON writer only ever sees plain JSON for the logic's types, empty replies
    /// included (it keeps what it learns about a type for good, which kept old logic in memory).
    /// </summary>
    [Fact]
    public void TheServerNeverWritesALogicType()
    {
        var router = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly);
        var json = new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web);
        var none = new ObjectResult(null) { DeclaredType = typeof(Nitrate.Companion.Partners.PartyView) };
        router.Shield(none, json);
        Assert.Equal(typeof(object), none.DeclaredType);
        var list = new ObjectResult(new List<Nitrate.Companion.Partners.ResumeView> { new(Guid.Empty, 1, DateTimeOffset.UnixEpoch) })
        {
            DeclaredType = typeof(IReadOnlyList<Nitrate.Companion.Partners.ResumeView>),
        };
        router.Shield(list, json);
        Assert.IsType<System.Text.Json.JsonElement>(list.Value);
        Assert.Equal(typeof(System.Text.Json.JsonElement), list.DeclaredType);
        // The server's own types are left to it.
        var plain = new ObjectResult("ok") { DeclaredType = typeof(string) };
        router.Shield(plain, json);
        Assert.Equal("ok", plain.Value);
    }

    [Fact]
    public void UnknownPathsAndMethodsAreTold()
    {
        var router = new ApiRouter(LogicServices.Controllers, typeof(CompanionLogic).Assembly);
        Assert.Equal(RouteMatchKind.NotFound, router.Match("GET", "/Nitrate/Nothing/Here").Kind);
        Assert.Equal(RouteMatchKind.NotFound, router.Match("GET", "/Nitrate/Updates").Kind);
        Assert.Equal(RouteMatchKind.MethodNotAllowed, router.Match("PATCH", "/Nitrate/Companion").Kind);
    }

    [Fact]
    public void TheLogicNamesItsFactoryAndContract()
    {
        var entry = typeof(CompanionLogic).Assembly.GetCustomAttribute<CompanionLogicAttribute>();
        Assert.NotNull(entry);
        Assert.Equal(typeof(LogicFactory), entry!.Factory);
        Assert.Equal(ShellContract.Version, entry.Contract);
    }

    [Fact]
    public async Task TheRealPackageRunsInItsOwnContextAndUnloads()
    {
        var host = HostingKit.Host(_dir, RealPackage("1.0.0.1"), services: NullServer.Services(_dir.Path)).Host;
        await host.StartAsync(CancellationToken.None);
        Assert.Equal("1.0.0.1", host.Status.Version);
        using (var lease = await host.AcquireAsync(CancellationToken.None))
        {
            Assert.True(lease!.Logic.GetType().Assembly.IsCollectible);
            Assert.Equal(RouteMatchKind.Found, lease.Logic.Match("GET", "/Nitrate/Companion").Kind);
            Assert.Equal(6, lease.Logic.Tasks.Count);
            Assert.NotNull(lease.Logic.Segments);
        }

        using var key = HostingKit.NewKey();
        var zip = RealPackage("1.0.0.2");
        Assert.Equal(SwapOutcome.Swapped, await host.SwapAsync(LogicPackage.Read(zip, LogicStore.BuiltIn), CancellationToken.None));
        Assert.Equal("1.0.0.2", host.Status.Version);
        Assert.True(await host.WaitForUnloadAsync(TimeSpan.FromSeconds(30)), "the real logic's old context is still in memory");
        await host.DisposeAsync();
    }

    private static byte[] RealPackage(string version)
    {
        var dir = AppContext.BaseDirectory;
        return HostingKit.Package(
            version,
            dll: File.ReadAllBytes(Path.Combine(dir, "Nitrate.Companion.Logic.dll")),
            entry: "Nitrate.Companion.Logic.dll",
            extra: [("SharpCompress.dll", File.ReadAllBytes(Path.Combine(dir, "SharpCompress.dll")))]);
    }
}

/// <summary>
/// A server with nothing in it: every service the logic asks for is a stand-in that does nothing
/// (and has paths in a temporary folder), so the real logic can start and stop in a test.
/// </summary>
public class NullServer : DispatchProxy
{
    private string _root = string.Empty;

    /// <summary>A service provider handing out stand-ins.</summary>
    public static IServiceProvider Services(string root) => new Provider(root);

    /// <inheritdoc />
    protected override object? Invoke(MethodInfo? targetMethod, object?[]? args)
    {
        var type = targetMethod?.ReturnType ?? typeof(void);
        if (type == typeof(string))
        {
            return targetMethod!.Name.EndsWith("Path", StringComparison.Ordinal) ? Path.Combine(_root, targetMethod.Name) : string.Empty;
        }

        if (type == typeof(Task))
        {
            return Task.CompletedTask;
        }

        if (type.IsArray)
        {
            return Array.CreateInstance(type.GetElementType()!, 0);
        }

        if (type.IsGenericType && type.GetGenericTypeDefinition() is var g && (g == typeof(IEnumerable<>) || g == typeof(IReadOnlyList<>) || g == typeof(IReadOnlyCollection<>) || g == typeof(IList<>)))
        {
            return Array.CreateInstance(type.GetGenericArguments()[0], 0);
        }

        return type.IsValueType && type != typeof(void) ? Activator.CreateInstance(type) : null;
    }

    private sealed class Provider : IServiceProvider
    {
        private readonly string _root;
        private readonly Dictionary<Type, object> _made = new();

        public Provider(string root) => _root = root;

        public object? GetService(Type serviceType)
        {
            if (!serviceType.IsInterface)
            {
                return null;
            }

            lock (_made)
            {
                if (!_made.TryGetValue(serviceType, out var made))
                {
                    made = DispatchProxy.Create(serviceType, typeof(NullServer));
                    ((NullServer)made)._root = _root;
                    _made[serviceType] = made;
                }

                return made;
            }
        }
    }
}
