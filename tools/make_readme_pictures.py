#!/usr/bin/env python3
"""Sets the README's screens in a room and saves its pictures, and draws the download button.

Usage: python3 tools/make_readme_pictures.py   (needs Pillow and numpy)

Render the screens first:
    ./gradlew :app:testDebugUnitTest --tests '*ReadmeShotsTest*' -Proborazzi.test.record=true
They land in app/build/screenshots/readme/, each with the colors the lights behind the TV would
show (a JSON file beside it). The pictures go to docs/screenshots/.

The room is lit in linear light: a painted wall, a little light from the room, and the lights
behind the TV, each a soft lamp hidden behind the screen's edge, shining on the wall in the color
the app sends it. The screen is the app's own render, set behind glass in a thin frame.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RENDERS = os.path.join(ROOT, "app", "build", "screenshots", "readme")
OUT = os.path.join(ROOT, "docs", "screenshots")
FONTS = os.path.join(ROOT, "app", "src", "main", "res", "font")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_launcher_assets import NAVY, logo  # noqa: E402  (the same mark as on the TV's home screen)


# Color ---------------------------------------------------------------------------------------

def to_linear(srgb):
    c = np.asarray(srgb, dtype=np.float64) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_srgb(lin):
    x = np.clip(lin, 0.0, 1.0)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055) * 255.0


def tonemap(x):
    """A filmic curve: soft highlights, so a lamp's core runs toward its color's white."""
    x = np.maximum(x, 0.0)
    return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0)


def xy_to_rgb(x, y):
    """The color of light with chromaticity xy, as linear sRGB scaled to a peak of 1."""
    X, Y, Z = x / y, 1.0, (1 - x - y) / y
    r = 3.2406 * X - 1.5372 * Y - 0.4986 * Z
    g = -0.9689 * X + 1.8758 * Y + 0.0415 * Z
    b = 0.0557 * X - 0.2040 * Y + 1.0570 * Z
    rgb = np.maximum(np.array([r, g, b]), 0.0)
    return rgb / rgb.max()


def kelvin(k):
    """The color of warm light of [k] kelvin (Planckian locus, Kim et al.), linear sRGB, peak 1."""
    t = float(k)
    x = (-0.2661239e9 / t ** 3 - 0.2343589e6 / t ** 2 + 0.8776956e3 / t + 0.179910) if t <= 4000 else \
        (-3.0258469e9 / t ** 3 + 2.1070379e6 / t ** 2 + 0.2226347e3 / t + 0.240390)
    if t <= 2222:
        y = -1.1063814 * x ** 3 - 1.34811020 * x ** 2 + 2.18555832 * x - 0.20219683
    elif t <= 4000:
        y = -0.9549476 * x ** 3 - 1.37418593 * x ** 2 + 2.09137015 * x - 0.16748867
    else:
        y = 3.0817580 * x ** 3 - 5.87338670 * x ** 2 + 3.75112997 * x - 0.37001483
    return xy_to_rgb(x, y)


def light_colors(name, vivid=1.0):
    """The lights' colors for a render: left, middle, right (linear sRGB) and how bright each may
    be. [vivid] above 1 takes each a little further from white along its own hue."""
    with open(os.path.join(RENDERS, name + ".json")) as f:
        data = json.load(f)["lights"]
    out = []
    for l in data:
        rgb = xy_to_rgb(*l["xy"])
        gray = rgb.mean()
        rgb = np.maximum(gray + (rgb - gray) * vivid, 0.0)
        out.append((rgb / rgb.max(), l["dim"]))
    return out


# Images --------------------------------------------------------------------------------------

def blur(a, sigma):
    """A Gaussian blur through the FFT, the edges mirrored."""
    if sigma <= 0:
        return a
    pad = int(3 * sigma) + 2
    widths = ((pad, pad), (pad, pad)) + ((0, 0),) * (a.ndim - 2)
    p = np.pad(a, widths, mode="reflect")
    h, w = p.shape[:2]
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.rfftfreq(w)[None, :]
    g = np.exp(-2 * (math.pi * sigma) ** 2 * (fx ** 2 + fy ** 2))
    if a.ndim == 3:
        g = g[..., None]
    out = np.fft.irfft2(np.fft.rfft2(p, axes=(0, 1)) * g, s=(h, w), axes=(0, 1))
    return out[pad:-pad, pad:-pad]


def noise(h, w, sigma, seed):
    """Smooth noise of unit spread at the scale [sigma] (px)."""
    n = blur(np.random.default_rng(seed).standard_normal((h, w)), sigma)
    return n / (n.std() + 1e-9)


def rounded_box(h, w, box, radius):
    """Coverage (0..1) of a rounded rectangle, antialiased."""
    x0, y0, x1, y1 = box
    y, x = np.mgrid[0:h, 0:w] + 0.5
    hx, hy = (x1 - x0) / 2 - radius, (y1 - y0) / 2 - radius
    qx = np.abs(x - (x0 + x1) / 2) - hx
    qy = np.abs(y - (y0 + y1) / 2) - hy
    d = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - radius
    return np.clip(0.5 - d, 0.0, 1.0)


def load(path, size):
    return np.asarray(Image.open(path).convert("RGB").resize(size, Image.LANCZOS), dtype=np.float64)


def font(name, size, weight, optical):
    """A bundled face at [size] px and [weight]; DM Sans also takes its optical size, as in the app."""
    f = ImageFont.truetype(os.path.join(FONTS, name), size)
    vals = []
    for a in f.get_variation_axes():
        axis = a["name"] if isinstance(a["name"], str) else a["name"].decode()
        vals.append({"Weight": weight, "Optical size": min(max(optical, a["minimum"]), a["maximum"])}.get(axis, a["default"]))
    f.set_variation_by_axes(vals)
    return f


def text_mask(text, face, size, weight, tracking=0.0, ss=4, optical=None):
    """The coverage of [text] set in [face] at [size] px, its cap height's top at the mask's top.
    DM Sans takes the [optical] size the text is seen at (its own size if none is given)."""
    f = font(face, size * ss, weight, optical=optical or size)
    ascent, descent = f.getmetrics()
    advance = sum(f.getlength(ch) for ch in text) + tracking * size * ss * (len(text) - 1)
    img = Image.new("L", (int(advance + size * ss), ascent + descent), 0)
    d = ImageDraw.Draw(img)
    x = 0.0
    for ch in text:
        d.text((x, 0), ch, font=f, fill=255)
        x += f.getlength(ch) + tracking * size * ss
    cap_top = ascent - int(0.70 * size * ss)
    img = img.crop((0, cap_top, int(x), ascent + descent))
    img = img.resize((max(1, round(img.width / ss)), max(1, round(img.height / ss))), Image.LANCZOS)
    return np.asarray(img, dtype=np.float64) / 255.0


def paste(canvas, mask, x, y, color, alpha=1.0):
    """Lays [color] over [canvas] (sRGB floats) through [mask] at (x, y)."""
    h, w = mask.shape
    region = canvas[y:y + h, x:x + w]
    a = mask[: region.shape[0], : region.shape[1], None] * alpha
    region[:] = region * (1 - a) + np.asarray(color, dtype=np.float64) * a


def save(img, name, quality=92):
    os.makedirs(OUT, exist_ok=True)
    rng = np.random.default_rng(11)
    # Fine grain, as in a photograph, which also keeps the soft gradients from banding.
    grain = blur(rng.standard_normal(img.shape[:2]), 0.6)
    grain = grain / grain.std() * 1.3
    out = np.clip(img + grain[..., None], 0, 255).astype(np.uint8)
    path = os.path.join(OUT, name)
    Image.fromarray(out).save(path, quality=quality, optimize=True, progressive=True, subsampling=0)
    print(path, os.path.getsize(path) // 1024, "KB")


# The room ------------------------------------------------------------------------------------

class Tv:
    """A wall-hung TV: the screen's box (px), its frame and its corner."""

    def __init__(self, cx, cy, width, bezel):
        self.w = width
        self.h = width * 9 / 16
        self.x0, self.y0 = cx - self.w / 2, cy - self.h / 2
        self.x1, self.y1 = cx + self.w / 2, cy + self.h / 2
        self.bezel = bezel
        self.cx, self.cy = cx, cy

    @property
    def outer(self):
        b = self.bezel
        return self.x0 - b, self.y0 - b, self.x1 + b, self.y1 + b


