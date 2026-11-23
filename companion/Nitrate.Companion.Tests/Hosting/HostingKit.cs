using System.IO.Compression;
using System.Net;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using Jellyfin.Extensions.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.ApplicationParts;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Nitrate.Companion.Hosting;
using Nitrate.Companion.Probe;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>Test helpers for the shell: packages, keys, a real server with the shell's dispatcher in it.</summary>
public static class HostingKit
{
    /// <summary>The fake logic's assembly, built beside the tests.</summary>
    public static byte[] FakeLogicDll { get; } = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "fake-logic", "Nitrate.Companion.Tests.FakeLogic.dll"));

    /// <summary>A logic package zip.</summary>
    public static byte[] Package(string version, int contract = ShellContract.Version, byte[]? dll = null, string entry = "Nitrate.Companion.Tests.FakeLogic.dll", IEnumerable<(string Name, byte[] Bytes)>? extra = null)
    {
        using var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            var files = new List<(string Name, byte[] Bytes)> { (entry, dll ?? FakeLogicDll) };
            files.AddRange(extra ?? []);
            foreach (var (name, bytes) in files)
            {
                using var s = zip.CreateEntry(name).Open();
                s.Write(bytes);
            }

            using var manifest = new StreamWriter(zip.CreateEntry("logic.json").Open());
            manifest.Write(JsonSerializer.Serialize(new LogicManifest(version, contract, entry, files.Select(f => f.Name).ToList())));
        }

        return buffer.ToArray();
    }

    /// <summary>A fresh signing key.</summary>
    public static ECDsa NewKey() => ECDsa.Create(ECCurve.NamedCurves.nistP256);

    /// <summary>A signature as the release makes it (DER over the zip's SHA-256).</summary>
    public static byte[] Sign(ECDsa key, byte[] zip) => key.SignHash(SHA256.HashData(zip), DSASignatureFormat.Rfc3279DerSequence);

    /// <summary>A temporary folder, deleted at the end.</summary>
    public static TempDir Temp() => new();

    /// <summary>A logic host over a temporary store whose built-in package is <paramref name="builtIn"/>.</summary>
    public static (LogicHost Host, LogicStore Store) Host(TempDir dir, byte[]? builtIn, ECDsa? key = null, IServiceProvider? services = null)
    {
        string? builtInPath = null;
        if (builtIn is not null)
        {
            builtInPath = Path.Combine(dir.Path, "plugin", LogicPackage.BuiltInFile);
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(builtInPath)!);
            File.WriteAllBytes(builtInPath, builtIn);
        }

        var store = new LogicStore(Path.Combine(dir.Path, "data", "logic"), builtInPath, key?.ExportSubjectPublicKeyInfo());
        var host = new LogicHost(services ?? new ServiceCollection().BuildServiceProvider(), NullLoggerFactory.Instance, store)
        {
            DrainTime = TimeSpan.FromSeconds(5),
        };
        return (host, store);
    }

    /// <summary>A downloaded, signed package.</summary>
    public static LogicPackage Downloaded(ECDsa key, string version)
    {
        var zip = Package(version);
        return LogicPackage.Read(zip, LogicStore.Downloaded, Sign(key, zip));
    }

    /// <summary>A temporary folder.</summary>
    public sealed class TempDir : IDisposable
    {
        /// <summary>Gets the folder.</summary>
        public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "nitrate-hosting-" + Guid.NewGuid().ToString("N"));

        /// <inheritdoc />
        public void Dispose()
        {
            try
            {
                Directory.Delete(Path, true);
            }
            catch (IOException)
            {
            }
        }
    }
}

/// <summary>Signs in whoever sends X-User (X-Admin: 1 for an admin); nobody otherwise, like Jellyfin's handler.</summary>
public sealed class TestAuthHandler : AuthenticationHandler<AuthenticationSchemeOptions>
{
    /// <summary>Initializes a new instance of the <see cref="TestAuthHandler"/> class.</summary>
    public TestAuthHandler(IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger, UrlEncoder encoder)
        : base(options, logger, encoder)
    {
    }

    /// <inheritdoc />
    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var user = Request.Headers["X-User"].ToString();
        if (user.Length == 0)
        {
            return Task.FromResult(AuthenticateResult.NoResult());
        }

        var claims = new List<Claim> { new("Jellyfin-UserId", user) };
        if (Request.Headers["X-Admin"] == "1")
        {
            claims.Add(new Claim("admin", "1"));
        }

        return Task.FromResult(AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(claims, "Test")), "Test")));
    }
}

