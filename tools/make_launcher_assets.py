#!/usr/bin/env python3
"""Renders the banner for the TV's home screen and the launcher icons from the logo and the font.

Usage: python3 tools/make_launcher_assets.py   (needs Pillow)

The logo is tools/logo.svg: four rounded squares, each turned a little further toward the
middle, a ring and a light dot, blue on night blue. It is drawn here from the SVG at any size,
never scaled from a bitmap.
"""
import math
import os
import random
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
FONT = os.path.join(ROOT, "layers", "ui", "src", "androidMain", "res", "font", "jost.ttf")
LOGO = os.path.join(ROOT, "tools", "logo.svg")

INK = (232, 242, 255)


def _color(value):
    v = value.lstrip("#")
    return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))


def _arc(x1, y1, rx, ry, phi, large, sweep, x2, y2, steps=32):
    """Points along an SVG arc from (x1, y1) to (x2, y2), the start left out."""
    phi = math.radians(phi)
    c, s = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    xp, yp = c * dx + s * dy, -s * dx + c * dy
    rx, ry = abs(rx), abs(ry)
    lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
    den = rx * rx * yp * yp + ry * ry * xp * xp
    k = math.sqrt(max(0.0, num / den)) * (-1 if large == sweep else 1)
    cxp, cyp = k * rx * yp / ry, -k * ry * xp / rx
    cx, cy = c * cxp - s * cyp + (x1 + x2) / 2, s * cxp + c * cyp + (y1 + y2) / 2
    ux, uy = (xp - cxp) / rx, (yp - cyp) / ry
    vx, vy = (-xp - cxp) / rx, (-yp - cyp) / ry
    t1 = math.atan2(uy, ux)
    dt = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    out = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        x, y = rx * math.cos(t), ry * math.sin(t)
        out.append((c * x - s * y + cx, s * x + c * y + cy))
    return out


def _path(d):
    """The points of an SVG path made of M, H, V, A and Z (all absolute, as the logo's are)."""
    tokens = re.findall(r"[MHVAZ]|-?\d*\.?\d+(?:e-?\d+)?", d)
    pts, i, cmd = [], 0, None
    x = y = 0.0
    while i < len(tokens):
        if tokens[i] in "MHVAZ":
            cmd = tokens[i]
            i += 1
            if cmd == "Z":
                continue
        n = lambda k: float(tokens[i + k])  # noqa: E731
        if cmd == "M":
            x, y = n(0), n(1)
            pts.append((x, y))
            i += 2
        elif cmd == "H":
            x = n(0)
            pts.append((x, y))
            i += 1
        elif cmd == "V":
            y = n(0)
            pts.append((x, y))
            i += 1
        elif cmd == "A":
            rx, ry, phi, large, sweep, x2, y2 = (n(k) for k in range(7))
            pts += _arc(x, y, rx, ry, phi, int(large), int(sweep), x2, y2)
            x, y = x2, y2
            i += 7
        else:
            raise ValueError("unsupported path command " + str(cmd))
    return pts


def _rotated(pts, transform):
    m = re.match(r"rotate\(\s*([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s*\)", transform or "")
    if not m:
        return pts
    a, cx, cy = math.radians(float(m.group(1))), float(m.group(2)), float(m.group(3))
    c, s = math.cos(a), math.sin(a)
    return [(cx + (px - cx) * c - (py - cy) * s, cy + (px - cx) * s + (py - cy) * c) for px, py in pts]


def _shapes():
    """The logo's background color and its shapes, as drawn in tools/logo.svg."""
    root = ET.parse(LOGO).getroot()
    ns = "{http://www.w3.org/2000/svg}"
    background, shapes = None, []

    def walk(node, inherited):
        style = dict(inherited)
        style.update({k: v for k, v in node.attrib.items() if k in ("fill", "stroke", "stroke-width")})
        tag = node.tag.replace(ns, "")
        nonlocal background
        if tag == "rect":
            background = _color(node.get("fill"))
        elif tag == "circle":
            shapes.append(("circle", style, float(node.get("cx")), float(node.get("cy")), float(node.get("r"))))
        elif tag == "path":
            shapes.append(("path", style, _rotated(_path(node.get("d")), node.get("transform"))))
        for child in node:
            walk(child, style)

    walk(root, {})
    return background, shapes


NAVY, _SHAPES = _shapes()
# The SVG's square, and a square that holds just the logo with its outermost stroke.
FULL = 1024
TIGHT = 676


def logo(size, background=NAVY, extent=TIGHT):
    """The logo on a square of [size] px showing [extent] units of the 1024 unit SVG around its
    middle; without a background, on transparency."""
    ss = 4
    px = size * ss
    img = Image.new("RGBA", (px, px), (background + (255,)) if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = px / extent
    off = FULL / 2

    def at(x, y):
        return (px / 2 + (x - off) * k, px / 2 + (y - off) * k)

    for shape in _SHAPES:
        style = shape[1]
        stroke = style.get("stroke") not in (None, "none")
        width = float(style.get("stroke-width", 0)) * k
        if shape[0] == "circle":
            _, _, cx, cy, r = shape
            (x, y) = at(cx, cy)
            if stroke:
                outer = r * k + width / 2
                d.ellipse([x - outer, y - outer, x + outer, y + outer], outline=_color(style["stroke"]) + (255,), width=max(1, round(width)))
            else:
                d.ellipse([x - r * k, y - r * k, x + r * k, y + r * k], fill=_color(style["fill"]) + (255,))
        else:
            pts = [at(x, y) for x, y in shape[2]]
            d.line(pts + pts[:2], fill=_color(style["stroke"]) + (255,), width=max(1, round(width)), joint="curve")
    # Scaled down with the color premultiplied, so the edges keep their color on transparency.
    return img.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


def jost(size, weight=400):
    f = ImageFont.truetype(FONT, size)
    vals = []
    for a in f.get_variation_axes():
        name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
        vals.append({"Weight": weight}.get(name, a["default"]))
    f.set_variation_by_axes(vals)
    return f


def dither(img, amount=1.2, seed=3):
    rnd = random.Random(seed)
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, *rest = px[x, y]
            n = (rnd.random() + rnd.random() - 1.0) * amount
            px[x, y] = (max(0, min(255, int(r + n))), max(0, min(255, int(g + n))), max(0, min(255, int(b + n))), *rest)
    return img


def banner(path, w=320, h=180):
    """The logo beside the name, both centered on the banner as a group."""
    s = 4
    img = Image.new("RGB", (w * s, h * s), NAVY)
    side = int(h * s * 0.50)
    mark = logo(side, background=None)
    f = jost(int(37 * s))
    d = ImageDraw.Draw(img)
    text = "Klangbild"
    tw = d.textlength(text, font=f)
    gap = int(17 * s)
    x0 = int((w * s - (side + gap + tw)) / 2)
    y0 = (h * s - side) // 2
    img.paste(mark, (x0, y0), mark)
    ascent, _ = f.getmetrics()
    # The cap height (0.70 em) centered on the logo.
    cap = 0.70 * 37 * s
    d.text((x0 + side + gap, int(h * s / 2 + cap / 2 - ascent)), text, font=f, fill=INK)
    img = img.resize((w, h), Image.LANCZOS)
    dither(img)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def legacy_icon(path, size):
    """The logo filling most of a night blue square."""
    img = logo(size, extent=int(TIGHT / 0.80)).convert("RGB")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    banner(os.path.join(RES, "drawable-xhdpi", "banner.png"))
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        legacy_icon(os.path.join(RES, f"mipmap-{density}", "ic_launcher.png"), size)
    print("ok")
