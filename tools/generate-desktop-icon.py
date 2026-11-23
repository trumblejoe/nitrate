#!/usr/bin/env python3
"""Draws the desktop app's icon (desktop/icons/nitrate.png and .ico) from the Android launcher
icon's shapes (mobile/src/main/res/drawable/ic_launcher_*.xml: a film frame on dark blue), so all
apps look alike. Needs Pillow. Run from the repository root: python3 tools/generate-desktop-icon.py
"""
from PIL import Image, ImageDraw

BACK = (0x0B, 0x0D, 0x12, 255)
GOLD = (0xE8, 0xA3, 0x3D, 255)
SIZE = 1024


def draw() -> Image.Image:
    # The launcher's 108-unit viewport, scaled; corners rounded like Windows 11's app icons.
    s = SIZE / 108
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=int(SIZE * 0.18), fill=BACK)

    def box(x, y, w, h, fill, r=0):
        d.rounded_rectangle([x * s, y * s, (x + w) * s, (y + h) * s], radius=r * s, fill=fill)

    # The frame (zoomed a little: the adaptive icon's safe zone leaves wide margins).
    zoom = 1.35
    c = 54

    def z(x, y, w, h, fill, r=0):
        box(c + (x - c) * zoom, c + (y - c) * zoom, w * zoom, h * zoom, fill, r * zoom)

    z(30, 32, 48, 44, GOLD, 4)
    z(42, 40, 24, 28, BACK)
    for y in (37, 47, 57, 67):
        z(33, y, 5, 5, BACK)
        z(70, y, 5, 5, BACK)
    return img


def main():
    img = draw()
    img.resize((512, 512), Image.LANCZOS).save("desktop/icons/nitrate.png")
    img.save("desktop/icons/nitrate.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    img.resize((256, 256), Image.LANCZOS).save("desktop/src/main/resources/nitrate-icon.png")


if __name__ == "__main__":
    main()
