using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Nitrate.Companion.Sso;

/// <summary>The device asking to be signed in.</summary>
public record SsoDevice(string DeviceId, string DeviceName, string App, string AppVersion);

/// <summary>The server's side of Jellyfin accounts, for sign-in.</summary>
public interface ISsoAccounts
{
    /// <summary>The enabled Jellyfin user named <paramref name="name"/> (any case), if there is one.</summary>
    (Guid Id, string Name)? FindUser(string name);

    /// <summary>Signs <paramref name="device"/> in as the user and returns its access token.</summary>
    Task<string> SignIn(Guid userId, SsoDevice device);
}

/// <summary>A sign-in waiting for someone to finish it at the provider.</summary>
public class PendingSignIn
{
    /// <summary>Gets the short code shown on the device and in the browser.</summary>
    public required string Code { get; init; }

    /// <summary>Gets the secret only the device knows, which collects the result.</summary>
    public required string Secret { get; init; }

    /// <summary>Gets the device to sign in.</summary>
    public required SsoDevice Device { get; init; }

    /// <summary>Gets when it lapses.</summary>
    public required DateTimeOffset Expires { get; init; }

    /// <summary>Gets or sets the state sent to the provider, once the browser has gone there.</summary>
    public string? State { get; set; }

    /// <summary>Gets or sets the nonce expected back in the id token.</summary>
    public string? Nonce { get; set; }

    /// <summary>Gets or sets the PKCE verifier.</summary>
    public string? Verifier { get; set; }

    /// <summary>Gets or sets the address the provider returned to.</summary>
    public string? RedirectUri { get; set; }

    /// <summary>Gets or sets the Jellyfin user to sign in as, once the provider said who it is.</summary>
    public (Guid Id, string Name)? User { get; set; }

    /// <summary>Gets or sets why it failed.</summary>
    public string? Error { get; set; }
}

/// <summary>Where a sign-in is, as the device sees it.</summary>
public enum SsoState
{
    /// <summary>Not finished in the browser yet.</summary>
    Waiting,

    /// <summary>Signed in: the token is in the result.</summary>
    Done,

    /// <summary>Refused (the message says why).</summary>
    Failed,

    /// <summary>Unknown or lapsed: start again.</summary>
    Expired,
}

/// <summary>A sign-in's state, as the device collects it.</summary>
public record SsoPoll(SsoState State, string? Message = null, string? AccessToken = null, Guid? UserId = null, string? UserName = null);

/// <summary>
/// Signing in to Jellyfin with an OpenID Connect provider. A device starts a sign-in and shows
/// its code (or opens it in the browser); a person finishes it in a browser, on any device, at
/// the provider (authorization code with PKCE; the id token's signature, issuer, audience,
/// lifetime and nonce are checked); the device collects a Jellyfin token with its secret.
/// Only existing Jellyfin users can sign in: by user name, verified email, or a link an admin set.
/// </summary>
public class SsoService
{
    /// <summary>How long a started sign-in waits.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(10);

    private const int MaxPending = 200;
    private const string CodeChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static readonly TimeSpan Skew = TimeSpan.FromMinutes(2);

    private readonly Func<SsoSettings> _settings;
    private readonly ISsoAccounts _accounts;
    private readonly HttpClient _http;
    private readonly TimeProvider _time;
    private readonly ConcurrentDictionary<string, PendingSignIn> _pending = new();
    private readonly SemaphoreSlim _metaLock = new(1, 1);
    private (string Issuer, JsonElement Doc, DateTimeOffset At)? _discovery;
    private (string Uri, JsonElement Keys, DateTimeOffset At)? _jwks;

    /// <summary>Initializes a new instance of the <see cref="SsoService"/> class.</summary>
    public SsoService(Func<SsoSettings> settings, ISsoAccounts accounts, HttpClient http, TimeProvider time)
    {
        _settings = settings;
        _accounts = accounts;
        _http = http;
        _time = time;
    }

    /// <summary>Gets the current settings.</summary>
    public SsoSettings Settings => _settings();

