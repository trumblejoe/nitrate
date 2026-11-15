using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Time.Testing;
using Nitrate.Companion.Sso;
using Xunit;

namespace Nitrate.Companion.Tests;

public class SsoTests
{
    private const string Issuer = "https://id.example";
    private const string ClientId = "nitrate";
    private static readonly Guid Joe = Guid.NewGuid();
    private static readonly Guid Ann = Guid.NewGuid();
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T20:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly RSA _key = RSA.Create(2048);
    private readonly FakeProvider _provider;
    private readonly FakeAccounts _accounts = new();
    private SsoSettings _settings = new(true, "Home ID", Issuer, ClientId, "s3cret", "openid profile email", "preferred_username", true, "groups", string.Empty, new Dictionary<string, string>(), string.Empty);
    private readonly SsoService _sso;

    public SsoTests()
    {
        _provider = new FakeProvider(this);
        _sso = new SsoService(() => _settings, _accounts, new HttpClient(_provider), _time);
    }

    private static readonly SsoDevice Tv = new("dev-1", "Living room TV", "Nitrate TV", "1.0");

    private sealed class FakeAccounts : ISsoAccounts
    {
        public List<(Guid, SsoDevice)> SignIns { get; } = new();

        public (Guid Id, string Name)? FindUser(string name) => name.ToLowerInvariant() switch
        {
            "joe" => (Joe, "joe"),
            "ann@example.com" => (Ann, "ann@example.com"),
            _ => null,
        };

        public Task<string> SignIn(Guid userId, SsoDevice device)
        {
            SignIns.Add((userId, device));
            return Task.FromResult("token-" + userId);
        }
    }

    /// <summary>An identity provider: discovery, keys, and a token endpoint answering with <see cref="Claims"/>.</summary>
    private sealed class FakeProvider : HttpMessageHandler
    {
        private readonly SsoTests _t;

        public FakeProvider(SsoTests t) => _t = t;

        public Func<string, Dictionary<string, object>> Claims { get; set; } = nonce => new();

        public string? LastNonce { get; set; }

        public Dictionary<string, string> LastForm { get; } = new();

        public string? LastAuth { get; set; }

        public bool SignWithOtherKey { get; set; }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var path = request.RequestUri!.AbsolutePath;
            string body;
            if (path == "/.well-known/openid-configuration")
            {
                body = JsonSerializer.Serialize(new Dictionary<string, string>
                {
                    ["issuer"] = Issuer,
                    ["authorization_endpoint"] = Issuer + "/authorize",
                    ["token_endpoint"] = Issuer + "/token",
                    ["jwks_uri"] = Issuer + "/jwks",
                });
            }
            else if (path == "/jwks")
            {
                var p = _t._key.ExportParameters(false);
                body = JsonSerializer.Serialize(new { keys = new[] { new { kty = "RSA", kid = "k1", use = "sig", n = Jwt.ToBase64Url(p.Modulus!), e = Jwt.ToBase64Url(p.Exponent!) } } });
            }
            else if (path == "/token")
            {
                LastForm.Clear();
                foreach (var pair in (await request.Content!.ReadAsStringAsync(cancellationToken)).Split('&'))
                {
                    var kv = pair.Split('=');
                    LastForm[Uri.UnescapeDataString(kv[0])] = Uri.UnescapeDataString(kv[1]);
                }

                LastAuth = request.Headers.Authorization?.ToString();
                var now = _t._time.GetUtcNow().ToUnixTimeSeconds();
                var claims = new Dictionary<string, object>
                {
                    ["iss"] = Issuer,
                    ["aud"] = ClientId,
                    ["sub"] = "u-123",
                    ["exp"] = now + 300,
                    ["iat"] = now,
                    ["nonce"] = LastNonce!,
                };
                foreach (var (k, v) in Claims(LastNonce!))
                {
                    claims[k] = v;
                }

                using var other = RSA.Create(2048);
                body = JsonSerializer.Serialize(new { id_token = Sign(claims, SignWithOtherKey ? other : _t._key), access_token = "at" });
            }
            else
            {
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }

            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
        }

        private static string Sign(Dictionary<string, object> claims, RSA key)
        {
            var head = Jwt.ToBase64Url(Encoding.UTF8.GetBytes("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}"));
            var payload = Jwt.ToBase64Url(JsonSerializer.SerializeToUtf8Bytes(claims));
            var sig = key.SignData(Encoding.ASCII.GetBytes(head + "." + payload), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            return head + "." + payload + "." + Jwt.ToBase64Url(sig);
        }
    }

    private static Dictionary<string, string> Query(string url) =>
        new Uri(url).Query.TrimStart('?').Split('&').Select(p => p.Split('=')).ToDictionary(p => p[0], p => Uri.UnescapeDataString(p[1]));

    /// <summary>Starts a sign-in, goes to the provider and comes back; returns it with the device's code and secret.</summary>
    private async Task<(PendingSignIn Start, PendingSignIn? Back, Dictionary<string, string> Asked)> SignIn(Func<string, Dictionary<string, object>> claims)
    {
        var start = _sso.Start(Tv);
        var asked = Query(await _sso.AuthorizeUrl(start.Code, "https://jf.example/Nitrate/Sso/Callback"));
        _provider.LastNonce = asked["nonce"];
        _provider.Claims = claims;
        var back = await _sso.Callback(asked["state"], "the-code", null, null);
        return (start, back, asked);
    }

