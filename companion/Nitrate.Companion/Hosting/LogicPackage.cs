using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Nitrate.Companion.Hosting;

/// <summary>A logic package couldn't be used; the message says why.</summary>
public sealed class LogicPackageException : Exception
{
    /// <summary>Initializes a new instance of the <see cref="LogicPackageException"/> class.</summary>
    public LogicPackageException(string message, Exception? inner = null)
        : base(message, inner)
    {
    }
}

/// <summary>The <c>logic.json</c> inside a logic package.</summary>
/// <param name="Version">The logic's version (0.1.0.run).</param>
/// <param name="Contract">The shell contract it was built for (<see cref="ShellContract"/>).</param>
/// <param name="Entry">The logic assembly's file name.</param>
/// <param name="Assemblies">Every assembly file in the package, the entry first.</param>
public sealed record LogicManifest(
    [property: JsonPropertyName("version")] string Version,
    [property: JsonPropertyName("contract")] int Contract,
    [property: JsonPropertyName("entry")] string Entry,
    [property: JsonPropertyName("assemblies")] IReadOnlyList<string> Assemblies);

/// <summary>
/// A logic package read into memory: its manifest and its assemblies' bytes (loaded from memory,
/// so nothing on disk is locked or needs cleaning up).
/// </summary>
public sealed class LogicPackage
{
    /// <summary>Where the built-in package sits, under the plugin's folder.</summary>
    public const string BuiltInFile = "logic/nitrate-companion-logic.zip";

    private LogicPackage(LogicManifest manifest, IReadOnlyDictionary<string, byte[]> assemblies, byte[] zip, string source, byte[]? signature)
    {
        Manifest = manifest;
        Assemblies = assemblies;
        Zip = zip;
        Source = source;
        Signature = signature;
    }

    /// <summary>Gets the package's manifest.</summary>
    public LogicManifest Manifest { get; }

    /// <summary>Gets the package's version.</summary>
    public string Version => Manifest.Version;

    /// <summary>Gets the version as a comparable <see cref="System.Version"/>.</summary>
    public Version ParsedVersion => ParseVersion(Manifest.Version);

    /// <summary>Gets the assemblies by simple name (file name without .dll).</summary>
    public IReadOnlyDictionary<string, byte[]> Assemblies { get; }

    /// <summary>Gets the package as it came (to keep it on disk).</summary>
    public byte[] Zip { get; }

    /// <summary>Gets the package's signature (downloaded ones), kept beside it on disk.</summary>
    public byte[]? Signature { get; }

    /// <summary>Gets where it came from: "built-in" or "downloaded".</summary>
    public string Source { get; }

    /// <summary>Gets the simple name of the entry assembly.</summary>
    public string EntryName => Path.GetFileNameWithoutExtension(Manifest.Entry);

    /// <summary>Parses a version, treating anything unreadable as 0.</summary>
    public static Version ParseVersion(string? text) => System.Version.TryParse(text, out var v) ? v : new Version(0, 0);

    /// <summary>
    /// Reads a package. Throws <see cref="LogicPackageException"/> when it isn't one: no or bad
    /// logic.json, a listed assembly missing, a path that isn't a plain file name.
    /// </summary>
    public static LogicPackage Read(byte[] zip, string source, byte[]? signature = null)
    {
        try
        {
            using var archive = new ZipArchive(new MemoryStream(zip, writable: false), ZipArchiveMode.Read);
            var manifestEntry = archive.GetEntry("logic.json") ?? throw new LogicPackageException("The package has no logic.json.");
            LogicManifest? manifest;
            using (var s = manifestEntry.Open())
            {
                manifest = JsonSerializer.Deserialize<LogicManifest>(s);
            }

            if (manifest is null || string.IsNullOrWhiteSpace(manifest.Version) || string.IsNullOrWhiteSpace(manifest.Entry) || manifest.Assemblies is null)
            {
                throw new LogicPackageException("The package's logic.json is incomplete.");
            }

            if (!System.Version.TryParse(manifest.Version, out _))
            {
                throw new LogicPackageException($"The package's version '{manifest.Version}' isn't a version.");
            }

            var assemblies = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
            foreach (var file in manifest.Assemblies.Append(manifest.Entry).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                if (file != Path.GetFileName(file) || !file.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
                {
                    throw new LogicPackageException($"'{file}' isn't an assembly file name.");
                }

                var name = Path.GetFileNameWithoutExtension(file);
                if (LogicLoadContext.IsHostOwned(name))
                {
                    throw new LogicPackageException($"'{file}' is the server's own and can't come from a package.");
                }

                var entry = archive.GetEntry(file) ?? throw new LogicPackageException($"The package lists '{file}' but doesn't have it.");
                using var s = entry.Open();
                using var copy = new MemoryStream();
                s.CopyTo(copy);
                assemblies[name] = copy.ToArray();
            }

            return new LogicPackage(manifest, assemblies, zip, source, signature);
        }
        catch (Exception e) when (e is InvalidDataException or JsonException or IOException)
        {
            throw new LogicPackageException("The package can't be read: " + e.Message, e);
        }
    }
}

/// <summary>
/// Checks downloaded logic packages: an ECDSA P-256 signature (DER, as <c>openssl dgst -sha256 -sign</c>
/// makes) over the zip's SHA-256, against the public key compiled into the shell.
/// </summary>
public static class LogicSignature
{
    /// <summary>Gets the public key the release compiled in (base64 SubjectPublicKeyInfo), or null: hot updates off.</summary>
    public static byte[]? BuiltInPublicKey { get; } = ReadMetadataKey();

    /// <summary>Gets where the release said the logic list is, or null.</summary>
    public static string? ManifestUrl { get; } = Metadata("NitrateLogicManifestUrl");

    /// <summary>The zip's SHA-256, lowercase hex.</summary>
    public static string Sha256(byte[] zip) => Convert.ToHexStringLower(SHA256.HashData(zip));

    /// <summary>Whether <paramref name="signature"/> is the key's signature of <paramref name="zip"/>.</summary>
    public static bool Verify(byte[] zip, byte[] signature, byte[]? publicKey)
    {
        if (publicKey is null || publicKey.Length == 0 || signature.Length == 0)
        {
            return false;
        }

        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(publicKey, out _);
            return key.VerifyHash(SHA256.HashData(zip), signature, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    private static byte[]? ReadMetadataKey()
    {
        var text = Metadata("NitrateLogicPublicKey");
        try
        {
            return text is null ? null : Convert.FromBase64String(text);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    private static string? Metadata(string key)
    {
        var value = typeof(LogicSignature).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == key)?.Value;
        return string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    }
}
