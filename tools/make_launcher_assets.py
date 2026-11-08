#!/usr/bin/env python3
"""Renders the banner for the TV's home screen from the logo, and writes the mark into the
launcher icon and the notification icon.

Usage: python3 tools/make_launcher_assets.py   (needs Pillow and numpy)

The logo is tools/logo.svg: the mark, a rounded square that a note's stem and head cut above a
wave, beside the name. Everything is drawn from its paths at the size it's shown, never scaled
from a bitmap. On the TV the mark is white on black: the home screen is dark, and the system
crops the icon's background to its own shape while the whole mark stays inside.
"""
import os
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
LOGO = os.path.join(ROOT, "tools", "logo.svg")
NS = "{http://www.w3.org/2000/svg}"

BLACK = (0, 0, 0)
WHITE = (255, 255, 255)


# Paths ---------------------------------------------------------------------------------------

def _curve(points, steps):
    """Points along a Bézier curve of any degree, the start left out."""
    p = np.asarray(points, dtype=np.float64)
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        q = p
        while len(q) > 1:
            q = q[:-1] * (1 - t) + q[1:] * t
        out.append((q[0][0], q[0][1]))
    return out


def subpaths(d):
    """The closed outlines of an SVG path made of M, L, H, V, C, Q and Z (all absolute, as the
    logo's are), as lists of points."""
    tokens = re.findall(r"[MLHVCQZ]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?", d)
    out, pts, i, cmd = [], [], 0, None
    x = y = 0.0
    while i < len(tokens):
        if tokens[i] in "MLHVCQZ":
            cmd = tokens[i]
            i += 1
            if cmd == "Z":
                if pts:
                    out.append(pts)
                pts = []
                continue
        n = [float(v) for v in tokens[i:i + {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "Q": 4}[cmd]]]
        i += len(n)
        if cmd == "M":
            if pts:
                out.append(pts)
            x, y = n
            pts = [(x, y)]
            cmd = "L"  # more pairs after a move draw lines
        elif cmd == "L":
            x, y = n
            pts.append((x, y))
        elif cmd == "H":
            x = n[0]
            pts.append((x, y))
        elif cmd == "V":
            y = n[0]
            pts.append((x, y))
        elif cmd == "C":
            pts += _curve([(x, y), n[0:2], n[2:4], n[4:6]], 16)
            x, y = n[4:6]
        elif cmd == "Q":
            pts += _curve([(x, y), n[0:2], n[2:4]], 12)
            x, y = n[2:4]
    if pts:
        out.append(pts)
    return out


def _transform(value):
    """An SVG transform made of translate and scale, as a matrix (a, b, c, d, e, f)."""
    m = np.eye(3)
    for name, args in re.findall(r"(translate|scale)\(([^)]*)\)", value or ""):
        v = [float(a) for a in re.split(r"[\s,]+", args.strip())]
        if name == "translate":
            step = np.array([[1, 0, v[0]], [0, 1, v[1] if len(v) > 1 else 0], [0, 0, 1]])
        else:
            step = np.array([[v[0], 0, 0], [0, v[1] if len(v) > 1 else v[0], 0], [0, 0, 1]])
        m = m @ step
    return m


def shapes(svg):
    """The filled paths of [svg] in its own units: (fill, path data, matrix) each, in order."""
    out = []

    def walk(node, fill, matrix):
        fill = node.get("fill", fill)
        matrix = matrix @ _transform(node.get("transform"))
        if node.tag == NS + "path":
            out.append((fill, node.get("d"), matrix))
        for child in node:
            walk(child, fill, matrix)

    walk(ET.parse(svg).getroot(), "#000000", np.eye(3))
    return out


def outlines(d, matrix):
    """The outlines of path data [d] with [matrix] applied."""
    return [[tuple((matrix @ (px, py, 1))[:2]) for px, py in sub] for sub in subpaths(d)]


