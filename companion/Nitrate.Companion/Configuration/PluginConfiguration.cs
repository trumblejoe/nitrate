using MediaBrowser.Model.Plugins;

namespace Nitrate.Companion.Configuration;

/// <summary>The plugin's settings (none yet that need changing).</summary>
public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>
    /// Gets or sets a value indicating whether subtitles are indexed nightly so people can search
    /// what's said in films and episodes.
    /// </summary>
    public bool EnableLines { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether each user's watches are kept in a diary.</summary>
    public bool EnableDiary { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether chat in watch-together sessions is on.</summary>
    public bool EnableChat { get; set; } = true;

    /// <summary>
    /// Gets or sets a value indicating whether signed-in users see everyone's names when sharing
    /// a script. Off: they invite by typing an exact user name.
    /// </summary>
    public bool ListUsersForSharing { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the apps offer signing in with the identity provider.</summary>
    public bool SsoEnabled { get; set; }

    /// <summary>Gets or sets the provider's name on the sign-in button ("Sign in with …").</summary>
    public string SsoProviderName { get; set; } = string.Empty;

    /// <summary>Gets or sets the provider's issuer address (its discovery document is under it).</summary>
    public string OidcIssuer { get; set; } = string.Empty;

    /// <summary>Gets or sets the client id registered with the provider.</summary>
    public string OidcClientId { get; set; } = string.Empty;

    /// <summary>Gets or sets the client secret (empty for a public client: PKCE alone).</summary>
    public string OidcClientSecret { get; set; } = string.Empty;

    /// <summary>Gets or sets the scopes asked for.</summary>
    public string OidcScopes { get; set; } = "openid profile email";

    /// <summary>Gets or sets the claim matched against Jellyfin user names.</summary>
    public string OidcUsernameClaim { get; set; } = "preferred_username";

    /// <summary>Gets or sets a value indicating whether a verified email also matches a Jellyfin user named after it.</summary>
    public bool OidcMatchByEmail { get; set; } = true;

    /// <summary>Gets or sets the claim listing the person's groups.</summary>
    public string OidcGroupsClaim { get; set; } = "groups";

    /// <summary>Gets or sets a group the person must be in (empty: anyone the provider signs in).</summary>
    public string OidcRequiredGroup { get; set; } = string.Empty;

    /// <summary>Gets or sets "identity = Jellyfin user" lines for people whose names differ.</summary>
    public string SsoUserLinks { get; set; } = string.Empty;

    /// <summary>Gets or sets the address the provider sends people back to (empty: the one the app used).</summary>
    public string SsoPublicUrl { get; set; } = string.Empty;

    /// <summary>
    /// Gets or sets the accounts an admin keeps private: never listed to others or findable by name
    /// (people can also make themselves private in Nitrate).
    /// </summary>
#pragma warning disable CA1819 // Jellyfin's XML settings need an array.
    public string[] PrivateUserIds { get; set; } = Array.Empty<string>();

    /// <summary>Gets or sets the rule-based collections the plugin keeps up to date.</summary>
    public Nitrate.Companion.SmartCollections.SmartRule[] SmartCollections { get; set; } = Array.Empty<Nitrate.Companion.SmartCollections.SmartRule>();
#pragma warning restore CA1819
}
