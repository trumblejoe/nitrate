#!/usr/bin/env python3
"""Abstract placeholder artwork for the mobile screenshot tests (mobile/src/test/resources/art).

Original shapes and gradients only, so screenshots show real colour, tinting and layout.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "mobile", "src", "test", "resources", "art")

# name: (base colour, accent)
PALETTES = {
    "m1": ((178, 74, 32), (255, 196, 92)),      # Nitrate: ember orange
    "m2": ((40, 70, 140), (150, 200, 255)),     # Silver Screen: steel blue
    "m3": ((24, 110, 92), (180, 255, 210)),     # Gate Weave: teal
    "m4": ((120, 36, 110), (255, 140, 220)),    # Persistence: magenta
    "series-1": ((90, 30, 160), (255, 120, 90)),  # The Night Reel: violet + coral
}


def gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)))
    return img


def art(size, base, accent, seed):
    rnd = random.Random(seed)
    w, h = size
    dark = tuple(int(c * 0.18) for c in base)
    img = gradient(size, base, dark)
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    for _ in range(7):
        r = rnd.uniform(0.15, 0.45) * min(w, h)
        x, y = rnd.uniform(0, w), rnd.uniform(0, h * 0.8)
        d.ellipse([x - r, y - r, x + r, y + r], fill=accent + (rnd.randint(40, 110),))
    for i in range(5):
        a = rnd.uniform(0, math.pi)
        x0, y0 = rnd.uniform(0, w), rnd.uniform(0, h)
        d.line([(x0, y0), (x0 + math.cos(a) * w, y0 + math.sin(a) * h)], fill=(255, 255, 255, 40), width=max(2, w // 120))
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) / 30))
    img = Image.alpha_composite(img.convert("RGBA"), glow)
    # Film-like grain.
    noise = Image.effect_noise(size, 18).convert("L")
    img = Image.blend(img, Image.merge("RGBA", (noise, noise, noise, Image.new("L", size, 255))), 0.06)
    return img.convert("RGB")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (base, accent) in PALETTES.items():
        art((400, 600), base, accent, name + "p").save(os.path.join(OUT, f"{name}-poster.jpg"), quality=82)
        art((1280, 720), base, accent, name + "b").save(os.path.join(OUT, f"{name}-backdrop.jpg"), quality=82)
    base, accent = PALETTES["series-1"]
    for n in range(1, 7):
        art((480, 270), base, accent, f"ep{n}").save(os.path.join(OUT, f"episode-{n}.jpg"), quality=80)
    # A 2x2 trickplay sheet of 320x180 frames, for the scrubbing preview.
    sheet = Image.new("RGB", (640, 360))
    for i, key in enumerate(["series-1", "m1", "m3", "m2"]):
        base, accent = PALETTES[key]
        sheet.paste(art((320, 180), base, accent, f"t{i}"), ((i % 2) * 320, (i // 2) * 180))
    sheet.save(os.path.join(OUT, "trickplay-0.jpg"), quality=80)
    print("wrote", len(os.listdir(OUT)), "files to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
