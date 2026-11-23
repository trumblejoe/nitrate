using System.Diagnostics;
using Nitrate.Companion.Hosting;
using Xunit;

namespace Nitrate.Companion.Tests.Hosting;

/// <summary>The release signs with openssl (release.yml); the shell must accept exactly that.</summary>
public sealed class ReleaseSigningTests : IDisposable
{
    private readonly HostingKit.TempDir _dir = HostingKit.Temp();

    public void Dispose() => _dir.Dispose();

    [Fact]
    public void ASignatureMadeAsTheReleaseMakesItIsAccepted()
    {
        Directory.CreateDirectory(_dir.Path);
        var key = Path.Combine(_dir.Path, "key.pem");
        var zip = Path.Combine(_dir.Path, "logic.zip");
        var sig = Path.Combine(_dir.Path, "logic.sig");
        var pub = Path.Combine(_dir.Path, "pub.der");
        File.WriteAllBytes(zip, HostingKit.Package("1.0.0.2"));

        // As release.yml: a P-256 key (the COMPANION_LOGIC_SIGNING_KEY secret), the signature, the public key for the shell.
        if (!OpenSsl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", key))
        {
            return; // No openssl here; the release runners have it.
        }

        Assert.True(OpenSsl("dgst", "-sha256", "-sign", key, "-out", sig, zip));
        Assert.True(OpenSsl("pkey", "-in", key, "-pubout", "-outform", "DER", "-out", pub));
        var publicKey = File.ReadAllBytes(pub);
        Assert.True(LogicSignature.Verify(File.ReadAllBytes(zip), File.ReadAllBytes(sig), publicKey));
        Assert.False(LogicSignature.Verify(HostingKit.Package("1.0.0.3"), File.ReadAllBytes(sig), publicKey));
    }

    private static bool OpenSsl(params string[] args)
    {
        try
        {
            var start = new ProcessStartInfo("openssl") { RedirectStandardError = true, RedirectStandardOutput = true };
            foreach (var a in args)
            {
                start.ArgumentList.Add(a);
            }

            using var process = Process.Start(start)!;
            process.WaitForExit(30_000);
            return process.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return false;
        }
    }
}
