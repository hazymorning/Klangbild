#!/usr/bin/env python3
"""Compares two renders of the picture tests, before and after a change, pixel by pixel.

    python3 ci/compare-pictures.py BEFORE AFTER [OUT]

BEFORE and AFTER are folders as the tests write them (app/build/screenshots). The summary, in
Markdown, says how many pictures are the same and lists the ones that changed (how many pixels,
by how much), are new or are gone. With OUT, every changed picture is written there three times
side by side: before, after, and after dimmed with every changed pixel in red. Exit code 0 when
every picture is the same, 1 when something differs, 2 when they could not be compared. Needs
Pillow.
"""
import os
import sys

from PIL import Image, ImageChops

# Each panel of a side-by-side picture is at most this wide (a 4K render shrinks to it).
PANEL_WIDTH = 960


def pictures(root):
    out = {}
    for folder, _, files in os.walk(root):
        for name in files:
            if name.endswith(".png"):
                path = os.path.join(folder, name)
                out[os.path.relpath(path, root)] = path
    return out


def difference(before, after):
    """None when the pixels are the same, else (pixels that differ, largest step of a channel, the difference)."""
    if before.size != after.size:
        return (before.width * before.height, 255, None)
    if before.tobytes() == after.tobytes():
        return None
    diff = ImageChops.difference(before, after)
    steps = max(high for _, high in diff.getextrema())
    mask = diff.convert("L").point(lambda v: 255 if v else 0)
    return (mask.histogram()[255], steps, diff)


def side_by_side(before, after, diff, path):
    scale = min(1.0, PANEL_WIDTH / max(before.width, after.width))

    def shrink(image, resample=Image.Resampling.LANCZOS):
        return image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))), resample)

    panels = [shrink(before.convert("RGB")), shrink(after.convert("RGB"))]
    if diff is not None:
        # The after picture dimmed, every pixel that changed in red (still red once shrunk).
        changed = shrink(diff.convert("L").point(lambda v: 255 if v else 0), Image.Resampling.BOX).point(lambda v: 255 if v else 0)
        marks = panels[1].point(lambda v: v // 4)
        marks.paste((255, 0, 0), mask=changed)
        panels.append(marks)
    sheet = Image.new("RGB", (sum(p.width for p in panels) + 8 * (len(panels) - 1), max(p.height for p in panels)), (255, 0, 255))
    x = 0
    for p in panels:
        sheet.paste(p, (x, 0))
        x += p.width + 8
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sheet.save(path)


def main(before_root, after_root, out_root=None):
    before = pictures(before_root)
    after = pictures(after_root)
    same = 0
    changed = []
    for name in sorted(before.keys() & after.keys()):
        with Image.open(before[name]) as a, Image.open(after[name]) as b:
            a = a.convert("RGBA")
            b = b.convert("RGBA")
            d = difference(a, b)
            if d is None:
                same += 1
                continue
            changed.append((name, d[0], d[1], a.width * a.height))
            if out_root:
                side_by_side(a, b, d[2], os.path.join(out_root, name))
    new = sorted(after.keys() - before.keys())
    gone = sorted(before.keys() - after.keys())

    if not changed and not new and not gone:
        print(f"All {same} pictures look exactly as before.")
        return 0
    print(f"{same} pictures look exactly as before; {len(changed)} changed, {len(new)} are new, {len(gone)} are gone.")
    if changed:
        print("\n| Changed | Pixels | Largest step |\n|---|---:|---:|")
        for name, pixels, steps, total in changed:
            print(f"| `{name}` | {pixels} ({100.0 * pixels / total:.2f} %) | {steps} |")
    for title, names in (("New", new), ("Gone", gone)):
        if names:
            print(f"\n{title}:\n")
            for name in names:
                print(f"- `{name}`")
    return 1


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        print(__doc__.strip())
        sys.exit(2)
    try:
        sys.exit(main(*sys.argv[1:]))
    except Exception as e:  # Anything but a finished comparison is an error, never a difference.
        print(f"The pictures could not be compared: {e}", file=sys.stderr)
        sys.exit(2)
