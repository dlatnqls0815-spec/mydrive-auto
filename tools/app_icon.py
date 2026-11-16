#!/usr/bin/env python3
"""Launcher icon for the K8 My Drive apps, drawn with Pillow (no image files in the repo).

Racing stripes: two red stripes with a thin white pinstripe, slanted like a bonnet stripe, on a dark
graphite gradient (chosen by the user 2026-11-16, draft B).

    write_res(res_dir, adaptive)  -> mipmap PNGs (+ adaptive XML for API 26+) under res_dir
    python tools/app_icon.py out.png  -> preview (legacy and adaptive)
"""
from pathlib import Path
import sys

from PIL import Image, ImageDraw

TOP, BOTTOM = (30, 34, 40), (14, 16, 20)
RED = (232, 40, 40, 255)
WHITE = (240, 242, 245, 255)
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # supersampling


def _gradient(s):
    g = Image.new("RGBA", (s, s))
    d = ImageDraw.Draw(g)
    for y in range(s):
        t = y / (s - 1)
        d.line([(0, y), (s, y)], fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)) + (255,))
    return g


def _stripes(d, s, inset=0.0):
    """Stripes across the whole square; inset shifts nothing, the square is clipped by the caller."""
    def band(x0, width, color):
        d.polygon([(s * x0, s * 1.05), (s * (x0 + width), s * 1.05),
                   (s * (x0 + width + 0.20), -s * 0.05), (s * (x0 + 0.20), -s * 0.05)], fill=color)
    band(0.20, 0.14, RED)
    band(0.42, 0.14, RED)
    band(0.34, 0.04, WHITE)


def legacy(px):
    """Rounded square icon (head unit, API 19, and phone fallback)."""
    s = px * SS
    art = _gradient(s)
    _stripes(ImageDraw.Draw(art), s)
    mask = Image.new("L", (s, s), 0)
    m = s * 0.04
    ImageDraw.Draw(mask).rounded_rectangle([m, m, s - m, s - m], radius=s * 0.22, fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(art, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


def background(px):
    """Adaptive icon background layer: the graphite gradient."""
    return _gradient(px * SS).resize((px, px), Image.LANCZOS)


def foreground(px):
    """Adaptive icon foreground: the stripes run edge to edge, the launcher mask trims them."""
    s = px * SS
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    _stripes(ImageDraw.Draw(im), s)
    return im.resize((px, px), Image.LANCZOS)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""


def write_res(res, adaptive):
    res = Path(res)
    for name, f in DENSITIES.items():
        folder = res / ("mipmap-" + name)
        folder.mkdir(parents=True, exist_ok=True)
        legacy(int(round(48 * f))).save(folder / "ic_launcher.png")
        if adaptive:
            foreground(int(round(108 * f))).save(folder / "ic_launcher_foreground.png")
            background(int(round(108 * f))).save(folder / "ic_launcher_background.png")
    if adaptive:
        (res / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
        (res / "mipmap-anydpi-v26" / "ic_launcher.xml").write_text(ADAPTIVE_XML, encoding="utf-8")


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "icon.png")
    big = Image.new("RGBA", (1100, 540), (0xEC, 0xEF, 0xF3, 255))
    big.alpha_composite(legacy(512), (10, 14))
    layer = background(512)
    layer.alpha_composite(foreground(512))
    mask = Image.new("L", (512, 512), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 511, 511], fill=255)
    big.paste(layer, (570, 14), mask)
    big.save(out)
    print(out)