def lamps(tv, colors, strength):
    """The lights behind the TV: left, above the middle, right, each a soft lamp hidden near the
    screen's edge and aimed outward a little."""
    (left, dim_l), (top, dim_t), (right, dim_r) = colors
    w, h = tv.w, tv.h
    return [
        ((tv.x0 + 0.10 * w, tv.cy - 0.04 * h), left * dim_l * strength, 0.22 * h, (-0.9, -0.45)),
        ((tv.cx, tv.y0 + 0.08 * h), top * dim_t * strength * 0.85, 0.24 * h, (0.0, -1.0)),
        ((tv.x1 - 0.10 * w, tv.cy - 0.04 * h), right * dim_r * strength, 0.22 * h, (0.9, -0.45)),
    ]


def wall(h, w, albedo, ambient, lights, seed=5):
    """The wall in linear light: matte paint ([albedo]) with a soft unevenness, lit by the room's
    [ambient] light (an array or a color) and by [lights] (position, color, spread, aim): each
    lamp's light falls off as a tight pool and a wide, faint spill."""
    y, x = np.mgrid[0:h, 0:w] + 0.5
    mottle = noise(h, w, 90, seed) * 0.010 + noise(h, w, 22, seed + 1) * 0.004
    irradiance = np.broadcast_to(np.asarray(ambient, dtype=np.float64), (h, w, 3)).copy()
    for (px, py), color, spread, (ax, ay) in lights:
        dx, dy = x - px, y - py
        d = np.hypot(dx, dy) + 1e-6
        pool = (1 + (d / spread) ** 2) ** -1.5
        spill = (1 + (d / (spread * 2.6)) ** 2) ** -1.5
        falloff = 0.72 * pool + 0.28 * spill
        aim = np.clip(1 + 0.5 * (dx * ax + dy * ay) / d, 0.3, None)
        irradiance += (falloff * aim)[..., None] * color
    return np.asarray(albedo, dtype=np.float64) * (1 + mottle)[..., None] * irradiance


def tv_frame(img, tv, screen, glow_lin, sheen=0.05, window=0.0):
    """Sets the TV into [img] (sRGB floats): a slim graphite frame whose outer rim catches the
    light around it ([glow_lin], the wall's light in linear terms), and the screen behind glass
    with a faint sheen and, by day, the soft reflection of a window ([window], its strength)."""
    h, w = img.shape[:2]
    outer = rounded_box(h, w, tv.outer, 5.0)
    inner = rounded_box(h, w, (tv.x0, tv.y0, tv.x1, tv.y1), 1.5)
    # The frame: graphite, a hair lighter toward its outer rim, the rim lit by the wall's light.
    rim = np.clip(outer - rounded_box(h, w, np.add(tv.outer, (2.2, 2.2, -2.2, -2.2)), 3.8), 0, 1)
    frame = np.full((h, w, 3), 17.0)
    lit = to_srgb(tonemap(glow_lin))
    frame = frame + rim[..., None] * np.clip(lit * 0.55 - 6.0, 0, None)
    img[:] = img * (1 - outer[..., None]) + frame * outer[..., None]
    # The screen.
    sw, sh = int(round(tv.x1 - tv.x0)), int(round(tv.y1 - tv.y0))
    shot = load(screen, (sw, sh))
    ys, xs = int(round(tv.y0)), int(round(tv.x0))
    region = img[ys:ys + sh, xs:xs + sw]
    m = inner[ys:ys + sh, xs:xs + sw, None]
    # Glass: a broad sheen from the upper left, fading across the screen.
    v, u = np.mgrid[0:sh, 0:sw]
    s = np.clip(1.0 - (u / sw * 0.85 + v / sh * 0.75), 0, 1) ** 2.0 * sheen * 255
    if window > 0:
        # A window far to the left: a tall, slanted pane of light, its bars dark, all out of focus.
        a = u / sw - 0.10 + (v / sh) * 0.22
        pane = ((a > 0.0) & (a < 0.24)).astype(np.float64) * (v / sh < 0.92)
        bars = (np.abs(a - 0.12) < 0.006) | (np.abs(v / sh - 0.40) < 0.006)
        pane = blur(pane * (1 - bars), 0.012 * sw) * np.clip(1.15 - v / sh, 0, 1)
        s = s + pane * window * 255
    region[:] = region * (1 - m) + np.clip(shot + s[..., None] * (1 - shot / 255.0), 0, 255) * m


# The pictures --------------------------------------------------------------------------------

def dark_room(W, H, tv, colors, strength, albedo=(122, 118, 114), ambient=0.008, room=2700):
    """A wall in the evening: the lamps behind the TV and a breath of warm light from the room,
    darker toward the picture's edges. Returns the image (sRGB floats) and the wall's light
    (linear)."""
    lights = lamps(tv, colors, strength)
    y, x = np.mgrid[0:H, 0:W] + 0.5
    r2 = ((x - tv.cx) / (W * 0.6)) ** 2 + ((y - tv.cy) / (H * 0.7)) ** 2
    fill = (ambient * np.exp(-r2 * 1.3))[..., None] * kelvin(room)
    paint = to_linear(list(albedo))
    lin = wall(H, W, paint, fill, lights)
    return to_srgb(tonemap(lin)), lin / paint


def logo_tile(side, ss=4):
    """The logo on its own night blue tile with round corners, as on the TV's home screen: its
    colors and its coverage, for light backgrounds, where the bare logo would fade."""
    tile = Image.new("L", (side * ss, side * ss), 0)
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, side * ss - 1, side * ss - 1], radius=int(side * 0.24 * ss), fill=255)
    a = np.asarray(tile.resize((side, side), Image.LANCZOS), dtype=np.float64) / 255.0
    return np.asarray(logo(side), dtype=np.float64)[..., :3], a[..., None]


TAGLINE = "Look what you\u2019re listening to."


def title(img, cx, middle, cap, color=(236, 239, 244), quiet=(172, 178, 190)):
    """ "Klangbild" over its tagline, both centered on [cx] by their ink and the two together on
    [middle] (the name's cap height to the tagline's baseline). The tagline is set in the text face
    at the optical size it is seen at on a page, in the quieter ink."""
    word = text_mask("Klangbild", "jost.ttf", cap / 0.70, 400, tracking=-0.005)
    small = 0.41 * cap
    line = text_mask(TAGLINE, "dmsans.ttf", small / 0.70, 400, optical=14)
    gap = 0.62 * cap
    top = middle - (cap + gap + small) / 2
    for mask, y, ink, alpha in ((word, top, color, 0.96), (line, top + cap + gap, quiet, 0.94)):
        cols = np.nonzero(mask.max(axis=0) > 0.02)[0]
        paste(img, mask, int(round(cx - (cols[0] + cols[-1] + 1) / 2)), int(round(y)), ink, alpha)


def now_playing():
    """The first picture: the song at night, the wall lit in its colors, the name and its tagline above."""
    W, H = 2400, 1500
    tv = Tv(W / 2, 872, 1480, 9)
    img, light = dark_room(W, H, tv, light_colors("now-playing"), 1.15)
    tv_frame(img, tv, os.path.join(RENDERS, "now-playing.png"), light * 0.16)
    title(img, W / 2, 223, 68)
    save(img, "mockup-now-playing.jpg")