    [Fact]
    public async Task ASignInAtTheProviderSignsTheDeviceInAsTheMatchingUser()
    {
        var (start, back, asked) = await SignIn(_ => new() { ["preferred_username"] = "Joe" });
        Assert.Equal("S256", asked["code_challenge_method"]);
        Assert.Equal(ClientId, asked["client_id"]);
        Assert.Contains("openid", asked["scope"], StringComparison.Ordinal);
        // PKCE: the verifier sent to the token endpoint hashes to the challenge.
        var verifier = _provider.LastForm["code_verifier"];
        Assert.Equal(asked["code_challenge"], Jwt.ToBase64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))));
        Assert.StartsWith("Basic ", _provider.LastAuth, StringComparison.Ordinal);
        Assert.Equal(Joe, back!.User!.Value.Id);

        var wrongSecret = await _sso.Poll(start.Code, "nope");
        Assert.Equal(SsoState.Expired, wrongSecret.State);
        var done = await _sso.Poll(start.Code, start.Secret);
        Assert.Equal(SsoState.Done, done.State);
        Assert.Equal("token-" + Joe, done.AccessToken);
        Assert.Equal("Living room TV", _accounts.SignIns.Single().Item2.DeviceName);
        // Collected once.
        Assert.Equal(SsoState.Expired, (await _sso.Poll(start.Code, start.Secret)).State);
    }

    [Fact]
    public async Task TheDeviceWaitsUntilTheBrowserIsDone()
    {
        var start = _sso.Start(Tv);
        Assert.Equal(SsoState.Waiting, (await _sso.Poll(start.Code, start.Secret)).State);
        Assert.NotNull(_sso.Find(start.Code[..4].ToLowerInvariant() + "-" + start.Code[4..]));
        _time.Advance(SsoService.Lifetime + TimeSpan.FromSeconds(1));
        Assert.Equal(SsoState.Expired, (await _sso.Poll(start.Code, start.Secret)).State);
    }

    [Fact]
    public async Task AVerifiedEmailMatchesAUserNamedAfterIt()
    {
        var (_, back, _) = await SignIn(_ => new() { ["preferred_username"] = "annie", ["email"] = "ann@example.com", ["email_verified"] = true });
        Assert.Equal(Ann, back!.User!.Value.Id);
        var (_, unverified, _) = await SignIn(_ => new() { ["preferred_username"] = "annie", ["email"] = "ann@example.com", ["email_verified"] = false });
        Assert.Null(unverified!.User);
        Assert.Contains("No Jellyfin account matches annie", unverified.Error, StringComparison.Ordinal);
    }

    [Fact]
    public async Task OnlyExistingUsersAndLinksSignIn()
    {
        var (start, back, _) = await SignIn(_ => new() { ["preferred_username"] = "stranger" });
        Assert.Null(back!.User);
        var failed = await _sso.Poll(start.Code, start.Secret);
        Assert.Equal(SsoState.Failed, failed.State);
        Assert.Empty(_accounts.SignIns);

        _settings = _settings with { Links = SsoSettings.ParseLinks("# people\nu-123 = joe\n") };
        var (_, linked, _) = await SignIn(_ => new() { ["preferred_username"] = "stranger" });
        Assert.Equal(Joe, linked!.User!.Value.Id);
    }

    [Fact]
    public async Task ARequiredGroupKeepsOthersOut()
    {
        _settings = _settings with { RequiredGroup = "jellyfin" };
        var (_, outside, _) = await SignIn(_ => new() { ["preferred_username"] = "joe", ["groups"] = new[] { "staff" } });
        Assert.Null(outside!.User);
        var (_, inside, _) = await SignIn(_ => new() { ["preferred_username"] = "joe", ["groups"] = new[] { "staff", "Jellyfin" } });
        Assert.Equal(Joe, inside!.User!.Value.Id);
    }

    [Theory]
    [InlineData("aud")]
    [InlineData("iss")]
    [InlineData("nonce")]
    [InlineData("exp")]
    [InlineData("signature")]
    public async Task ABadIdTokenIsRefused(string what)
    {
        var now = _time.GetUtcNow().ToUnixTimeSeconds();
        _provider.SignWithOtherKey = what == "signature";
        var (_, back, _) = await SignIn(_ => what switch
        {
            "aud" => new() { ["preferred_username"] = "joe", ["aud"] = "someone-else" },
            "iss" => new() { ["preferred_username"] = "joe", ["iss"] = "https://evil.example" },
            "nonce" => new() { ["preferred_username"] = "joe", ["nonce"] = "replayed" },
            "exp" => new() { ["preferred_username"] = "joe", ["exp"] = now - 3600 },
            _ => new() { ["preferred_username"] = "joe" },
        });
        Assert.Null(back!.User);
        Assert.NotNull(back.Error);
    }

    [Fact]
    public async Task AStateWorksOnceAndUnknownStatesAreIgnored()
    {
        var start = _sso.Start(Tv);
        var asked = Query(await _sso.AuthorizeUrl(start.Code, "https://jf.example/cb"));
        _provider.LastNonce = asked["nonce"];
        _provider.Claims = _ => new() { ["preferred_username"] = "joe" };
        Assert.Null(await _sso.Callback("made-up", "c", null, null));
        Assert.NotNull(await _sso.Callback(asked["state"], "c", null, null));
        Assert.Null(await _sso.Callback(asked["state"], "c", null, null));
    }

    [Fact]
    public async Task TheProviderRefusingIsReported()
    {
        var start = _sso.Start(Tv);
        var asked = Query(await _sso.AuthorizeUrl(start.Code, "https://jf.example/cb"));
        var back = await _sso.Callback(asked["state"], null, "access_denied", "User cancelled");
        Assert.Contains("User cancelled", back!.Error, StringComparison.Ordinal);
        Assert.Equal(SsoState.Failed, (await _sso.Poll(start.Code, start.Secret)).State);
    }

    [Fact]
    public void NothingStartsWhenSwitchedOff()
    {
        _settings = _settings with { Enabled = false };
        Assert.Throws<InvalidOperationException>(() => _sso.Start(Tv));
    }
}
