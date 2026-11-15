using Nitrate.Companion.Configuration;

namespace Nitrate.Companion.Sso;

/// <summary>The single sign-on settings, read from the plugin's configuration each time.</summary>
public record SsoSettings(
    bool Enabled,
    string ProviderName,
    string Issuer,
    string ClientId,
    string ClientSecret,
    string Scopes,
    string UsernameClaim,
    bool MatchByEmail,
    string GroupsClaim,
    string RequiredGroup,
    IReadOnlyDictionary<string, string> Links,
    string PublicUrl)
{
    /// <summary>Whether sign-in can be offered: switched on and filled in.</summary>
    public bool Ready => Enabled && Issuer.Length > 0 && ClientId.Length > 0;

    /// <summary>The settings in <paramref name="c"/> (none: switched off).</summary>
    public static SsoSettings From(PluginConfiguration? c)
    {
        c ??= new PluginConfiguration { SsoEnabled = false };
        return new SsoSettings(
            c.SsoEnabled,
            string.IsNullOrWhiteSpace(c.SsoProviderName) ? "single sign-on" : c.SsoProviderName.Trim(),
            (c.OidcIssuer ?? string.Empty).Trim().TrimEnd('/'),
            (c.OidcClientId ?? string.Empty).Trim(),
            c.OidcClientSecret ?? string.Empty,
            string.IsNullOrWhiteSpace(c.OidcScopes) ? "openid profile email" : c.OidcScopes.Trim(),
            string.IsNullOrWhiteSpace(c.OidcUsernameClaim) ? "preferred_username" : c.OidcUsernameClaim.Trim(),
            c.OidcMatchByEmail,
            string.IsNullOrWhiteSpace(c.OidcGroupsClaim) ? "groups" : c.OidcGroupsClaim.Trim(),
            (c.OidcRequiredGroup ?? string.Empty).Trim(),
            ParseLinks(c.SsoUserLinks),
            (c.SsoPublicUrl ?? string.Empty).Trim().TrimEnd('/'));
    }

    /// <summary>
    /// "identity = Jellyfin user" lines: an identity (the provider's subject, email or user
    /// name) signs in as that Jellyfin user. Blank lines and lines starting with # are skipped.
    /// </summary>
    public static IReadOnlyDictionary<string, string> ParseLinks(string? text)
    {
        var links = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var raw in (text ?? string.Empty).Split('\n'))
        {
            var line = raw.Trim();
            var eq = line.IndexOf('=', StringComparison.Ordinal);
            if (line.Length == 0 || line.StartsWith('#') || eq <= 0)
            {
                continue;
            }

            var key = line[..eq].Trim();
            var user = line[(eq + 1)..].Trim();
            if (key.Length > 0 && user.Length > 0)
            {
                links[key] = user;
            }
        }

        return links;
    }
}
