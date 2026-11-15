#!/usr/bin/env python3
"""Packages the Nitrate Companion plugin and keeps its Jellyfin plugin-repository manifest.

  companion-manifest.py package <dll dir> <version> <target abi> <out.zip>
      Zips the plugin with the meta.json Jellyfin reads from an installed plugin.
  companion-manifest.py add <manifest.json> <version> <target abi> <zip> <url> <source hash> <changelog>
      Lists a new version in the manifest (made if missing), newest first, unless the newest
      listed version was built from the same sources (source hash); prints "added" or "same".

Jellyfin installs from the manifest's newest version whose targetAbi it meets, and checks the
download against its MD5 checksum.
"""
import hashlib
import json
import os
import sys
import zipfile
from datetime import datetime, timezone

GUID = "04250121-a5d6-40da-99fc-242a178b3d2c"  # Plugin.PluginId: never change it.
NAME = "Nitrate Companion"
DESCRIPTION = "Chat during watch-together and other server features for the Nitrate apps."
OVERVIEW = "Server features for the Nitrate apps"
OWNER = "Nitrate"
CATEGORY = "General"


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def package(dll_dir, version, abi, out):
    meta = {
        "category": CATEGORY, "changelog": "", "description": DESCRIPTION, "guid": GUID, "name": NAME,
        "overview": OVERVIEW, "owner": OWNER, "targetAbi": abi, "timestamp": now(), "version": version,
        "status": "Active", "autoUpdate": True, "assemblies": ["Nitrate.Companion.dll"],
    }
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dll_dir, "Nitrate.Companion.dll"), "Nitrate.Companion.dll")
        z.writestr("meta.json", json.dumps(meta, indent=2))


def add(manifest_path, version, abi, zip_path, url, source_hash, changelog):
    manifest = []
    if os.path.exists(manifest_path) and os.path.getsize(manifest_path) > 0:
        with open(manifest_path) as f:
            manifest = json.load(f)
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


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "package":
        package(*args)
    elif cmd == "add":
        add(*args)
    else:
        sys.exit(f"unknown command {cmd}")
