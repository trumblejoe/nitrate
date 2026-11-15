using MediaBrowser.Model.Plugins;

namespace Nitrate.Companion.Configuration;

/// <summary>The plugin's settings (none yet that need changing).</summary>
public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>Gets or sets a value indicating whether chat in watch-together sessions is on.</summary>
    public bool EnableChat { get; set; } = true;

    /// <summary>
    /// Gets or sets a value indicating whether signed-in users see everyone's names when sharing
    /// a script. Off: they invite by typing an exact user name.
    /// </summary>
    public bool ListUsersForSharing { get; set; } = true;
}
