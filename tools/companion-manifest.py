#!/usr/bin/env python3
"""Packages the Nitrate Companion plugin and keeps its Jellyfin plugin-repository manifest.

The plugin is a shell Jellyfin loads (Nitrate.Companion.dll) and logic the shell loads and swaps
without a restart (Nitrate.Companion.Logic.dll and its libraries, as a logic package); see
docs/features/companion-hotswap.md.

  companion-manifest.py logic <dll dir> <version> <out.zip>
      Zips the logic with the logic.json the shell reads (version, contract, entry, assemblies).
      The contract is the shell's ShellContract.Version it was built against.
  companion-manifest.py package <dll dir> <version> <target abi> <out.zip> [<logic.zip>]
      Zips the shell with the meta.json Jellyfin reads from an installed plugin, and the logic
      package it starts from (logic/nitrate-companion-logic.zip).
  companion-manifest.py add <manifest.json> <version> <target abi> <zip> <url> <source hash> <changelog>
      Lists a new version in the manifest (made if missing), newest first, unless the newest
      listed version was built from the same sources (source hash); prints "added" or "same".
  companion-manifest.py logic-add <logic.json> <logic.zip> <signature file> <url> <source hash>
      Lists a signed logic package in the logic list the shell checks (made if missing), newest
      first, unless the newest was built from the same sources; prints "added" or "same".

Jellyfin installs from the manifest's newest version whose targetAbi it meets, and checks the
download against its MD5 checksum. The shell takes a logic package only with a good signature
(ECDSA P-256 over the zip's SHA-256, made by `openssl dgst -sha256 -sign`) and the SHA-256 listed.
"""
import base64
import hashlib
import json
import os
import re
import sys
import zipfile
from datetime import datetime, timezone

GUID = "04250121-a5d6-40da-99fc-242a178b3d2c"  # Plugin.PluginId: never change it.
NAME = "Nitrate Companion"
DESCRIPTION = "Chat during watch-together and other server features for the Nitrate apps."
OVERVIEW = "Server features for the Nitrate apps"
OWNER = "Nitrate"
CATEGORY = "General"

# The shell: the only assembly Jellyfin loads. Jellyfin's own packages are provided by the server
# and never shipped.
ASSEMBLIES = ["Nitrate.Companion.dll"]

# The logic and the libraries it ships (SharpCompress, MIT: CBR/CB7/CBT comics), loaded by the shell.
LOGIC_ASSEMBLIES = ["Nitrate.Companion.Logic.dll", "SharpCompress.dll"]

# Where the shell finds its built-in logic package (LogicPackage.BuiltInFile).
BUILT_IN_LOGIC = "logic/nitrate-companion-logic.zip"

# Kept in the logic list (older ones drop off; a server that far behind takes the plugin update).
LOGIC_KEPT = 20

CONTRACT_SOURCE = os.path.join(os.path.dirname(__file__), "..", "companion", "Nitrate.Companion", "Hosting", "Contract.cs")


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def contract():
    with open(CONTRACT_SOURCE) as f:
        match = re.search(r"public const int Version = (\d+);", f.read())
    if match is None:
        sys.exit("ShellContract.Version not found in " + CONTRACT_SOURCE)
    return int(match.group(1))


def logic(dll_dir, version, out):
    manifest = {"version": version, "contract": contract(), "entry": LOGIC_ASSEMBLIES[0], "assemblies": LOGIC_ASSEMBLIES}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dll in LOGIC_ASSEMBLIES:
            z.write(os.path.join(dll_dir, dll), dll)
        z.writestr("logic.json", json.dumps(manifest, indent=2))


def package(dll_dir, version, abi, out, logic_zip=None):
    meta = {
        "category": CATEGORY, "changelog": "", "description": DESCRIPTION, "guid": GUID, "name": NAME,
        "overview": OVERVIEW, "owner": OWNER, "targetAbi": abi, "timestamp": now(), "version": version,
        "status": "Active", "autoUpdate": True, "assemblies": ASSEMBLIES,
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for dll in ASSEMBLIES:
            z.write(os.path.join(dll_dir, dll), dll)
        if logic_zip is not None:
            z.write(logic_zip, BUILT_IN_LOGIC)
        z.writestr("meta.json", json.dumps(meta, indent=2))


def read_list(path, empty):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        with open(path) as f:
            return json.load(f)
    return empty


def add(manifest_path, version, abi, zip_path, url, source_hash, changelog):
    manifest = read_list(manifest_path, [])
    plugin = next((p for p in manifest if p.get("guid") == GUID), None)
    if plugin is None:
        plugin = {"guid": GUID, "name": NAME, "description": DESCRIPTION, "overview": OVERVIEW,
                  "owner": OWNER, "category": CATEGORY, "versions": []}
        manifest.append(plugin)
    newest = plugin["versions"][0] if plugin["versions"] else None
    marker = f"[source {source_hash}]"
    if newest is not None and marker in newest.get("changelog", ""):
        print("same")
        return
    with open(zip_path, "rb") as f:
        checksum = hashlib.md5(f.read()).hexdigest()
    plugin["versions"].insert(0, {
        "version": version, "changelog": f"{changelog}\n{marker}".strip(), "targetAbi": abi,
        "sourceUrl": url, "checksum": checksum, "timestamp": now(),
    })
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print("added")


def logic_add(list_path, zip_path, signature_path, url, source_hash):
    listing = read_list(list_path, {"packages": []})
    packages = listing.setdefault("packages", [])
    if packages and packages[0].get("source") == source_hash:
        print("same")
        return
    with open(zip_path, "rb") as f:
        data = f.read()
    with zipfile.ZipFile(zip_path) as z:
        manifest = json.loads(z.read("logic.json"))
    with open(signature_path, "rb") as f:
        signature = base64.b64encode(f.read()).decode()
    packages.insert(0, {
        "version": manifest["version"], "contract": manifest["contract"], "url": url,
        "sha256": hashlib.sha256(data).hexdigest(), "signature": signature,
        "source": source_hash, "timestamp": now(),
    })
    del packages[LOGIC_KEPT:]
    with open(list_path, "w") as f:
        json.dump(listing, f, indent=2)
    print("added")


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "logic":
        logic(*args)
    elif cmd == "package":
        package(*args)
    elif cmd == "add":
        add(*args)
    elif cmd == "logic-add":
        logic_add(*args)
    elif cmd == "contract":
        print(contract())
    else:
        sys.exit(f"unknown command {cmd}")
