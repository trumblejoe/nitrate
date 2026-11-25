using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Jellyfin.Data.Enums;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Model.Entities;

namespace Nitrate.Companion.Metadata;

/// <summary>What applying an edit did.</summary>
/// <param name="Changed">The fields whose value changed, by their JSON names.</param>
/// <param name="Old">Their old values (field → value), for the log and Undo.</param>
/// <param name="People">The new cast and crew when they changed (saved through the library), else null.</param>
/// <param name="LockChanged">"Lock this item" changed (a folder's children follow, as in Jellyfin).</param>
public record FieldChanges(IReadOnlyList<string> Changed, JsonObject Old, IReadOnlyList<PersonInfo>? People, bool LockChanged);

/// <summary>
/// The fields a metadata editor may change, read from the item JSON Jellyfin's own
/// <c>GET /Items/{id}</c> gives (its field names; either case), and written onto the item as
/// Jellyfin's <c>POST /Items/{id}</c> does. Everything else in the body (paths, ids, dates added,
/// media streams, user data, unknown fields) is ignored. A field left out is left as it is; one
/// sent as null is cleared.
/// </summary>
public static class MetadataFields
{
    /// <summary>The editable fields, by their JSON names.</summary>
    public static readonly IReadOnlyList<string> Editable = new[]
    {
        "Name", "OriginalTitle", "ForcedSortName", "Overview", "Taglines", "ProductionYear", "PremiereDate", "EndDate",
        "OfficialRating", "CommunityRating", "CriticRating", "Genres", "Tags", "Studios", "People", "IndexNumber",
        "ParentIndexNumber", "AirsBeforeSeasonNumber", "AirsAfterSeasonNumber", "AirsBeforeEpisodeNumber", "ProviderIds",
        "LockData", "LockedFields",
    };

    /// <summary>
    /// Writes the editable fields present in [body] onto [item] (people only into the result, for
    /// the library to save), and tells what changed with the old values.
    /// </summary>
    /// <exception cref="FormatException">A field has a value of the wrong kind.</exception>
    public static FieldChanges Apply(BaseItem item, JsonElement body, IReadOnlyList<PersonInfo> people)
    {
        if (body.ValueKind != JsonValueKind.Object)
        {
            throw new FormatException("The body isn't an item.");
        }

        var given = new Dictionary<string, JsonElement>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in body.EnumerateObject())
        {
            given[p.Name] = p.Value;
        }

        var changed = new List<string>();
        var old = new JsonObject();
        IReadOnlyList<PersonInfo>? newPeople = null;
        var lockChanged = false;
        foreach (var field in Editable)
        {
            if (!given.TryGetValue(field, out var value) || !Applies(item, field))
            {
                continue;
            }

            var before = Read(item, field, people);
            List<PersonInfo>? cast = null;
            if (field == "People")
            {
                cast = ReadPeople(value);
                if (Same(before, PeopleJson(cast)))
                {
                    continue;
                }

                newPeople = cast;
            }
            else
            {
                if (field == "Name" && (value.ValueKind != JsonValueKind.String || string.IsNullOrWhiteSpace(value.GetString())))
                {
                    // A title always keeps a name.
                    continue;
                }

                Write(item, field, value);
                if (Same(before, Read(item, field, people)))
                {
                    continue;
                }

                lockChanged |= field == "LockData";
            }

            changed.Add(field);
            old[field] = before;
        }