def now_playing_light():
    """The first picture for a light page: the same song by day on the light wall, the room in
    daylight and the lights behind the TV off, as they are by day; the name and its tagline above
    in night blue."""
    W, H = 2400, 1500
    tv = Tv(W / 2, 872, 1480, 9)
    img, rim = daylight_room(W, H, tv)
    tv_frame(img, tv, os.path.join(RENDERS, "now-playing-light.png"), rim, sheen=0.03, window=0.10)
    title(img, W / 2, 223, 68, color=(22, 32, 58), quiet=(84, 98, 128))
    save(img, "mockup-now-playing-light.jpg")


def smooth(e0, e1, v):
    """0 up to [e0], 1 from [e1], a smooth step between."""
    t = np.clip((v - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def streaks(h, w, along, across, seed, vertical=False):
    """Noise around 0, long along one axis ([along] px) and short across it ([across] px): wood
    grain, the weave of linen."""
    r = np.random.default_rng(seed)
    shape = (h // along + 2, w // across + 2) if vertical else (h // across + 2, w // along + 2)
    small = r.standard_normal(shape)
    img = Image.fromarray(((small - small.min()) / np.ptp(small) * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
    return np.asarray(img, dtype=np.float32) / 255.0 - 0.5


class Box:
    """A part of a picture of [W] x [H] px with its own pixel grid, so each thing in the room is
    worked out only where it stands."""

    def __init__(self, x0, y0, x1, y1, W, H):
        self.x0, self.y0 = max(0, int(math.floor(x0))), max(0, int(math.floor(y0)))
        self.x1, self.y1 = min(W, int(math.ceil(x1))), min(H, int(math.ceil(y1)))
        self.h, self.w = self.y1 - self.y0, self.x1 - self.x0
        self.yy, self.xx = np.mgrid[self.y0:self.y1, self.x0:self.x1].astype(np.float32) + 0.5

    def polygon(self, points, ss=4):
        """The coverage of a polygon (picture px) in this box, antialiased."""
        img = Image.new("L", (self.w * ss, self.h * ss), 0)
        ImageDraw.Draw(img).polygon([((x - self.x0) * ss, (y - self.y0) * ss) for x, y in points], fill=255)
        return np.asarray(img.resize((self.w, self.h), Image.BOX), dtype=np.float32) / 255.0

    def noise(self, sigma, seed):
        return noise(self.h, self.w, sigma, seed).astype(np.float32)


class Layer:
    """A thing standing in the room: its coverage, its color premultiplied by it (linear), and the
    row its foot stands on, about which the sideboard's top mirrors it."""

    def __init__(self, box, alpha, rgb, foot):
        self.box, self.alpha, self.rgb, self.foot = box, np.nan_to_num(alpha), np.nan_to_num(rgb), foot

    def over(self, canvas):
        b = self.box
        region = canvas[b.y0:b.y1, b.x0:b.x1]
        region[:] = region * (1 - self.alpha[..., None]) + self.rgb


class Room:
    """A living room at night, worked out in 3D and photographed square to its wall: the TV over a
    walnut sideboard with reeded doors, a table lamp with a linen shade and a few books on the
    left, three pillar candles and a record sleeve on the right. Lengths are in cm: x across from
    the picture's middle, y up from the sideboard's top, z out from the wall toward the camera.

    The light falls as it would. The bulb shines only through the shade's open top and bottom,
    which draws the two bright fans on the wall and a pool on the sideboard, round the shadow of
    the lamp's base; the shade's cloth glows all round; each candle's flame is kept by its own rim
    from what lies far below it; the room gives back a little warm light."""

    W, H = 2400, 1350
    S, DIST, EYE = 8.75, 457.0, 69.7       # px per cm on the wall (a 65 inch TV is 1260 px); the camera's distance and eye height
    VX, VY = W / 2, 500.0                  # where the camera's axis meets the picture: the TV's middle
    DEPTH, TOP_X = 40.0, 112.0             # the sideboard: 40 cm deep, 224 cm long
    LAMP_X, LAMP_Z = -89.6, 20.0
    BULB_Y, BULB_R = 31.0, 2.2
    SH_BOT, SH_TOP, R_BOT, R_TOP = 24.0, 43.0, 15.0, 12.0
    BASE_TOP = 20.5
    CANDLES = [(84.0, 16.0, 15.0), (92.0, 25.0, 10.5), (77.0, 27.0, 7.5)]   # x, z, height
    CR, FLAME_UP = 3.75, 1.7                                                # their radius; the flame's middle above the wax
    BOOKS = [                                                               # x0, x1, z0, z1, y0, y1, cloth
        (-72.0, -48.0, 20.0, 36.5, 0.0, 3.0, (176, 166, 146)),
        (-71.2, -48.6, 21.0, 36.2, 3.0, 5.4, (84, 96, 104)),
        (-70.6, -49.0, 21.4, 35.6, 5.4, 8.2, (132, 80, 60)),
    ]
    SLEEVE = (80.0, 111.4, 31.3, 3.0, 0.2)                                  # x0, x1, height, z at its foot and at its top
    BULB_I, SHADE_I, CANDLE_I, AMBIENT = 1000.0, 800.0, 65.0, 0.085

    def __init__(self):
        self.F = self.S * self.DIST
        self.lamp_col = kelvin(2700).astype(np.float32)
        self.candle_col = kelvin(1950).astype(np.float32)
        # What lights the sideboard and the lamp's base from the shade's open bottom: the bulb
        # (a small source, 60%) and the shade's lit inside (a broad one, 40%).
        self.down = [(self.LAMP_X + self.BULB_R * math.sqrt((i + 0.5) / 9) * math.cos(2.4 * i), self.BULB_Y,
                      self.LAMP_Z + self.BULB_R * math.sqrt((i + 0.5) / 9) * math.sin(2.4 * i), 0.6 / 9) for i in range(9)]
        self.down += [(self.LAMP_X + 11.0 * math.cos(math.pi * i / 5), 27.5, self.LAMP_Z + 11.0 * math.sin(math.pi * i / 5), 0.064)
                      for i in range(10)]
        # The shade's cloth as small glowing patches, each as bright as the bulb lights it.
        self.cloth = []
        for y in (26.0, 30.0, 34.0, 38.0, 42.0):
            r = self.shade_r(y)
            for i in range(12):
                a = 2 * math.pi * (i + 0.5) / 12
                n = math.sqrt(1 + 0.156 ** 2)
                self.cloth.append((self.LAMP_X + r * math.cos(a), y, self.LAMP_Z + r * math.sin(a),
                                   math.cos(a) / n, 0.156 / n, math.sin(a) / n, float(self.shade_glow(y)) * r * 2 * math.pi / 12 * 3.8))
        self.cloth_sum = sum(c[6] for c in self.cloth)

    # The camera.
    def k(self, z):
        """Px per cm at [z] cm out from the wall."""
        return self.F / (self.DIST - z)

    def px(self, x, z):
        return self.VX + x * self.k(z)

    def py(self, y, z):
        return self.VY + (self.EYE - y) * self.k(z)

    def tilt(self, y, z):
        """The sine of the angle the camera looks down on a level circle at height [y], [z] out."""
        return (self.EYE - y) / math.hypot(self.EYE - y, self.DIST - z)

    def box(self, x0, y0, x1, y1):
        return Box(x0, y0, x1, y1, self.W, self.H)

    # The lamp.
    def base_r(self, y):
        """The lamp's base, a ginger jar: its radius at height [y], widest a little above its
        middle, rounding into a short neck on top and onto a broad foot."""
        y = np.asarray(y, dtype=np.float64)
        t = np.where(y < 11.5, (11.5 - y) / 13.5, (y - 11.5) / 9.2)
        r = 8.6 * np.clip(1 - np.clip(t, 0, 1) ** 2.2, 0, 1) ** (1 / 2.2)
        return np.where((y >= 0) & (y <= self.BASE_TOP), r, 0.0)

    def shade_r(self, y):
        return self.R_BOT + (self.R_TOP - self.R_BOT) * (y - self.SH_BOT) / (self.SH_TOP - self.SH_BOT)

    def shade_glow(self, y):
        """How brightly the shade's cloth glows at height [y]: 1 at the bulb's height."""
        r = self.shade_r(y)
        return (r / np.hypot(r, y - self.BULB_Y) ** 3) * self.shade_r(self.BULB_Y) ** 2

    def through_shade(self, vx, vy, vz):
        """How much of the bulb's light leaves toward (vx, vy, vz): through the shade's open top and
        bottom only, the rims softened by the bulb's size."""
        m = vy / (np.hypot(vx, vz) + 1e-6)
        top, bot = self.SH_TOP - self.BULB_Y, self.BULB_Y - self.SH_BOT
        up = smooth(top / (self.R_TOP + self.BULB_R), top / (self.R_TOP - self.BULB_R), m)
        down = smooth(bot / (self.R_BOT + self.BULB_R), bot / (self.R_BOT - self.BULB_R), -m)
        return up + down

    def bulb_light(self, p, n):
        vx, vy, vz = p[0] - self.LAMP_X, p[1] - self.BULB_Y, p[2] - self.LAMP_Z
        d2 = vx * vx + vy * vy + vz * vz + 1e-6
        cos = np.clip(-(n[0] * vx + n[1] * vy + n[2] * vz) / np.sqrt(d2), 0, None)
        return self.BULB_I * self.through_shade(vx, vy, vz) * cos / d2

    def shade_light(self, p, n):
        e = 0.0
        for cx, cy, cz, nx, ny, nz, a in self.cloth:
            vx, vy, vz = p[0] - cx, p[1] - cy, p[2] - cz
            d2 = vx * vx + vy * vy + vz * vz + 2.0
            d = np.sqrt(d2)
            e = e + a * np.clip((nx * vx + ny * vy + nz * vz) / d, 0, None) * np.clip(-(n[0] * vx + n[1] * vy + n[2] * vz) / d, 0, None) / d2
        return self.SHADE_I * e / self.cloth_sum

    # The candles.
    def blocked_by(self, i, fx, fy, fz, p):
        """How much candle [i]'s body stands between a flame and [p]."""
        ox, oz, oh = self.CANDLES[i]
        dx, dz = p[0] - fx, p[2] - fz
        t = np.clip(((ox - fx) * dx + (oz - fz) * dz) / (dx * dx + dz * dz + 1e-6), 0, 1)
        miss = np.hypot(fx + t * dx - ox, fz + t * dz - oz)
        y = fy + t * (p[1] - fy)
        inside = smooth(0.0, 0.08, t) * (1 - smooth(0.92, 1.0, t))
        return (1 - smooth(self.CR - 1.6, self.CR + 1.6, miss)) * (1 - smooth(oh - 1.6, oh + 1.6, y)) * inside

    def candle_light(self, p, n, own=None):
        e = 0.0
        for i, (cx, cz, h) in enumerate(self.CANDLES):
            if i == own:
                continue
            fy = h + self.FLAME_UP
            vx, vy, vz = p[0] - cx, p[1] - fy, p[2] - cz
            d2 = vx * vx + vy * vy + vz * vz + 0.5
            cos = np.clip(-(n[0] * vx + n[1] * vy + n[2] * vz) / np.sqrt(d2), 0, None)
            rho = np.hypot(vx, vz)
            # The candle's own rim keeps its flame's light off what lies far enough below it.
            drop = -vy - (fy - h) * rho / self.CR
            seen = 1 - smooth(-1.0 - 0.25 * rho, 1.0 + 0.25 * rho, drop)
            for j in range(len(self.CANDLES)):
                if j not in (i, own):
                    seen = seen * (1 - self.blocked_by(j, cx, fy, cz, p))
            e = e + self.CANDLE_I * seen * cos / d2
        return e

    def ambient(self, x):
        """The room's own light, given back by ceiling and floor: warm, more of it near the lamp."""
        x = np.asarray(x, dtype=np.float32)
        lamp = (0.55 + 0.45 * np.exp(-((x - self.LAMP_X) / 140.0) ** 2))[..., None] * self.lamp_col
        candles = np.exp(-((x - 86.0) / 70.0) ** 2)[..., None] * self.candle_col * 0.25
        return (lamp + candles) * self.AMBIENT

    def light(self, p, n, own=None, ambient=1.0):
        """All the light falling on points [p] with normals [n] (linear RGB)."""
        return ((self.bulb_light(p, n) + self.shade_light(p, n))[..., None] * self.lamp_col
                + self.candle_light(p, n, own)[..., None] * self.candle_col + self.ambient(p[0]) * ambient)

    # The things in the room.
    def sleeve_corners(self):
        x0, x1, h, zf, zt = self.SLEEVE
        return [(self.px(x0, zt), self.py(h, zt)), (self.px(x1, zt), self.py(h, zt)),
                (self.px(x1, zf), self.py(0, zf)), (self.px(x0, zf), self.py(0, zf))]

    def sleeve(self, cover):
        """A record sleeve leaning on the wall behind the candles, its print a little faded."""
        x0, x1, h, zf, zt = self.SLEEVE
        pts = self.sleeve_corners()
        b = self.box(min(p[0] for p in pts) - 2, min(p[1] for p in pts) - 2, max(p[0] for p in pts) + 2, max(p[1] for p in pts) + 2)
        alpha = b.polygon(pts)
        v = np.clip((b.yy - pts[0][1]) / (pts[3][1] - pts[0][1]), 0, 1)
        left = pts[0][0] + (pts[3][0] - pts[0][0]) * v
        right = pts[1][0] + (pts[2][0] - pts[1][0]) * v
        u = np.clip((b.xx - left) / (right - left), 0, 1)
        art = np.asarray(cover.resize((512, 512), Image.LANCZOS), dtype=np.float32)
        albedo = to_linear(art[(v * 511).astype(int), (u * 511).astype(int)]).astype(np.float32)
        albedo = (albedo.mean(axis=-1, keepdims=True) * 0.3 + albedo * 0.7) * 0.9 + 0.01
        lean = math.atan2(zf - zt, h)
        e = self.light((x0 + (x1 - x0) * u, h * (1 - v), zf + (zt - zf) * (1 - v)), (0.0, math.sin(lean), math.cos(lean)), ambient=0.9)
        worn = 1 + 0.10 * np.exp(-np.minimum(np.minimum(u, 1 - u), np.minimum(v, 1 - v)) / 0.006)
        return Layer(b, alpha, albedo * e * (worn * alpha)[..., None], pts[3][1])

    def candle(self, i):
        """A pillar candle of ivory wax: lit by the other flames, the lamp and the room, glowing
        through the wax just below its own flame; on top the melted pool."""
        cx, cz, h = self.CANDLES[i]
        k = self.k(cz)
        ax, w = self.px(cx, cz), self.CR * k
        yt, yb = self.py(h, cz), self.py(0, cz)
        st, sb = self.tilt(h, cz), self.tilt(0, cz)
        b = self.box(ax - w - 3, yt - w * st - 3, ax + w + 3, yb + w * sb + 3)
        dx, yy = b.xx - ax, b.yy
        arc = yb + w * sb * np.sqrt(np.clip(1 - (dx / w) ** 2, 0, 1))
        body = np.clip(w - np.abs(dx) + 0.5, 0, 1) * np.clip(arc - yy + 0.5, 0, 1) * np.clip(yy - yt + 0.5, 0, 1)
        top = np.clip(1.0 - np.sqrt((dx / w) ** 2 + ((yy - yt) / (w * st)) ** 2), 0, None)
        top_a = np.clip(top * w * st + 0.5, 0, 1) * (np.abs(dx) < w + 1) * (np.abs(yy - yt) < w * st + 1)
        alpha = np.maximum(body, top_a)
        s = np.clip(dx / w, -1, 1)
        c = np.sqrt(1 - s * s)
        y = np.clip(h - (yy - yt) / k + self.CR * c * st, 0, h)
        wax = to_linear([238, 228, 208]).astype(np.float32)
        e = self.light((cx + self.CR * s, y, cz + self.CR * c), (s, np.zeros_like(s), c), own=i)
        glow = (0.80 * np.exp(-(h - y) / 1.1) + 0.05 * np.exp(-(h - y) / 3.5)) * (0.5 + 0.5 * c)
        rgb = wax * e + (glow * 0.62)[..., None] * wax * self.candle_col
        pool = (0.45 + 0.55 * smooth(0.55, 0.95, 1 - top))[..., None] * wax * self.candle_col
        rgb = np.where(((top_a > 0) & (yy < yt + w * st))[..., None], pool, rgb)
        return Layer(b, alpha, rgb * alpha[..., None], yb)

    def lamp(self):
        """The lamp: a speckled stoneware base lit on its shoulder from the shade's open bottom,
        a brass neck, and the linen shade glowing from inside, its lit inside seen above the far
        rim of its open top."""
        k = self.k(self.LAMP_Z)
        ax = self.px(self.LAMP_X, self.LAMP_Z)
        wt, wb = self.R_TOP * k, self.R_BOT * k
        t_top, t_bot = self.tilt(self.SH_TOP, self.LAMP_Z), self.tilt(self.SH_BOT, self.LAMP_Z)
        yt, yb = self.py(self.SH_TOP, self.LAMP_Z), self.py(self.SH_BOT, self.LAMP_Z)
        foot = self.py(0, self.LAMP_Z)
        b = self.box(ax - wb - 4, yt - wt * t_top - 4, ax + wb + 4, foot + 4)
        dx, yy = b.xx - ax, b.yy
        # The base.
        y = self.EYE - (yy - self.VY) / k
        r = self.base_r(y)
        a_base = np.clip(r * k - np.abs(dx) + 0.5, 0, 1) * (y > 0) * (y < self.BASE_TOP) * np.clip(foot + 1.0 - yy, 0, 1)
        slope = np.arctan(-(self.base_r(y + 0.05) - self.base_r(y - 0.05)) / 0.1)
        s = np.clip(dx / np.maximum(r * k, 1e-3), -1, 1)
        c = np.sqrt(1 - s * s)
        n = (s * np.cos(slope), np.sin(slope), c * np.cos(slope))
        p = (self.LAMP_X + r * s, y, self.LAMP_Z + r * c)
        direct = 0.0
        for sx, sy, sz, weight in self.down:
            vx, vy, vz = p[0] - sx, p[1] - sy, p[2] - sz
            d2 = vx * vx + vy * vy + vz * vz
            direct = direct + weight * np.clip(-(n[0] * vx + n[1] * vy + n[2] * vz) / np.sqrt(d2), 0, None) / d2
        e = (self.BULB_I * direct + self.shade_light(p, n))[..., None] * self.lamp_col
        e = e + self.ambient(p[0]) * (0.6 + 0.4 * n[2])[..., None] + self.candle_light(p, n)[..., None] * self.candle_col
        # Light given back by the bright top around its foot, and by the bright wall behind it at its sides.
        e = e + (0.06 * (0.5 - 0.5 * n[1]) * c + 0.10 * np.abs(s) ** 3)[..., None] * self.lamp_col
        rng = np.random.default_rng(31)
        speck = blur((rng.random((b.h, b.w)) > 0.9975).astype(np.float32), 0.7)
        speck = 1 - 0.55 * speck / max(speck.max(), 1e-6)
        rgb = to_linear([214, 204, 190]).astype(np.float32) * e * (speck * (1 + 0.03 * b.noise(3, 32)))[..., None]
        # Its glaze mirrors the shade's open bottom on the shoulder.
        vy, vz = self.tilt(10.0, self.LAMP_Z), math.sqrt(1 - self.tilt(10.0, self.LAMP_Z) ** 2)
        nv = n[1] * vy + n[2] * vz
        rx, ry, rz = 2 * nv * n[0], 2 * nv * n[1] - vy, 2 * nv * n[2] - vz
        t = np.where(ry > 1e-3, (self.SH_BOT - y) / np.maximum(ry, 1e-3), 1e9)
        sees = (ry > 0) * (1 - smooth(self.R_BOT - 4, self.R_BOT + 1, np.hypot(p[0] + t * rx - self.LAMP_X, p[2] + t * rz - self.LAMP_Z)))
        rgb = rgb + (sees * (0.07 + 0.5 * (1 - np.clip(nv, 0, 1)) ** 5) * 1.4)[..., None] * self.lamp_col
        # The neck.
        nr = 0.95 * k
        a_neck = np.clip(nr - np.abs(dx) + 0.5, 0, 1) * (y >= self.BASE_TOP - 0.6) * (y < self.SH_BOT + 1)
        ns = np.clip(dx / nr, -1, 1)
        neck = to_linear([170, 140, 100]).astype(np.float32) * self.lamp_col \
            * (0.10 + 0.5 * np.exp(-((ns + 0.25) / 0.3) ** 2) + 0.15 * smooth(self.SH_BOT - 3, self.SH_BOT, y))[..., None]
        # The shade.
        half = wt + (wb - wt) * np.clip((yy - yt) / (yb - yt), 0, 1)
        front_top = yt + wt * t_top * np.sqrt(np.clip(1 - (dx / wt) ** 2, 0, 1))
        front_bot = yb + wb * t_bot * np.sqrt(np.clip(1 - (dx / wb) ** 2, 0, 1))
        opening = np.clip((1 - np.sqrt((dx / wt) ** 2 + ((yy - yt) / (wt * t_top)) ** 2)) * wt * t_top + 0.5, 0, 1)
        side = np.clip(half - np.abs(dx) + 0.5, 0, 1) * np.clip(yy - yt + 0.5, 0, 1) * np.clip(front_bot - yy + 0.5, 0, 1)
        a_shade = np.maximum(side, opening)
        ys = self.SH_TOP - (self.SH_TOP - self.SH_BOT) * np.clip((yy - front_top) / np.maximum(front_bot - front_top, 1), 0, 1)
        round_ = np.sqrt(np.clip(1 - (dx / np.maximum(half, 1)) ** 2, 0, 1))
        linen = to_linear([236, 226, 206]).astype(np.float32) * self.lamp_col
        weave = 1 + 0.035 * streaks(b.h, b.w, 14, 1, 41) + 0.025 * streaks(b.h, b.w, 10, 1, 42, vertical=True) + 0.02 * b.noise(0.8, 43)
        hems = 1 - 0.28 * np.exp(-((yy - front_top) / 2.2) ** 2) - 0.22 * np.exp(-((yy - front_bot + 1.5) / 2.2) ** 2)
        shade = (self.shade_glow(ys) * (0.86 + 0.14 * round_) * weave * hems * 1.75)[..., None] * linen
        inside = (2.6 * (0.8 + 0.2 * round_))[..., None] * linen
        shade = np.where(((opening > 0) & (yy < front_top))[..., None], inside, shade)
        # Base, neck, shade, one over the other.
        alpha, rgb = a_base, np.nan_to_num(rgb) * a_base[..., None]
        for a, col in ((a_neck * (1 - a_shade), neck), (a_shade, shade)):
            rgb = rgb * (1 - a[..., None]) + col * a[..., None]
            alpha = alpha + a * (1 - alpha)
        return Layer(b, alpha, rgb, foot)

    def books(self):
        """Three hardcovers in book cloth, spines to the room, the pages' edges on the right side."""
        px, py = self.px, self.py
        xs = [px(x, z) for (x0, x1, z0, z1, _, _, _) in self.BOOKS for x in (x0, x1) for z in (z0, z1)]
        ys = [py(y, z) for (_, _, z0, z1, y0, y1, _) in self.BOOKS for y in (y0, y1) for z in (z0, z1)]
        b = self.box(min(xs) - 3, min(ys) - 3, max(xs) + 3, max(ys) + 3)
        xx, yy = b.xx, b.yy
        alpha = np.zeros((b.h, b.w), np.float32)
        rgb = np.zeros((b.h, b.w, 3), np.float32)
        pages = to_linear([226, 216, 196]).astype(np.float32)
        for i, (x0, x1, z0, z1, y0, y1, cloth) in enumerate(self.BOOKS):
            c = to_linear(list(cloth)).astype(np.float32)
            # The right side (the plane x = x1): each pixel's depth from its column, its height from its row.
            side = b.polygon([(px(x1, z1), py(y1, z1)), (px(x1, z0), py(y1, z0)), (px(x1, z0), py(y0, z0)), (px(x1, z1), py(y0, z1))])
            z = np.clip(self.DIST - x1 * self.F / np.where(np.abs(xx - self.VX) < 1, -1, xx - self.VX), z0, z1)
            y = self.EYE - (yy - self.VY) * (self.DIST - z) / self.F
            leaves = 1 + 0.06 * np.sin(y * 55.0) * (0.6 + 0.4 * b.noise(0.6, 70 + i))
            block = smooth(0.18, 0.30, y - y0) * smooth(0.18, 0.30, y1 - y) * smooth(0.25, 0.4, z1 - z)
            e = self.light((np.full_like(xx, x1), y, z), (1.0, 0.0, 0.0), ambient=0.9)
            side_rgb = (pages * (leaves * block)[..., None] + c * (1 - block)[..., None]) * e
            # The spine, rounded.
            spine = b.polygon([(px(x0, z1), py(y1, z1)), (px(x1, z1), py(y1, z1)), (px(x1, z1), py(y0, z1)), (px(x0, z1), py(y0, z1))])
            y = self.EYE - (yy - self.VY) / self.k(z1)
            x = (xx - self.VX) / self.k(z1)
            round_ = 0.62 + 0.38 * np.clip(np.sin(np.pi * np.clip((y - y0) / (y1 - y0), 0, 1)), 0, 1) ** 0.6
            e = self.ambient(x) * 1.15 + self.shade_light((x, y, np.full_like(xx, z1)), (0.0, 0.0, 1.0))[..., None] * self.lamp_col
            spine_rgb = c * e * (round_ * (1 + 0.05 * b.noise(0.9, 50 + i)))[..., None]
            if i == 1:
                foil = (np.abs(y - (y0 + y1) / 2) < 0.18) & (x > x0 + 3) & (x < x0 + 9)
                spine_rgb = np.where(foil[..., None], to_linear([200, 180, 130]).astype(np.float32) * e * 1.6, spine_rgb)
            for a, col in ((side, side_rgb), (spine, spine_rgb)):
                rgb = rgb * (1 - a[..., None]) + col * a[..., None]
                alpha = alpha + a * (1 - alpha)
        # The top book's cover, in the lamp's light.
        x0, x1, z0, z1, y0, y1, cloth = self.BOOKS[-1]
        cover = b.polygon([(px(x0, z0), py(y1, z0)), (px(x1, z0), py(y1, z0)), (px(x1, z1), py(y1, z1)), (px(x0, z1), py(y1, z1))])
        z = np.clip(self.DIST - self.F * (self.EYE - y1) / np.maximum(yy - self.VY, 1), z0, z1)
        e = self.light(((xx - self.VX) * (self.DIST - z) / self.F, np.full_like(xx, y1), z), (0.0, 1.0, 0.0), ambient=1.1)
        cover_rgb = to_linear(list(cloth)).astype(np.float32) * e * (1 + 0.05 * b.noise(0.9, 60))[..., None]
        rgb = rgb * (1 - cover[..., None]) + cover_rgb * cover[..., None]
        alpha = alpha + cover * (1 - alpha)
        return Layer(b, alpha, rgb, py(0, self.BOOKS[0][3]))

    def flames(self, canvas):
        """The candles' flames: round below, drawn to a point, a bright core, a dim blue foot and a
        soft orange halo, over a black wick."""
        for cx, cz, h in self.CANDLES:
            k = self.k(cz)
            fx = self.px(cx, cz)
            b = self.box(fx - 40, self.py(h + self.FLAME_UP, cz) - 50, fx + 40, self.py(h + self.FLAME_UP, cz) + 40)
            xx, yy = b.xx, b.yy
            hw, ht = 0.72 * k, 3.9 * k
            base = self.py(h + 0.35, cz)
            t = (base - yy) / ht
            width = np.where(t < 0.32, np.sqrt(np.clip(1 - ((t - 0.32) / 0.32) ** 2, 0, 1)), np.clip(1 - (t - 0.32) / 0.68, 0, 1))
            width = width * (t > -0.02) * (t < 1.0)
            d = np.abs(xx - fx) / np.maximum(width * hw, 1e-3)
            shape = np.clip(1.6 * (1 - d), 0, 1) * (width > 0)
            core = np.clip(1.8 * (1 - d / 0.62), 0, 1) * smooth(0.06, 0.22, t) * (1 - smooth(0.45, 0.8, t)) * (width > 0)
            lit = smooth(0.0, 0.18, t)
            halo = np.exp(-(((xx - fx) / (2.2 * hw)) ** 2 + ((yy - (base - 0.4 * ht)) / (0.9 * ht)) ** 2))
            flame = (shape * lit)[..., None] * np.array([1.15, 0.50, 0.09], np.float32) \
                + core[..., None] * np.array([1.0, 0.74, 0.30], np.float32) \
                + (shape * (1 - lit) * 0.35)[..., None] * np.array([0.30, 0.38, 1.0], np.float32) \
                + (halo * 0.10)[..., None] * np.array([1.0, 0.42, 0.08], np.float32)
            region = canvas[b.y0:b.y1, b.x0:b.x1]
            region[:] = region * (1 - 0.55 * np.clip(shape * 1.2, 0, 1))[..., None] + flame
            wick = np.exp(-((xx - fx) / 0.8) ** 2) * smooth(0, 2, yy - (base - 0.25 * k)) * smooth(0, 1.5, self.py(h, cz) - yy)
            region *= (1 - 0.85 * wick * (1 - np.clip(shape, 0, 1)))[..., None]

    def sideboard(self, canvas, wall_rgb, layers):
        """The sideboard in oiled walnut: its top in the lamp's pool, shaded round what stands on
        it and mirroring it softly; the front, a shadow gap, then reeded doors in the room's light."""
        y_back, y_front = self.py(0, 0), self.py(0, self.DEPTH)
        b = self.box(0, y_back - 1, self.W, y_front + 3)
        xx, yy = b.xx, b.yy
        z = np.clip(self.DIST - self.F * self.EYE / np.maximum(yy - self.VY, 1), 0, self.DEPTH)
        x = (xx - self.VX) * (self.DIST - z) / self.F
        on_top = np.clip((self.TOP_X - np.abs(x)) * self.k(z) + 0.5, 0, 1) * np.clip(yy - y_back + 0.5, 0, 1) * np.clip(y_front - yy + 0.5, 0, 1)
        p, n = (x, np.zeros_like(x), z), (0.0, 1.0, 0.0)
        # The light from the shade's open bottom, in the shadows of the lamp's base and the books.
        near = np.abs(x - self.LAMP_X) < 80
        xs, zs = x[near], z[near]
        heights = np.linspace(0.2, self.BASE_TOP - 0.2, 18)
        down = np.zeros_like(xs)
        for sx, sy, sz, weight in self.down:
            free = np.ones_like(xs)
            for hh, rr in zip(heights, self.base_r(heights)):
                f = (sy - hh) / sy
                free *= smooth(rr - 0.3, rr + 0.3, np.hypot(sx + (xs - sx) * f - self.LAMP_X, sz + (zs - sz) * f - self.LAMP_Z))
            for (x0, x1, z0, z1, y0, y1, _) in self.BOOKS:
                dx, dz = np.where(np.abs(xs - sx) < 1e-6, 1e-6, xs - sx), np.where(np.abs(zs - sz) < 1e-6, 1e-6, zs - sz)
                tx0, tx1, tz0, tz1 = (x0 - sx) / dx, (x1 - sx) / dx, (z0 - sz) / dz, (z1 - sz) / dz
                ty0, ty1 = (sy - y0) / sy, (sy - y1) / sy
                near_t = np.maximum(np.maximum(np.minimum(tx0, tx1), min(ty0, ty1)), np.minimum(tz0, tz1))
                far_t = np.minimum(np.minimum(np.maximum(tx0, tx1), max(ty0, ty1)), np.maximum(tz0, tz1))
                free *= ~((near_t < far_t) & (far_t > 0) & (near_t < 0.999))
            vx, vz = xs - sx, zs - sz
            d2 = vx * vx + sy * sy + vz * vz
            f = (sy - self.SH_BOT) / sy
            gate = 1 - smooth(self.R_BOT - 1.5, self.R_BOT + 1.5, np.hypot(sx + vx * f - self.LAMP_X, sz + vz * f - self.LAMP_Z))
            down += weight * free * gate * (sy / np.sqrt(d2)) / d2
        bulb = self.bulb_light(p, n)
        bulb[near] = self.BULB_I * down
        e = (bulb + self.shade_light(p, n))[..., None] * self.lamp_col + self.candle_light(p, n)[..., None] * self.candle_col + self.ambient(x) * 0.9
        # Shade where things stand on it, and in the corner with the wall.
        contact = np.exp(-(np.maximum(np.hypot(x - self.LAMP_X, z - self.LAMP_Z) - 5.2, 0) / 1.2) ** 2)
        for cx, cz, h in self.CANDLES:
            contact += np.exp(-(np.maximum(np.hypot(x - cx, z - cz) - self.CR, 0) / 1.0) ** 2)
        x0, x1, z0, z1 = self.BOOKS[0][:4]
        contact += np.exp(-(np.hypot(np.maximum(np.maximum(x0 - x, x - x1), 0), np.maximum(np.maximum(z0 - z, z - z1), 0)) / 1.0) ** 2)
        sx0, sx1, _, sz, _ = self.SLEEVE
        contact += np.exp(-(np.hypot(np.maximum(np.maximum(sx0 - x, x - sx1), 0), np.maximum(z - sz, 0)) / 1.2) ** 2)
        contact = np.clip(contact + np.exp(-np.maximum(z, 0) / 1.5) * 0.8, 0, 1)
        walnut = to_linear([118, 82, 56]).astype(np.float32)
        figure = 1 + 0.22 * streaks(b.h, b.w, 260, 2, 9) + 0.10 * streaks(b.h, b.w, 60, 1, 10)
        top = walnut * (figure * (1 - 0.6 * contact))[..., None] * e
        # Its satin finish mirrors the wall and what stands on it, each about its foot, softly.
        rows = b.yy[:, 0]
        mirror = wall_rgb[np.clip((2 * y_back - rows).astype(int), 0, self.H - 1)].copy()
        for layer in layers:
            lb = layer.box
            src = np.round(2 * layer.foot - rows).astype(int) - lb.y0
            for ri in np.nonzero((rows > layer.foot) & (src >= 0) & (src < lb.h))[0]:
                seg = mirror[ri, lb.x0:lb.x1]
                seg[:] = seg * (1 - layer.alpha[src[ri]][:, None]) + layer.rgb[src[ri]]
        gloss = 0.10 + 0.08 * (1 - np.clip((yy - y_back) / (y_front - y_back), 0, 1))
        top = top + blur(mirror, 3.0) * (gloss * (1 - 0.5 * contact))[..., None]
        region = canvas[b.y0:b.y1, b.x0:b.x1]
        region[:] = region * (1 - on_top[..., None]) + top * on_top[..., None]
        # The front.
        b = self.box(0, y_front - 1, self.W, self.H)
        xx, yy = b.xx, b.yy
        y, x = self.EYE - (yy - self.VY) / self.k(self.DEPTH), (xx - self.VX) / self.k(self.DEPTH)
        on_front = np.clip((self.TOP_X - np.abs(x)) * self.k(self.DEPTH) + 0.5, 0, 1) * np.clip(yy - y_front + 0.5, 0, 1)
        room = self.ambient(x) * (2.2 * (1 - 0.55 * smooth(0, 18, -y)))[..., None]
        across = ((x + self.TOP_X) / 3.2) % 1.0 * 2 - 1
        reed = 0.22 + 0.78 * np.sqrt(np.clip(1 - across * across, 0, 1)) + 0.10 * np.clip(-across, 0, 1)
        front = walnut * ((1 + 0.10 * streaks(b.h, b.w, 120, 2, 12, vertical=True)) * reed)[..., None] * room
        front = np.where((y > -2.5)[..., None], walnut * (1.1 + 0.24 * streaks(b.h, b.w, 260, 2, 9))[..., None] * room, front)
        front = np.where(((y <= -2.5) & (y > -3.3))[..., None], walnut * room * 0.11, front)
        # The rounded front edge catches some light from the ceiling.
        edge = np.exp(-((yy - (y_front + 0.8)) / 1.1) ** 2) * (0.5 + 0.5 * np.exp(-((x - self.LAMP_X) / 60.0) ** 2) + 0.4 * np.exp(-((x - 86) / 40.0) ** 2))
        front = front + (edge * 0.020)[..., None] * self.lamp_col
        for gap in (-self.TOP_X / 3, self.TOP_X / 3):
            front = front * (1 - 0.7 * np.exp(-((x - gap) / 0.12) ** 2) * (y < -3.3))[..., None]
        region = canvas[b.y0:b.y1, b.x0:b.x1]
        region[:] = region * (1 - on_front[..., None]) + front * on_front[..., None]

    def photograph(self, tv, cover, exposure=1.4, white=3800):
        """The room as a photograph (sRGB floats) and the light on the wall around the TV (linear)."""
        full = self.box(0, 0, self.W, self.H)
        x, y = (full.xx - self.VX) / self.S, self.EYE - (full.yy - self.VY) / self.S
        light = self.light((x, y, np.zeros_like(x)), (0.0, 0.0, 1.0))
        # Beside the sideboard, below its top, none of the lamp's or the candles' light reaches the wall.
        below = smooth(0.5, -3.0, y)
        light = light * (1 - below[..., None]) + self.ambient(x) * (below * (1 - 0.5 * smooth(0, -40, y)))[..., None]
        # Shade from what stands close to the wall: the TV (3 cm off it), the sleeve, the sideboard's top.
        tv_mask = rounded_box(self.H, self.W, tv.outer, 5.0).astype(np.float32)
        sleeve_mask = full.polygon(self.sleeve_corners())
        shade = (1 - 0.55 * blur(tv_mask, 14) * (1 - tv_mask)) * (1 - 0.5 * blur(sleeve_mask, 7) * (1 - sleeve_mask))
        shade *= 1 - 0.5 * smooth(self.TOP_X + 4, self.TOP_X - 4, np.abs(x)) * np.exp(-np.clip(y, 0, None) / 2.2)
        paint = to_linear([176, 166, 154]).astype(np.float32)
        plaster = 1 + full.noise(90, 5) * 0.012 + full.noise(18, 6) * 0.006 + full.noise(1.2, 7) * 0.006
        canvas = paint * (plaster * shade)[..., None] * light
        wall_rgb = canvas.copy()
        layers = [self.sleeve(cover), self.candle(0), self.lamp(), self.candle(1), self.candle(2), self.books()]
        self.sideboard(canvas, wall_rgb, layers)
        for layer in layers:
            layer.over(canvas)
        self.flames(canvas)
        bright = np.clip(canvas - 1.2, 0, None)
        canvas = canvas + blur(bright, 6) * 0.25 + blur(bright, 24) * 0.12 + blur(bright, 80) * 0.05
        # The camera: balanced for warm light (the lamp still reads warm), a filmic curve, a little less color.
        wb = kelvin(white)
        wb = (wb / (0.2126 * wb[0] + 0.7152 * wb[1] + 0.0722 * wb[2])).astype(np.float32)
        img = tonemap(canvas * exposure / wb)
        gray = (0.2126 * img[..., 0] + 0.7152 * img[..., 1] + 0.0722 * img[..., 2])[..., None]
        return to_srgb(gray + (img - gray) * 0.88), (light * paint).astype(np.float64) * exposure / wb


def views():
    """The three views, each on the TV in the same room on a winter night: over a walnut
    sideboard, lit by a table lamp and three candles. The record sleeve wears the cover of the day
    and night picture's song."""
    room = Room()
    tv = Tv(Room.W / 2, Room.VY, 1260, 9)
    # The day and night song's cover, cut from its render inside its rounded corners (the artwork: 368 dp at 88 dp, 2 px per dp).
    cover = Image.open(os.path.join(RENDERS, "night.png")).convert("RGB").crop((192, 188, 896, 892))
    photo, light = room.photograph(tv, cover)
    for screen, name in (("single-cover", "mockup-single-cover"), ("cover-flow", "mockup-cover-flow"), ("landscape", "mockup-landscape")):
        img = photo.copy()
        tv_frame(img, tv, os.path.join(RENDERS, screen + ".png"), light * 0.6)
        save(img, name + ".jpg")


def daylight_room(W, H, tv, seed=21):
    """A light wall by day: daylight from a window to the left, a soft band of sun across the wall
    with the shade of the window's bars in it, and the TV's own soft shadow. Returns the image
    (sRGB floats) and the light on the TV's rim (linear). Its softness is measured for a picture
    1200 px wide and grows with a wider one."""
    k = W / 1200
    y, x = np.mgrid[0:H, 0:W] + 0.5
    # Daylight: brighter toward the window, a little brighter toward the ceiling.
    sky = 0.70 + 0.26 * np.clip(1 - x / W, 0, 1) ** 1.4 + 0.08 * np.clip(1 - y / H, 0, 1)
    # The sun falls in a slanted band from the upper left, the window's bars across it.
    a = (x * 0.80 + y * 0.60) / W
    band = blur(((a > 0.18) & (a < 0.62)).astype(np.float64), 34 * k)
    bars = blur(((np.abs(a - 0.40) < 0.010) | (np.abs((y * 0.80 - x * 0.60) / W - 0.05) < 0.010)).astype(np.float64), 10 * k)
    sun = 0.55 * band * (1 - 0.65 * bars) * np.clip(1.35 - x / W, 0, 1)
    light = sky[..., None] * np.array([1.0, 0.985, 0.960]) + sun[..., None] * np.array([1.0, 0.93, 0.80])
    # The TV keeps some light off the wall below and right of it.
    shadow = blur(rounded_box(H, W, np.add(tv.outer, np.multiply((20, 26, 20, 26), k)), 8.0 * k), 22 * k)
    light = light * (1 - 0.30 * shadow)[..., None]
    mottle = noise(H, W, 90 * k, seed) * 0.008 + noise(H, W, 22 * k, seed + 1) * 0.003
    paint = to_linear([240, 234, 224])
    lin = paint * (1 + mottle)[..., None] * light
    rim = (0.25 + 0.75 * np.clip(1.2 - x / W - 0.5 * y / H, 0, 1))[..., None] * np.ones(3) * 0.8
    return to_srgb(tonemap(lin * 1.25)), rim


def day_and_night():
    """One song twice: on the light wall by day, on the dark wall at night, side by side."""
    W, H = 1200, 1000
    tv = Tv(W / 2, H / 2 + 8, 1010, 5)
    day, rim = daylight_room(W, H, tv)
    tv_frame(day, tv, os.path.join(RENDERS, "day.png"), rim, sheen=0.03, window=0.10)
    night, light = dark_room(W, H, tv, light_colors("night"), 0.5)
    tv_frame(night, tv, os.path.join(RENDERS, "night.png"), light * 0.16)
    save(np.concatenate([day, night], axis=1), "day-and-night.jpg")


def ink_width(mask):
    """How wide the ink of a text mask is, from its first to its last inked column."""
    cols = np.nonzero(mask.max(axis=0) > 0.02)[0]
    return int(cols[-1] + 1) if len(cols) else mask.shape[1]


def download_button(scale=4, light=False):
    """The README's download button, built like a store badge: the logo, then "Download for" over
    "Android TV", the same space on every side, flat night blue with a hairline edge. The [light]
    one, for light pages, is pale with night blue words, and the logo sits on its own night blue
    tile as on the TV's home screen. Its width follows what it holds; the README shows it 236 px
    wide."""
    h = 64 * scale
    side = int(round(h * 0.60))                  # the logo
    pad = (h - side) // 2                        # the same space left of it as above and below
    gap = int(round(h * 0.17))
    small = text_mask("Download for", "dmsans.ttf", 10.5 * scale, 450)
    large = text_mask("Android TV", "jost.ttf", 21.5 * scale, 430, tracking=-0.005)
    words = max(ink_width(small), ink_width(large))
    w = pad + side + gap + words + pad
    r = int(round(h * 0.22))
    ss = 4
    shape = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(shape).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], radius=r * ss, fill=255)
    edge = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(edge).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], radius=r * ss, outline=255, width=scale * ss)
    shape = np.asarray(shape.resize((w, h), Image.LANCZOS), dtype=np.float64) / 255.0
    edge = np.asarray(edge.resize((w, h), Image.LANCZOS), dtype=np.float64) / 255.0
    img = np.empty((h, w, 3))
    if light:
        img[:] = np.array([238, 243, 250], dtype=np.float64)
        img = img * (1 - edge[..., None] * 0.16) + np.array(NAVY) * edge[..., None] * 0.16
        mark, a = logo_tile(side)
    else:
        img[:] = np.array(NAVY, dtype=np.float64)
        img = img * (1 - edge[..., None] * 0.22) + np.array([150, 170, 205]) * edge[..., None] * 0.22
        mark = np.asarray(logo(side, background=None), dtype=np.float64)
        a = mark[..., 3:4] / 255.0
    img[pad:pad + side, pad:pad + side] = img[pad:pad + side, pad:pad + side] * (1 - a) + mark[..., :3] * a
    # The two lines as one block centered on the button: the small line's cap height to the large line's baseline.
    cap_small, cap_large = 0.70 * 10.5 * scale, 0.70 * 21.5 * scale
    line_gap = 6.5 * scale
    y0 = h / 2 - (cap_small + line_gap + cap_large) / 2
    tx = pad + side + gap
    paste(img, small, tx, int(round(y0)), (84, 98, 128) if light else (186, 202, 226))
    paste(img, large, tx, int(round(y0 + cap_small + line_gap)), (12, 24, 52) if light else (242, 246, 252))
    rgba = np.dstack([np.clip(img, 0, 255), shape * 255]).astype(np.uint8)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "download-light.png" if light else "download.png")
    Image.fromarray(rgba, "RGBA").save(path, optimize=True)
    print(path, os.path.getsize(path) // 1024, "KB", f"{w}x{h}")


if __name__ == "__main__":
    now_playing()
    now_playing_light()
    views()
    day_and_night()
    download_button()
    download_button(light=True)
