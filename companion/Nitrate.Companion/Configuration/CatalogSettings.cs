namespace Nitrate.Companion.Catalogs;

/// <summary>
/// A comic catalog the server's admin set up (plugin settings): everyone signed in to the server
/// can browse and read it through the plugin, which signs in for them, so the sign-in details
/// never leave the server.
/// </summary>
public class CatalogSettings
{
    /// <summary>Gets or sets the catalog's id (made by the settings page; a storage key for reading positions).</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Gets or sets its name as people see it.</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets the address: an OPDS feed, or CLU's own address.</summary>
    public string Url { get; set; } = string.Empty;

    /// <summary>Gets or sets what kind of server it is: "OPDS" or "CLU".</summary>
    public string Kind { get; set; } = "OPDS";

    /// <summary>Gets or sets the user name (OPDS with a password).</summary>
    public string User { get; set; } = string.Empty;

    /// <summary>Gets or sets the password.</summary>
    public string Password { get; set; } = string.Empty;

    /// <summary>Gets or sets a bearer token (CLU's API token, or an OPDS server that takes one).</summary>
    public string Token { get; set; } = string.Empty;
}