/// <summary>The probe, run by MVC itself.</summary>
[ApiController]
[Microsoft.AspNetCore.Authorization.Authorize]
[Route("Direct/Probe")]
[Produces("application/json")]
public sealed class DirectProbeController : ProbeActions
{
}

/// <summary>The bare probe, run by MVC itself.</summary>
[ApiController]
[Microsoft.AspNetCore.Authorization.Authorize]
[Route("Direct/Bare")]
public sealed class DirectBareController : BareActions
{
}

/// <summary>
/// A real web server (Kestrel on a free port) set up like Jellyfin's API: MVC with Jellyfin's JSON
/// settings, a default scheme that signs in, authorization with a policy, the shell's dispatcher and
/// the direct probe as controllers, and the shell's timing middleware first.
/// </summary>
public sealed class TestServer : IAsyncDisposable
{
    private readonly WebApplication _app;

    private TestServer(WebApplication app, HttpClient client)
    {
        _app = app;
        Client = client;
    }

    /// <summary>Gets a client for the server.</summary>
    public HttpClient Client { get; }

    /// <summary>Starts a server with <paramref name="host"/> behind the shell's dispatcher.</summary>
    public static async Task<TestServer> StartAsync(LogicHost host)
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseKestrel(o => o.Listen(IPAddress.Loopback, 0));
        builder.Services.AddSingleton(host);
        builder.Services.AddTransient<IStartupFilter, LogicTimingStartupFilter>();
        builder.Services.AddAuthentication("Test").AddScheme<AuthenticationSchemeOptions, TestAuthHandler>("Test", null);
        builder.Services.AddAuthorization(o => o.AddPolicy("Admin", p => p.RequireClaim("admin")));
        builder.Services.AddControllers()
            .ConfigureApplicationPartManager(parts =>
            {
                parts.ApplicationParts.Clear();
                parts.FeatureProviders.Add(new OnlyThese(typeof(DispatchController), typeof(DirectProbeController), typeof(DirectBareController)));
                parts.ApplicationParts.Add(new AssemblyPart(typeof(DispatchController).Assembly));
                parts.ApplicationParts.Add(new AssemblyPart(typeof(TestServer).Assembly));
            })
            .AddJsonOptions(options =>
            {
                // As Jellyfin sets them (Jellyfin.Server's ApiServiceCollectionExtensions).
                var json = JsonDefaults.PascalCaseOptions;
                options.JsonSerializerOptions.ReadCommentHandling = json.ReadCommentHandling;
                options.JsonSerializerOptions.WriteIndented = json.WriteIndented;
                options.JsonSerializerOptions.DefaultIgnoreCondition = json.DefaultIgnoreCondition;
                options.JsonSerializerOptions.NumberHandling = json.NumberHandling;
                options.JsonSerializerOptions.Converters.Clear();
                foreach (var converter in json.Converters)
                {
                    options.JsonSerializerOptions.Converters.Add(converter);
                }

                options.JsonSerializerOptions.PropertyNamingPolicy = json.PropertyNamingPolicy;
            });
        var app = builder.Build();
        app.UseRouting();
        app.UseAuthentication();
        app.UseAuthorization();
        app.MapControllers();
        await app.StartAsync().ConfigureAwait(false);
        var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        return new TestServer(app, new HttpClient { BaseAddress = new Uri(address), Timeout = TimeSpan.FromSeconds(30) });
    }

    /// <inheritdoc />
    public async ValueTask DisposeAsync()
    {
        Client.Dispose();
        await _app.StopAsync().ConfigureAwait(false);
        await _app.DisposeAsync().ConfigureAwait(false);
    }

    private sealed class OnlyThese : ControllerFeatureProvider
    {
        private readonly HashSet<Type> _types;

        public OnlyThese(params Type[] types) => _types = types.ToHashSet();

        protected override bool IsController(System.Reflection.TypeInfo typeInfo) => _types.Contains(typeInfo.AsType());
    }
}

/// <summary>Requests, the way the tests write them.</summary>
public static class Requests
{
    /// <summary>A request as a signed-in user (or nobody, or an admin).</summary>
    public static HttpRequestMessage As(HttpMethod method, string path, string? user = "sam", bool admin = false, string? json = null, string contentType = "application/json")
    {
        var request = new HttpRequestMessage(method, path);
        if (user is not null)
        {
            request.Headers.Add("X-User", user);
        }

        if (admin)
        {
            request.Headers.Add("X-Admin", "1");
        }

        if (json is not null)
        {
            request.Content = new StringContent(json, Encoding.UTF8);
            request.Content.Headers.ContentType = contentType.Length == 0 ? null : System.Net.Http.Headers.MediaTypeHeaderValue.Parse(contentType);
        }

        return request;
    }
}
