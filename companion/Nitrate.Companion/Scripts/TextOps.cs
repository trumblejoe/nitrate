using System.Text;
using System.Text.Json;

namespace Nitrate.Companion.Scripts;

/// <summary>
/// Applies a text change in the apps' wire form: a JSON array walking the whole text, where a
/// positive number keeps that many characters, a negative one deletes, and a string inserts.
/// Lengths are UTF-16 units, as C# and Kotlin both count them. The server only applies changes
/// (the apps rebase their own changes onto the server's), so it never has to merge anything.
/// </summary>
public static class TextOps
{
    /// <summary>Most parts one change may have.</summary>
    public const int MaxParts = 20_000;

    /// <summary>Applies <paramref name="ops"/> to <paramref name="text"/>.</summary>
    /// <exception cref="ArgumentException">The change doesn't fit the text, or isn't a change.</exception>
    public static string Apply(string text, JsonElement ops)
    {
        if (ops.ValueKind != JsonValueKind.Array)
        {
            throw new ArgumentException("A change is a JSON array.");
        }

        if (ops.GetArrayLength() > MaxParts)
        {
            throw new ArgumentException("The change has too many parts.");
        }

        var output = new StringBuilder(text.Length);
        var at = 0;
        foreach (var part in ops.EnumerateArray())
        {
            switch (part.ValueKind)
            {
                case JsonValueKind.String:
                    output.Append(part.GetString());
                    break;
                case JsonValueKind.Number when part.TryGetInt32(out var n) && n != 0:
                    var count = Math.Abs(n);
                    if (at + count > text.Length)
                    {
                        throw new ArgumentException("The change runs past the end of the text.");
                    }

                    if (n > 0)
                    {
                        output.Append(text, at, count);
                    }

                    at += count;
                    break;
                default:
                    throw new ArgumentException("A change part is a non-zero whole number or a string.");
            }
        }

        if (at != text.Length)
        {
            throw new ArgumentException($"The change covers {at} characters, but the text has {text.Length}.");
        }

        return output.ToString();
    }

    /// <summary>The script's title: from its title page, else its first line with text (as the apps show it).</summary>
    public static string TitleOf(string text)
    {
        var lines = text.Split('\n').Select(l => l.Trim()).ToList();
        var title = lines.FirstOrDefault(l => l.StartsWith("Title:", StringComparison.OrdinalIgnoreCase))?[6..].Trim();
        if (!string.IsNullOrEmpty(title))
        {
            return title;
        }

        var first = lines.FirstOrDefault(l => l.Length > 0);
        return first is null ? "Untitled" : first[..Math.Min(60, first.Length)];
    }
}
