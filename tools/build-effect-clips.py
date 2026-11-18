#!/usr/bin/env python3
"""
Builds the TV app's effect clips (tv/src/main/assets/fx/<name>.mp4) and their credits
(fx/credits.json) from freely licensed footage on Wikimedia Commons.

Each clip: a few seconds cut from the source, joined end to start with a crossfade so it loops
without a jump, cropped to the band it covers at the bottom of the screen (core EffectClips.band),
scaled to 1280 wide, H.264 with no sound. Only public domain, CC0 and CC BY / BY-SA footage is
taken; the licence and author come from Commons itself and go into credits.json.

Commons rate-limits hard: this fetches one file at a time with pauses, and keeps downloads in
build/effect-clips so a rerun only fetches what's missing.

    tools/build-effect-clips.py            # every clip
    tools/build-effect-clips.py safari     # just one
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tv/src/main/assets/fx")
CACHE = os.path.join(ROOT, "build/effect-clips")
UA = {"User-Agent": "nitrate-effect-clips/1.0 (open-source media client; one-off build script)"}
ALLOWED = ("Public domain", "CC0", "CC BY ", "CC BY-SA ")

# name: (Commons file, start s, length s, band (share of height, from the bottom), crop centre y (0..1 of the source))
CLIPS = {
    "safari": ("File:Juvenile Giraffe - walking - Malawi - 2018 Aug.webm", 2.0, 8.0, 0.42, 0.62),
    "army": ("File:Stallions Conduct M1 Abrams, Bradley and Mortar live fire demonstration (2021-01-25).webm", 4.0, 8.0, 0.42, 0.6),
    "air-force": ("File:B-52 Stratofortress flies over Dubai Airshow 2025 (986813).webm", 3.0, 8.0, 0.42, 0.5),
    "navy": ("File:Torpedo and 5\" Gun Exercise aboard USS Curtis Wilbur (DDG 54).webm", 10.0, 8.0, 0.42, 0.55),
    "marines": ("File:5th ANGLICO Marines Sharpen Skills on Live-Fire Machine Gun Range (980555).webm", 6.0, 8.0, 0.42, 0.6),
    "veterans": ("File:Thousands of Flags Honor the Fallen at Arlington National Cemetery.webm", 20.0, 8.0, 0.42, 0.6),
    "easter": ("File:European Rabbit 2025 06 18 01.webm", 2.0, 8.0, 0.36, 0.6),
}


def get(url: str, timeout: int = 120) -> bytes:
    for attempt in range(6):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout).read()
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            wait = 30 * (attempt + 1)
            print(f"  rate limited, waiting {wait}s")
            time.sleep(wait)
    raise SystemExit("Commons keeps refusing: try again later")


def info(title: str) -> dict:
    q = urllib.parse.urlencode({
        "action": "query", "titles": title, "prop": "videoinfo", "format": "json",
        "viprop": "url|derivatives|extmetadata", "viextmetadatafilter": "LicenseShortName|Artist|LicenseUrl",
    })
    page = next(iter(json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"].values()))
    return page["videoinfo"][0]


def fetch(name: str, title: str) -> tuple[str, dict]:
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name + ".webm")
    meta_path = os.path.join(CACHE, name + ".json")
    if os.path.exists(path) and os.path.getsize(path) > 0 and os.path.exists(meta_path):
        return path, json.load(open(meta_path))
    vi = info(title)
    md = vi.get("extmetadata", {})
    lic = md.get("LicenseShortName", {}).get("value", "")
    if not (lic.startswith(ALLOWED) or lic in ("Public domain", "CC0")):
        raise SystemExit(f"{title}: licence {lic!r} isn't one Nitrate ships")
    src = next((d["src"] for want in ("720p.vp9.webm", "720p.webm", "1080p.vp9.webm") for d in vi.get("derivatives", []) if d["src"].endswith(want)), vi["url"])
    time.sleep(5)
    data = get(src, 900)  # fetched before the file is opened, so a refusal leaves nothing half-written
    open(path, "wb").write(data)
    artist = md.get("Artist", {}).get("value", "")
    meta = {
        "name": name, "title": title.removeprefix("File:"), "license": lic,
        "licenseUrl": md.get("LicenseUrl", {}).get("value"),
        # Commons gives the author as HTML: keep the text.
        "author": subprocess.run(["python3", "-c", "import html,re,sys; print(html.unescape(re.sub('<[^>]+>', '', sys.argv[1])).strip())", artist], capture_output=True, text=True).stdout.strip(),
        "page": vi.get("descriptionurl"),
    }
    json.dump(meta, open(meta_path, "w"), indent=1)
    time.sleep(10)
    return path, meta


def encode(name: str, src: str, start: float, length: float, band: float, centre: float) -> None:
    os.makedirs(OUT, exist_ok=True)
    width, height = 1280, round(720 * band / 2) * 2
    fade = 1.0
    # Crop the source to the band's shape around `centre`, then loop it: the last second fades into the first.
    crop = f"crop=iw:'min(ih,iw*{height}/{width})':0:'max(0,min(ih-oh,ih*{centre}-oh/2))',scale={width}:{height},setsar=1,fps=30"
    graph = (
        f"[0:v]trim={start}:{start + length + fade},setpts=PTS-STARTPTS,{crop},split[a][b];"
        f"[a]trim=0:{length},setpts=PTS-STARTPTS[body];"
        f"[b]trim={length}:{length + fade},setpts=PTS-STARTPTS[tail];"
        f"[body][tail]xfade=transition=fade:duration={fade}:offset={length - fade}[v]"
    )
    out = os.path.join(OUT, name + ".mp4")
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", src, "-filter_complex", graph, "-map", "[v]", "-an",
        "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p", "-crf", "26", "-preset", "slow",
        "-movflags", "+faststart", out,
    ], check=True)
    print(f"  {name}.mp4 {os.path.getsize(out) // 1024} KB")


def main() -> None:
    wanted = sys.argv[1:] or list(CLIPS)
    credits_path = os.path.join(OUT, "credits.json")
    credits = {c["name"]: c for c in (json.load(open(credits_path)) if os.path.exists(credits_path) else [])}
    for name in wanted:
        title, start, length, band, centre = CLIPS[name]
        print(name, "←", title)
        src, meta = fetch(name, title)
        encode(name, src, start, length, band, centre)
        credits[name] = meta
    json.dump(sorted(credits.values(), key=lambda c: c["name"]), open(credits_path, "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
