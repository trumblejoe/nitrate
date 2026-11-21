using System.IO.Compression;
using System.Xml.Linq;

namespace Nitrate.Companion.Comics;

/// <summary>A book whose ComicInfo can't be changed here (not a CBZ), with why.</summary>
public class ComicInfoException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="ComicInfoException"/> class.</summary>
    public ComicInfoException(string message)
        : base(message)
    {
    }
}

/// <summary>
/// Changes a CBZ's ComicInfo.xml (the Anansi/ComicRack schema that Jellyfin's Bookshelf plugin, CLU
/// and readers read): the named fields are set or removed and everything else in the file (pages,
/// other fields) is kept. The book is rewritten beside itself and swapped in, so a failure never
/// leaves half a file.
/// </summary>
public static class ComicInfoEditor
{
    /// <summary>The fields that can be changed, as ComicInfo names them.</summary>
    public static readonly IReadOnlyList<string> Fields =
    [
        "Series", "Number", "Count", "Volume", "Title", "Summary", "Year", "Month", "Day",
        "Writer", "Penciller", "Inker", "Colorist", "Letterer", "CoverArtist", "Editor",
        "Publisher", "Imprint", "Genre", "Tags", "LanguageISO", "AgeRating", "Manga", "StoryArc", "SeriesGroup",
    ];

    /// <summary>
    /// Sets each field in <paramref name="changes"/> (null or empty removes it) in the CBZ at
    /// <paramref name="path"/>, and returns the new ComicInfo.xml.
    /// </summary>
    public static string Apply(string path, IReadOnlyDictionary<string, string?> changes)
    {
        if (ComicStore.Format(path) != "zip")
        {
            throw new ComicInfoException("Only CBZ books can be changed here. Convert CBR and others to CBZ first (CLU can convert a whole library).");
        }

        var unknown = changes.Keys.FirstOrDefault(k => !Fields.Contains(k));
        if (unknown is not null)
        {
            throw new ComicInfoException($"\"{unknown}\" isn't a ComicInfo field Nitrate changes.");
        }

        var temp = path + ".nitrate-tmp";
        File.Copy(path, temp, overwrite: true);
        string xml;
        try
        {
            using (var zip = ZipFile.Open(temp, ZipArchiveMode.Update))
            {
                var entry = zip.Entries.FirstOrDefault(e => e.Name.Equals("ComicInfo.xml", StringComparison.OrdinalIgnoreCase));
                XDocument doc;
                if (entry is null)
                {
                    XNamespace xsi = "http://www.w3.org/2001/XMLSchema-instance";
                    XNamespace xsd = "http://www.w3.org/2001/XMLSchema";
                    doc = new XDocument(new XDeclaration("1.0", "utf-8", null), new XElement("ComicInfo", new XAttribute(XNamespace.Xmlns + "xsi", xsi), new XAttribute(XNamespace.Xmlns + "xsd", xsd)));
                }
                else
                {
                    using (var s = entry.Open())
                    {
                        doc = XDocument.Load(s);
                    }

                    entry.Delete();
                }

                var root = doc.Root ?? throw new ComicInfoException("The book's ComicInfo.xml has no content.");
                foreach (var (name, value) in changes)
                {
                    var element = root.Elements().FirstOrDefault(e => e.Name.LocalName == name);
                    if (string.IsNullOrWhiteSpace(value))
                    {
                        element?.Remove();
                    }
                    else if (element is null)
                    {
                        // Before the page list, where ComicInfo keeps its fields.
                        var pages = root.Elements().FirstOrDefault(e => e.Name.LocalName == "Pages");
                        var added = new XElement(root.Name.Namespace + name, value.Trim());
                        if (pages is null)
                        {
                            root.Add(added);
                        }
                        else
                        {
                            pages.AddBeforeSelf(added);
                        }
                    }
                    else
                    {
                        element.Value = value.Trim();
                    }
                }

                xml = doc.Declaration + Environment.NewLine + doc.ToString();
                var written = zip.CreateEntry("ComicInfo.xml", CompressionLevel.Optimal);
                using var w = new StreamWriter(written.Open(), new System.Text.UTF8Encoding(false));
                w.Write(xml);
            }

            File.Move(temp, path, overwrite: true);
        }
        catch
        {
            if (File.Exists(temp))
            {
                File.Delete(temp);
            }

            throw;
        }

        return xml;
    }
}