        return new FieldChanges(changed, old, newPeople, lockChanged);
    }

    /// <summary>The value of one editable field now, as JSON (people from [people]).</summary>
    public static JsonNode? Read(BaseItem item, string field, IReadOnlyList<PersonInfo> people) => field switch
    {
        "Name" => item.Name,
        "OriginalTitle" => item.OriginalTitle,
        "ForcedSortName" => item.ForcedSortName,
        "Overview" => item.Overview,
        "Taglines" => string.IsNullOrEmpty(item.Tagline) ? new JsonArray() : new JsonArray(item.Tagline),
        "ProductionYear" => item.ProductionYear,
        "PremiereDate" => item.PremiereDate?.ToString("o", CultureInfo.InvariantCulture),
        "EndDate" => item.EndDate?.ToString("o", CultureInfo.InvariantCulture),
        "OfficialRating" => item.OfficialRating,
        "CommunityRating" => item.CommunityRating,
        "CriticRating" => item.CriticRating,
        "Genres" => Strings(item.Genres),
        "Tags" => Strings(item.Tags),
        "Studios" => new JsonArray((item.Studios ?? []).Select(s => (JsonNode)new JsonObject { ["Name"] = s }).ToArray()),
        "People" => PeopleJson(people),
        "IndexNumber" => item.IndexNumber,
        "ParentIndexNumber" => item.ParentIndexNumber,
        "AirsBeforeSeasonNumber" => (item as Episode)?.AirsBeforeSeasonNumber,
        "AirsAfterSeasonNumber" => (item as Episode)?.AirsAfterSeasonNumber,
        "AirsBeforeEpisodeNumber" => (item as Episode)?.AirsBeforeEpisodeNumber,
        "ProviderIds" => new JsonObject((item.ProviderIds ?? new()).OrderBy(p => p.Key, StringComparer.Ordinal).Select(p => KeyValuePair.Create(p.Key, (JsonNode?)p.Value))),
        "LockData" => item.IsLocked,
        "LockedFields" => Strings((item.LockedFields ?? []).Select(f => f.ToString()).OrderBy(f => f, StringComparer.Ordinal)),
        _ => null,
    };

    /// <summary>Cast and crew from the item JSON's <c>People</c> (name, role, type).</summary>
    public static List<PersonInfo> ReadPeople(JsonElement value)
    {
        var list = new List<PersonInfo>();
        if (value.ValueKind == JsonValueKind.Null)
        {
            return list;
        }

        if (value.ValueKind != JsonValueKind.Array)
        {
            throw new FormatException("People must be a list.");
        }

        foreach (var p in value.EnumerateArray())
        {
            if (p.ValueKind != JsonValueKind.Object || Prop(p, "Name") is not { ValueKind: JsonValueKind.String } name || string.IsNullOrWhiteSpace(name.GetString()))
            {
                continue;
            }

            var type = Prop(p, "Type") is { } t ? t.ValueKind switch
            {
                JsonValueKind.String when Enum.TryParse<PersonKind>(t.GetString(), true, out var k) => k,
                JsonValueKind.Number when Enum.IsDefined(typeof(PersonKind), t.GetInt32()) => (PersonKind)t.GetInt32(),
                _ => PersonKind.Unknown,
            } : PersonKind.Unknown;
            list.Add(new PersonInfo
            {
                Name = name.GetString()!.Trim(),
                Role = Prop(p, "Role") is { ValueKind: JsonValueKind.String } role ? role.GetString() : null,
                Type = type,
            });
        }

        return list;
    }

    /// <summary>A field applies to this kind of item (the "airs before/after" numbers are an episode's).</summary>
    private static bool Applies(BaseItem item, string field) =>
        !field.StartsWith("Airs", StringComparison.Ordinal) || item is Episode;

    private static void Write(BaseItem item, string field, JsonElement v)
    {
        switch (field)
        {
            case "Name":
                item.Name = v.GetString()!.Trim();
                break;
            case "OriginalTitle":
                item.OriginalTitle = Text(v);
                break;
            case "ForcedSortName":
                item.ForcedSortName = Text(v);
                break;
            case "Overview":
                item.Overview = Text(v);
                break;
            case "Taglines":
                item.Tagline = List(v).FirstOrDefault();
                break;
            case "ProductionYear":
                item.ProductionYear = Int(v);
                break;
            case "PremiereDate":
                item.PremiereDate = Date(v);
                break;
            case "EndDate":
                item.EndDate = Date(v);
                break;
            case "OfficialRating":
                item.OfficialRating = Text(v);
                break;
            case "CommunityRating":
                item.CommunityRating = Float(v);
                break;
            case "CriticRating":
                item.CriticRating = Float(v);
                break;
            case "Genres":
                item.Genres = List(v);
                break;
            case "Tags":
                item.Tags = List(v);
                break;
            case "Studios":
                item.Studios = List(v);
                break;
            case "IndexNumber":
                item.IndexNumber = Int(v);
                break;
            case "ParentIndexNumber":
                item.ParentIndexNumber = Int(v);
                break;
            case "AirsBeforeSeasonNumber":
                ((Episode)item).AirsBeforeSeasonNumber = Int(v);
                break;
            case "AirsAfterSeasonNumber":
                ((Episode)item).AirsAfterSeasonNumber = Int(v);
                break;
            case "AirsBeforeEpisodeNumber":
                ((Episode)item).AirsBeforeEpisodeNumber = Int(v);
                break;
            case "ProviderIds":
                item.ProviderIds = ProviderIds(v);
                break;
            case "LockData":
                item.IsLocked = v.ValueKind switch
                {
                    JsonValueKind.True => true,
                    JsonValueKind.False or JsonValueKind.Null => false,
                    _ => throw new FormatException("LockData must be true or false."),
                };
                break;
            case "LockedFields":
                item.LockedFields = List(v).Select(f => Enum.TryParse<MetadataField>(f, true, out var m) && Enum.IsDefined(m) ? m : throw new FormatException($"Unknown locked field {f}.")).Distinct().ToArray();
                break;
        }
    }

    private static JsonElement? Prop(JsonElement o, string name)
    {
        foreach (var p in o.EnumerateObject())
        {
            if (string.Equals(p.Name, name, StringComparison.OrdinalIgnoreCase))
            {
                return p.Value;
            }
        }

        return null;
    }

    private static JsonArray Strings(IEnumerable<string>? values) => new((values ?? []).Select(s => (JsonNode?)s).ToArray());

    private static JsonArray PeopleJson(IEnumerable<PersonInfo> people) => new(people
        .Select(p => (JsonNode?)new JsonObject { ["Name"] = p.Name, ["Role"] = string.IsNullOrEmpty(p.Role) ? null : p.Role, ["Type"] = p.Type.ToString() })
        .ToArray());

    private static bool Same(JsonNode? a, JsonNode? b) => JsonNode.DeepEquals(a, b);

    private static string? Text(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Null => null,
        JsonValueKind.String => string.IsNullOrWhiteSpace(v.GetString()) ? null : v.GetString(),
        _ => throw new FormatException("Expected text."),
    };

    private static int? Int(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Null => null,
        JsonValueKind.Number => v.GetInt32(),
        JsonValueKind.String when string.IsNullOrWhiteSpace(v.GetString()) => null,
        JsonValueKind.String => int.Parse(v.GetString()!, CultureInfo.InvariantCulture),
        _ => throw new FormatException("Expected a whole number."),
    };

    private static float? Float(JsonElement v) => v.ValueKind switch
    {
        JsonValueKind.Null => null,
        JsonValueKind.Number => v.GetSingle(),
        JsonValueKind.String when string.IsNullOrWhiteSpace(v.GetString()) => null,
        JsonValueKind.String => float.Parse(v.GetString()!, CultureInfo.InvariantCulture),
        _ => throw new FormatException("Expected a number."),
    };

    // Jellyfin keeps dates in UTC; one without a zone is taken as UTC already.
    private static DateTime? Date(JsonElement v)
    {
        if (v.ValueKind == JsonValueKind.Null || (v.ValueKind == JsonValueKind.String && string.IsNullOrWhiteSpace(v.GetString())))
        {
            return null;
        }

        if (v.ValueKind != JsonValueKind.String)
        {
            throw new FormatException("Expected a date.");
        }

        var date = DateTime.Parse(v.GetString()!, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
        return date.Kind == DateTimeKind.Unspecified ? DateTime.SpecifyKind(date, DateTimeKind.Utc) : date.ToUniversalTime();
    }

    // Plain strings, or objects with a Name (studios as Jellyfin gives them).
    private static string[] List(JsonElement v)
    {
        if (v.ValueKind == JsonValueKind.Null)
        {
            return [];
        }

        if (v.ValueKind != JsonValueKind.Array)
        {
            throw new FormatException("Expected a list.");
        }

        return v.EnumerateArray()
            .Select(e => e.ValueKind switch
            {
                JsonValueKind.String => e.GetString(),
                JsonValueKind.Object => Prop(e, "Name") is { ValueKind: JsonValueKind.String } n ? n.GetString() : null,
                _ => throw new FormatException("Expected a list of names."),
            })
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Select(s => s!.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private static Dictionary<string, string> ProviderIds(JsonElement v)
    {
        var ids = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (v.ValueKind == JsonValueKind.Null)
        {
            return ids;
        }

        if (v.ValueKind != JsonValueKind.Object)
        {
            throw new FormatException("ProviderIds must be an object.");
        }

        foreach (var p in v.EnumerateObject())
        {
            if (p.Value.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(p.Value.GetString()))
            {
                ids[p.Name] = p.Value.GetString()!.Trim();
            }
        }

        return ids;
    }
}
