using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Nitrate.Companion.Sso;

/// <summary>Why an id token was refused.</summary>
public class TokenException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="TokenException"/> class.</summary>
    public TokenException(string message)
        : base(message)
    {
    }
}

/// <summary>
/// Just enough JSON Web Token handling for OpenID Connect id tokens: decoding, and checking the
/// signature against the provider's published keys (RSA, RSA-PSS, ECDSA) or the client secret
/// (HMAC), with the framework's own cryptography.
/// </summary>
public static class Jwt
{
    /// <summary>Decodes base64url.</summary>
    public static byte[] FromBase64Url(string s)
    {
        var b = s.Replace('-', '+').Replace('_', '/');
        b += (b.Length % 4) switch { 2 => "==", 3 => "=", _ => string.Empty };
        return Convert.FromBase64String(b);
    }

    /// <summary>Encodes base64url without padding.</summary>
    public static string ToBase64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>A token's header and claims, unchecked.</summary>
    public static (JsonElement Header, JsonElement Claims) Decode(string token)
    {
        var parts = token.Split('.');
        if (parts.Length != 3)
        {
            throw new TokenException("Not a signed token");
        }

        try
        {
            return (
                JsonDocument.Parse(FromBase64Url(parts[0])).RootElement.Clone(),
                JsonDocument.Parse(FromBase64Url(parts[1])).RootElement.Clone());
        }
        catch (Exception e) when (e is FormatException or JsonException)
        {
            throw new TokenException("The token couldn't be read");
        }
    }

    /// <summary>
    /// Checks the token's signature with the key set <paramref name="jwks"/> (or
    /// <paramref name="secret"/> for HMAC). Returns false when no key fits, so the caller can
    /// fetch the provider's keys again (they rotate).
    /// </summary>
    public static bool VerifySignature(string token, JsonElement jwks, string? secret)
    {
        var parts = token.Split('.');
        var (header, _) = Decode(token);
        var alg = header.TryGetProperty("alg", out var a) ? a.GetString() : null;
        var kid = header.TryGetProperty("kid", out var k) ? k.GetString() : null;
        var data = Encoding.ASCII.GetBytes(parts[0] + "." + parts[1]);
        var sig = FromBase64Url(parts[2]);

        if (alg is "HS256" or "HS384" or "HS512")
        {
            if (string.IsNullOrEmpty(secret))
            {
                return false;
            }

            var key = Encoding.UTF8.GetBytes(secret);
            var mac = alg switch
            {
                "HS256" => HMACSHA256.HashData(key, data),
                "HS384" => HMACSHA384.HashData(key, data),
                _ => HMACSHA512.HashData(key, data),
            };
            return CryptographicOperations.FixedTimeEquals(mac, sig);
        }

        var hash = alg switch
        {
            "RS256" or "PS256" or "ES256" => HashAlgorithmName.SHA256,
            "RS384" or "PS384" or "ES384" => HashAlgorithmName.SHA384,
            "RS512" or "PS512" or "ES512" => HashAlgorithmName.SHA512,
            _ => throw new TokenException($"Unsupported signature ({alg ?? "none"})"),
        };

        if (!jwks.TryGetProperty("keys", out var keys) || keys.ValueKind != JsonValueKind.Array)
        {
            return false;
        }

        foreach (var jwk in keys.EnumerateArray())
        {
            var keyId = jwk.TryGetProperty("kid", out var ki) ? ki.GetString() : null;
            if (kid is not null && keyId is not null && keyId != kid)
            {
                continue;
            }

            if (jwk.TryGetProperty("use", out var use) && use.GetString() is { } u && u != "sig")
            {
                continue;
            }

            var kty = jwk.TryGetProperty("kty", out var t) ? t.GetString() : null;
            try
            {
                if (alg[0] is 'R' or 'P' && kty == "RSA")
                {
                    using var rsa = RSA.Create(new RSAParameters
                    {
                        Modulus = FromBase64Url(jwk.GetProperty("n").GetString()!),
                        Exponent = FromBase64Url(jwk.GetProperty("e").GetString()!),
                    });
                    var padding = alg[0] == 'R' ? RSASignaturePadding.Pkcs1 : RSASignaturePadding.Pss;
                    if (rsa.VerifyData(data, sig, hash, padding))
                    {
                        return true;
                    }
                }
                else if (alg[0] == 'E' && kty == "EC")
                {
                    var curve = jwk.GetProperty("crv").GetString() switch
                    {
                        "P-256" => ECCurve.NamedCurves.nistP256,
                        "P-384" => ECCurve.NamedCurves.nistP384,
                        "P-521" => ECCurve.NamedCurves.nistP521,
                        _ => throw new TokenException("Unsupported key curve"),
                    };
                    using var ec = ECDsa.Create(new ECParameters
                    {
                        Curve = curve,
                        Q = new ECPoint
                        {
                            X = FromBase64Url(jwk.GetProperty("x").GetString()!),
                            Y = FromBase64Url(jwk.GetProperty("y").GetString()!),
                        },
                    });
                    if (ec.VerifyData(data, sig, hash))
                    {
                        return true;
                    }
                }
            }
            catch (Exception e) when (e is CryptographicException or FormatException or KeyNotFoundException or InvalidOperationException)
            {
                // A key this can't use: try the next.
            }
        }

        return false;
    }
}
