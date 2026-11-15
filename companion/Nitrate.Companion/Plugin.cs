using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;
using Nitrate.Companion.Configuration;

namespace Nitrate.Companion;

/// <summary>
/// Nitrate Companion: server-side features for the Nitrate apps that Jellyfin has no API for,
/// starting with chat during watch-together sessions.
/// </summary>
public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    /// <summary>The plugin's id. Jellyfin stores its settings under it: never change it.</summary>
    public static readonly Guid PluginId = Guid.Parse("04250121-a5d6-40da-99fc-242a178b3d2c");

    /// <summary>Initializes a new instance of the <see cref="Plugin"/> class.</summary>
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
    }

    /// <summary>Gets the running plugin, once the server has loaded it.</summary>
    public static Plugin? Instance { get; private set; }

    /// <inheritdoc />
    public override string Name => "Nitrate Companion";

    /// <inheritdoc />
    public override Guid Id => PluginId;

    /// <inheritdoc />
    public override string Description => "Chat during watch-together, shared screenplays, single sign-on and channels for the Nitrate apps.";

    /// <inheritdoc />
    public IEnumerable<PluginPageInfo> GetPages() => new[]
    {
        new PluginPageInfo { Name = Name, EmbeddedResourcePath = GetType().Namespace + ".Configuration.configPage.html" },
    };
}
