#!/usr/bin/env python3
"""Trim Jellyfin's OpenAPI description to the endpoints Nitrate uses, for the core contract tests.

Usage: tools/trim-openapi.py path/to/jellyfin-openapi-stable.json
Get the spec from a running server at /api-docs/openapi.json or from Jellyfin's GitHub releases.
Add a path to PATHS when JellyfinClient starts calling a new endpoint.
"""
import json
import sys

PATHS = [
    "/System/Info/Public",
    "/Users/Me",
    "/Users/Public",
    "/Users/AuthenticateByName",
    "/Users/AuthenticateWithQuickConnect",
    "/UserImage",
    "/QuickConnect/Enabled",
    "/QuickConnect/Initiate",
    "/QuickConnect/Connect",
    "/Sessions/Logout",
    "/DisplayPreferences/{displayPreferencesId}",
    "/UserViews",
    "/UserItems/Resume",
    "/Shows/NextUp",
    "/Items",
    "/Items/Latest",
    "/Items/{itemId}",
    "/Items/{itemId}/Similar",
    "/Items/{itemId}/Images/{imageType}",
    "/Items/{itemId}/Images/{imageType}/{imageIndex}",
    "/Items/{itemId}/PlaybackInfo",
    "/Shows/{seriesId}/Seasons",
    "/Shows/{seriesId}/Episodes",
    "/Playlists/{playlistId}/Items",
    "/UserPlayedItems/{itemId}",
    "/UserFavoriteItems/{itemId}",
    "/Videos/{itemId}/stream",
    "/Audio/{itemId}/stream",
    "/Sessions/Playing",
    "/Sessions/Playing/Progress",
    "/Sessions/Playing/Stopped",
    "/Items/{itemId}/Download",
    "/Videos/{itemId}/stream.{container}",
    "/Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}",
    "/UserItems/{itemId}/UserData",
    "/Persons",
    "/Genres",
    "/MediaSegments/{itemId}",
    "/Videos/{itemId}/Trickplay/{width}/{index}.jpg",
]
OUT = "core/src/jvmTest/resources/jellyfin-openapi.json"


def refs(node, found):
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
            found.add(ref.rsplit("/", 1)[1])
        for v in node.values():
            refs(v, found)
    elif isinstance(node, list):
        for v in node:
            refs(v, found)


def strip(node):
    """Drop prose; the tests only need structure."""
    if isinstance(node, dict):
        return {k: strip(v) for k, v in node.items() if k not in ("description", "summary", "x-jellyfin-version")}
    if isinstance(node, list):
        return [strip(v) for v in node]
    return node


def main():
    spec = json.load(open(sys.argv[1]))
    missing = [p for p in PATHS if p not in spec["paths"]]
    if missing:
        sys.exit(f"Not in spec: {missing}")
    paths = {p: spec["paths"][p] for p in PATHS}
    schemas = spec["components"]["schemas"]
    keep, todo = set(), set()
    refs(paths, todo)
    while todo:
        name = todo.pop()
        if name in keep:
            continue
        keep.add(name)
        refs(schemas[name], todo)
    out = {
        "openapi": spec["openapi"],
        "info": {"title": spec["info"]["title"], "version": spec["info"]["version"]},
        "paths": strip(paths),
        "components": {"schemas": strip({k: schemas[k] for k in sorted(keep)})},
    }
    with open(OUT, "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    print(f"{OUT}: {len(paths)} paths, {len(keep)} schemas")


if __name__ == "__main__":
    main()