    /// <summary>Starts a sign-in for <paramref name="device"/>.</summary>
    public PendingSignIn Start(SsoDevice device)
    {
        if (!Settings.Ready)
        {
            throw new InvalidOperationException("Single sign-on isn't set up on this server.");
        }

        Prune();
        if (_pending.Count >= MaxPending)
        {
            throw new InvalidOperationException("Too many sign-ins are waiting. Try again in a few minutes.");
        }

        while (true)
        {
            var code = new string(Enumerable.Range(0, 8).Select(_ => CodeChars[RandomNumberGenerator.GetInt32(CodeChars.Length)]).ToArray());
            var pending = new PendingSignIn
            {
                Code = code,
                Secret = Jwt.ToBase64Url(RandomNumberGenerator.GetBytes(32)),
                Device = device,
                Expires = _time.GetUtcNow() + Lifetime,
            };
            if (_pending.TryAdd(code, pending))
            {
                return pending;
            }
        }
    }

    /// <summary>The waiting sign-in with <paramref name="code"/> (any case, dashes and spaces ignored).</summary>
    public PendingSignIn? Find(string code)
    {
        var key = new string(code.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
        return _pending.TryGetValue(key, out var p) && p.Expires > _time.GetUtcNow() ? p : null;
    }

    /// <summary>Where to send the browser to sign in at the provider, for the sign-in <paramref name="code"/>.</summary>
    public async Task<string> AuthorizeUrl(string code, string redirectUri, CancellationToken ct = default)
    {
        var p = Find(code) ?? throw new InvalidOperationException("This sign-in has lapsed. Start again on your device.");
        var s = Settings;
        var meta = await Discovery(s, ct).ConfigureAwait(false);
        var endpoint = meta.GetProperty("authorization_endpoint").GetString()!;
        p.State = Jwt.ToBase64Url(RandomNumberGenerator.GetBytes(24));
        p.Nonce = Jwt.ToBase64Url(RandomNumberGenerator.GetBytes(24));
        p.Verifier = Jwt.ToBase64Url(RandomNumberGenerator.GetBytes(32));
        p.RedirectUri = redirectUri;
        p.Error = null;
        var challenge = Jwt.ToBase64Url(SHA256.HashData(Encoding.ASCII.GetBytes(p.Verifier)));
        var query = new Dictionary<string, string>
        {
            ["response_type"] = "code",
            ["client_id"] = s.ClientId,
            ["redirect_uri"] = redirectUri,
            ["scope"] = s.Scopes.Contains("openid", StringComparison.Ordinal) ? s.Scopes : "openid " + s.Scopes,
            ["state"] = p.State,
            ["nonce"] = p.Nonce,
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
        };
        var sep = endpoint.Contains('?', StringComparison.Ordinal) ? "&" : "?";
        return endpoint + sep + string.Join("&", query.Select(kv => kv.Key + "=" + Uri.EscapeDataString(kv.Value)));
    }

    /// <summary>
    /// The provider sent the browser back: exchanges the code, checks the id token and finds the
    /// Jellyfin user. Returns the sign-in (with its <see cref="PendingSignIn.User"/> or <see cref="PendingSignIn.Error"/>).
    /// </summary>
    public async Task<PendingSignIn?> Callback(string? state, string? code, string? error, string? errorDescription, CancellationToken ct = default)
    {
        var p = state is null ? null : _pending.Values.FirstOrDefault(x => x.State is not null && CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(x.State), Encoding.ASCII.GetBytes(state)));
        if (p is null || p.Expires <= _time.GetUtcNow())
        {
            return null;
        }

        // A state is good once.
        p.State = null;
        if (error is not null || code is null)
        {
            p.Error = "The sign-in was refused: " + (errorDescription ?? error ?? "no code came back");
            return p;
        }

        try
        {
            var claims = await Exchange(p, code, ct).ConfigureAwait(false);
            p.User = Match(claims);
        }
        catch (Exception e) when (e is TokenException or HttpRequestException or InvalidOperationException or JsonException or KeyNotFoundException or TaskCanceledException)
        {
            p.Error = e.Message;
        }

        return p;
    }

    /// <summary>
    /// The device collects its sign-in: once done, signs it in to Jellyfin and forgets the
    /// sign-in (a result is collected once).
    /// </summary>
    public async Task<SsoPoll> Poll(string code, string secret)
    {
        var p = Find(code);
        if (p is null || !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(p.Secret), Encoding.ASCII.GetBytes(secret)))
        {
            return new SsoPoll(SsoState.Expired, "This sign-in has lapsed. Start again.");
        }

        if (p.Error is not null)
        {
            _pending.TryRemove(p.Code, out _);
            return new SsoPoll(SsoState.Failed, p.Error);
        }

