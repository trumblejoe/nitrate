using System.Text;

namespace Nitrate.Companion.Catalogs;

/// <summary>What the apps are told about a catalog: never its sign-in details.</summary>
/// <param name="Id">The catalog's id.</param>
/// <param name="Name">Its name.</param>
/// <param name="Url">Its address (where browsing starts).</param>
/// <param name="Kind">"OPDS" or "CLU".</param>
public record CatalogInfo(string Id, string Name, string Url, string Kind);

/// <summary>Who may be reached through a catalog, and how the plugin signs in to it.</summary>
public static class CatalogAccess
{
    /// <summary>The catalogs set up and usable (a name, an id and an http(s) address).</summary>
    public static IReadOnlyList<CatalogSettings> Usable(IEnumerable<CatalogSettings>? all) =>
        (all ?? []).Where(c => !string.IsNullOrWhiteSpace(c.Id) && !string.IsNullOrWhiteSpace(c.Name) && Base(c) is not null).ToList();

    /// <summary>What the apps see of <paramref name="c"/>.</summary>
    public static CatalogInfo Public(CatalogSettings c) =>
        new(c.Id, c.Name.Trim(), c.Url.Trim(), string.Equals(c.Kind, "CLU", StringComparison.OrdinalIgnoreCase) ? "CLU" : "OPDS");

    /// <summary>The catalog's address, when it's a proper http(s) one.</summary>
    public static Uri? Base(CatalogSettings c) =>
        Uri.TryCreate(c.Url.Trim(), UriKind.Absolute, out var u) && (u.Scheme == Uri.UriSchemeHttp || u.Scheme == Uri.UriSchemeHttps) ? u : null;

    /// <summary>
    /// True when <paramref name="url"/> is on the catalog's own server (same scheme, host and
    /// port): the plugin only ever fetches from there, so it can't be used to reach anything else.
    /// </summary>
    public static bool Allows(CatalogSettings c, string url, out Uri target)
    {
        target = null!;
        if (Base(c) is not { } home || !Uri.TryCreate(url, UriKind.Absolute, out var u) || u.Scheme != home.Scheme)
        {
            return false;
        }

        if (!string.Equals(u.Host, home.Host, StringComparison.OrdinalIgnoreCase) || u.Port != home.Port || !string.IsNullOrEmpty(u.UserInfo))
        {
            return false;
        }

        target = u;
        return true;
    }

    /// <summary>The Authorization header the catalog wants, if any.</summary>
    public static string? Authorization(CatalogSettings c)
    {
        if (!string.IsNullOrWhiteSpace(c.Token))
        {
            return "Bearer " + c.Token.Trim();
        }

        return string.IsNullOrWhiteSpace(c.User) ? null : "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(c.User.Trim() + ":" + c.Password));
    }
}