def coverage(polygons, w, h, ss=8):
    """How much of each of [w] × [h] pixels the [polygons] (in pixels) cover, from 0 to 1, by
    the nonzero rule as SVG fills, with [ss] × [ss] samples a pixel."""
    W, H = w * ss, h * ss
    diff = np.zeros((H, W + 1), dtype=np.int32)
    for poly in polygons:
        p = np.asarray(poly, dtype=np.float64) * ss
        x0, y0 = p[:, 0], p[:, 1]
        x1, y1 = np.roll(x0, -1), np.roll(y0, -1)
        sign = np.sign(y1 - y0).astype(np.int32)
        # The rows whose sample centers lie between an edge's ends, and where it crosses them.
        r0 = np.clip(np.ceil(np.minimum(y0, y1) - 0.5), 0, H).astype(np.int64)
        r1 = np.clip(np.ceil(np.maximum(y0, y1) - 0.5), 0, H).astype(np.int64)
        count = np.where(sign != 0, r1 - r0, 0)
        edge = np.repeat(np.arange(len(p)), count)
        rows = r0[edge] + np.arange(count.sum()) - np.repeat(np.cumsum(count) - count, count)
        t = (rows + 0.5 - y0[edge]) / (y1[edge] - y0[edge])
        xs = x0[edge] + t * (x1[edge] - x0[edge])
        cols = np.clip(np.ceil(xs - 0.5), 0, W).astype(np.int64)
        np.add.at(diff, (rows, cols), sign[edge])
    inside = np.cumsum(diff[:, :W], axis=1) != 0
    return inside.reshape(h, ss, w, ss).mean(axis=(1, 3))


def paint(img, polygons, color):
    """Fills [polygons] on [img] (an h × w × 3 float array, sRGB) with [color]."""
    h, w = img.shape[:2]
    a = coverage(polygons, w, h)[..., None]
    img[:] = img * (1 - a) + np.asarray(color, dtype=np.float64) * a


# The logo ------------------------------------------------------------------------------------

def _bounds(polygons):
    p = np.concatenate([np.asarray(poly) for poly in polygons])
    return p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max()


_LOGO = shapes(LOGO)
MARK = _LOGO[0][1]                       # the mark's path data, 100 units wide from 0


def banner(path, w=320, h=180, width=0.70):
    """The logo, white on black, [width] of the banner wide and centered on the mark, which
    stands as tall as the name's capitals."""
    polygons = [poly for _, d, m in _LOGO for poly in outlines(d, m)]
    x0, _, x1, _ = _bounds(polygons)
    _, top, _, bottom = _bounds(outlines(MARK, _LOGO[0][2]))
    k = w * width / (x1 - x0)
    dx = (w - (x1 - x0) * k) / 2 - x0 * k
    dy = h / 2 - (top + bottom) / 2 * k
    img = np.zeros((h, w, 3))
    img[:] = BLACK
    paint(img, [[(dx + x * k, dy + y * k) for x, y in poly] for poly in polygons], WHITE)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.round(img).astype(np.uint8), "RGB").save(path, optimize=True)


def _number(v):
    return f"{v:.4f}".rstrip("0").rstrip(".")


def vector(path, comment, side, width):
    """The mark in white as a vector drawable of [side] dp, [width] dp wide in its middle. Each
    of the mark's two parts is a path of its own, which keeps them short enough to draw fast."""
    k = width / 100
    height = _bounds(subpaths(MARK))[3] * k
    parts = re.findall(r"M[^M]*", MARK)
    body = "\n".join(
        f'        <path\n            android:fillColor="#FFFFFFFF"\n            android:pathData="{part.strip()}" />'
        for part in parts
    )
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Made by tools/make_launcher_assets.py from tools/logo.svg. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{side}dp"
    android:height="{side}dp"
    android:viewportWidth="{side}"
    android:viewportHeight="{side}">
    <group
        android:translateX="{_number((side - width) / 2)}"
        android:translateY="{_number((side - height) / 2)}"
        android:scaleX="{_number(k)}"
        android:scaleY="{_number(k)}">
{body}
    </group>
</vector>
"""
    with open(path, "w") as f:
        f.write(xml)


if __name__ == "__main__":
    banner(os.path.join(RES, "drawable-xhdpi", "banner.png"))
    # The icon's layers are 108 dp, of which the system shows the middle 72 in its own shape and
    # keeps a circle of 66 whole: the mark's corners stay well inside it.
    vector(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
           "The mark for the launcher icon, on black (mipmap-anydpi/ic_launcher.xml).", 108, 46)
    vector(os.path.join(RES, "drawable", "ic_notification.xml"),
           "The mark for the speaker's notification (Android needs an icon there).", 24, 20)
    print("ok")