        if (p.User is not { } user || !_pending.TryRemove(p.Code, out _))
        {
            return new SsoPoll(SsoState.Waiting);
        }

        var token = await _accounts.SignIn(user.Id, p.Device).ConfigureAwait(false);
        return new SsoPoll(SsoState.Done, null, token, user.Id, user.Name);
    }

    /// <summary>Which Jellyfin user the provider's <paramref name="claims"/> are, or why none.</summary>
    public (Guid Id, string Name) Match(JsonElement claims)
    {
        var s = Settings;
        string? Claim(string name) => claims.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

        if (s.RequiredGroup.Length > 0)
        {
            var groups = claims.TryGetProperty(s.GroupsClaim, out var g)
                ? g.ValueKind == JsonValueKind.Array ? g.EnumerateArray().Select(x => x.ToString()).ToList()
                : new List<string> { g.ToString() }
                : new List<string>();
            if (!groups.Contains(s.RequiredGroup, StringComparer.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException($"Your account isn't in the \"{s.RequiredGroup}\" group this server needs.");
            }
        }

        var subject = Claim("sub");
        var username = Claim(s.UsernameClaim);
        var email = Claim("email");
        var emailVerified = !claims.TryGetProperty("email_verified", out var ev) || ev.ValueKind != JsonValueKind.False;

        foreach (var key in new[] { subject, email, username })
        {
            if (key is not null && s.Links.TryGetValue(key, out var linked) && _accounts.FindUser(linked) is { } u)
            {
                return u;
            }
        }

        if (username is not null && _accounts.FindUser(username) is { } byName)
        {
            return byName;
        }

        if (s.MatchByEmail && emailVerified && email is not null && _accounts.FindUser(email) is { } byEmail)
        {
            return byEmail;
        }

        throw new InvalidOperationException(
            $"No Jellyfin account matches {username ?? email ?? subject ?? "you"}. Ask the server's admin to add one or link yours.");
    }

    private async Task<JsonElement> Exchange(PendingSignIn p, string code, CancellationToken ct)
    {
        var s = Settings;
        var meta = await Discovery(s, ct).ConfigureAwait(false);
        var form = new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code",
            ["code"] = code,
            ["redirect_uri"] = p.RedirectUri!,
            ["code_verifier"] = p.Verifier!,
            ["client_id"] = s.ClientId,
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, meta.GetProperty("token_endpoint").GetString());
        if (s.ClientSecret.Length > 0)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue(
                "Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(Uri.EscapeDataString(s.ClientId) + ":" + Uri.EscapeDataString(s.ClientSecret))));
        }

        request.Content = new FormUrlEncodedContent(form);
        using var response = await _http.SendAsync(request, ct).ConfigureAwait(false);
        var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"The provider refused the sign-in ({(int)response.StatusCode}): {Short(body)}");
        }

        var tokens = JsonDocument.Parse(body).RootElement;
        var idToken = tokens.TryGetProperty("id_token", out var it) ? it.GetString() : null;
        if (idToken is null)
        {
            throw new TokenException("The provider sent no id token (is \"openid\" among the scopes?)");
        }

        var claims = await Validate(idToken, p.Nonce!, meta, s, ct).ConfigureAwait(false);

        // Some providers keep the user name out of the id token: ask for it.
        if (!claims.TryGetProperty(s.UsernameClaim, out _)
            && meta.TryGetProperty("userinfo_endpoint", out var ui)
            && tokens.TryGetProperty("access_token", out var at))
        {
            using var infoRequest = new HttpRequestMessage(HttpMethod.Get, ui.GetString());
            infoRequest.Headers.Authorization = new AuthenticationHeaderValue("Bearer", at.GetString());
            using var info = await _http.SendAsync(infoRequest, ct).ConfigureAwait(false);
            if (info.IsSuccessStatusCode)
            {
                var more = JsonDocument.Parse(await info.Content.ReadAsStringAsync(ct).ConfigureAwait(false)).RootElement;
                if (more.TryGetProperty("sub", out var sub) && sub.GetString() == claims.GetProperty("sub").GetString())
                {
                    var merged = new Dictionary<string, JsonElement>();
                    foreach (var prop in more.EnumerateObject())
                    {
                        merged[prop.Name] = prop.Value.Clone();
                    }

                    foreach (var prop in claims.EnumerateObject())
                    {
                        merged[prop.Name] = prop.Value.Clone();
                    }

                    claims = JsonSerializer.SerializeToElement(merged);
                }
            }
        }

        return claims;
    }

    /// <summary>Checks an id token and returns its claims.</summary>
    public async Task<JsonElement> Validate(string idToken, string nonce, JsonElement meta, SsoSettings s, CancellationToken ct)
    {
        var (_, claims) = Jwt.Decode(idToken);
        var jwksUri = meta.TryGetProperty("jwks_uri", out var j) ? j.GetString() : null;
        var verified = false;
        if (jwksUri is not null)
        {
            verified = Jwt.VerifySignature(idToken, await Keys(jwksUri, false, ct).ConfigureAwait(false), s.ClientSecret)
                || Jwt.VerifySignature(idToken, await Keys(jwksUri, true, ct).ConfigureAwait(false), s.ClientSecret);
        }
        else
        {
            verified = Jwt.VerifySignature(idToken, default, s.ClientSecret);
        }

        if (!verified)
        {
            throw new TokenException("The id token's signature didn't check out.");
        }

        var issuer = meta.TryGetProperty("issuer", out var iss) ? iss.GetString() : s.Issuer;
        if (Str(claims, "iss") is not { } tokenIssuer || tokenIssuer.TrimEnd('/') != issuer?.TrimEnd('/'))
        {
            throw new TokenException("The id token is from another issuer.");
        }

        var audiences = claims.TryGetProperty("aud", out var aud)
            ? aud.ValueKind == JsonValueKind.Array ? aud.EnumerateArray().Select(a => a.GetString()).ToList() : new List<string?> { aud.GetString() }
            : new List<string?>();
        if (!audiences.Contains(s.ClientId) || (audiences.Count > 1 && Str(claims, "azp") is { } azp && azp != s.ClientId))
        {
            throw new TokenException("The id token is for another application.");
        }

        var now = _time.GetUtcNow();
        if (!claims.TryGetProperty("exp", out var exp) || DateTimeOffset.FromUnixTimeSeconds(exp.GetInt64()) + Skew < now)
        {
            throw new TokenException("The id token has expired.");
        }

        if (claims.TryGetProperty("iat", out var iat) && DateTimeOffset.FromUnixTimeSeconds(iat.GetInt64()) - Skew > now)
        {
            throw new TokenException("The id token is from the future (check the server's clock).");
        }

        if (Str(claims, "nonce") != nonce)
        {
            throw new TokenException("The id token wasn't made for this sign-in.");
        }

        if (Str(claims, "sub") is null)
        {
            throw new TokenException("The id token names no one.");
        }

        return claims;
    }

    private static string? Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static string Short(string s) => s.Length > 200 ? s[..200] + "…" : s;

    private async Task<JsonElement> Discovery(SsoSettings s, CancellationToken ct)
    {
        var now = _time.GetUtcNow();
        if (_discovery is { } d && d.Issuer == s.Issuer && now - d.At < TimeSpan.FromHours(1))
        {
            return d.Doc;
        }

        await _metaLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            var body = await _http.GetStringAsync(s.Issuer + "/.well-known/openid-configuration", ct).ConfigureAwait(false);
            var doc = JsonDocument.Parse(body).RootElement.Clone();
            if (!doc.TryGetProperty("authorization_endpoint", out _) || !doc.TryGetProperty("token_endpoint", out _))
            {
                throw new InvalidOperationException("The provider's discovery document has no sign-in addresses.");
            }

            _discovery = (s.Issuer, doc, now);
            return doc;
        }
        finally
        {
            _metaLock.Release();
        }
    }

    private async Task<JsonElement> Keys(string uri, bool fresh, CancellationToken ct)
    {
        var now = _time.GetUtcNow();
        if (!fresh && _jwks is { } k && k.Uri == uri && now - k.At < TimeSpan.FromHours(6))
        {
            return k.Keys;
        }

        var keys = JsonDocument.Parse(await _http.GetStringAsync(uri, ct).ConfigureAwait(false)).RootElement.Clone();
        _jwks = (uri, keys, now);
        return keys;
    }

    private void Prune()
    {
        var now = _time.GetUtcNow();
        foreach (var (code, p) in _pending)
        {
            if (p.Expires <= now)
            {
                _pending.TryRemove(code, out _);
            }
        }
    }
}
