#!/usr/bin/env python3
"""Which parts a change touches, for the Build workflow: prints android=… and companion=… lines.

  changed-parts.py <changed file>...

companion: the server plugin (companion/, its packaging script).
android:   anything else that the Gradle build reads; documentation, the Swift iOS host (built
           only on macOS by ios.yml) and the plugin are left out.
A change to the Build workflow (or this script) counts for both, since they decide what's tested.
The release workflows (release.yml, ios-release.yml) count for neither: Build never runs them, so
editing them can't break a test (Release reuses Build's earlier passing result for each part).
"""
import sys

BOTH = {".github/workflows/build.yml", "tools/changed-parts.py"}
COMPANION_FILES = {"tools/companion-manifest.py"}
NOT_ANDROID_PREFIXES = (
    "companion/", "iosApp/", ".github/workflows/ios.yml", ".github/workflows/release.yml", ".github/workflows/ios-release.yml",
    ".github/workflows/effect-clips.yml",
)


def parts(files):
    android = companion = False
    for f in files:
        if f in BOTH:
            android = companion = True
        elif f.startswith("companion/") or f in COMPANION_FILES:
            companion = True
        elif f.endswith(".md") or f.startswith(NOT_ANDROID_PREFIXES) or f in COMPANION_FILES:
            continue
        else:
            android = True
    return android, companion


if __name__ == "__main__":
    a, c = parts(sys.argv[1:])
    print(f"android={'true' if a else 'false'}")
    print(f"companion={'true' if c else 'false'}")
