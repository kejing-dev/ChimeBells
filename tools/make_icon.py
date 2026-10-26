"""Chime Bells icon: the 512 export for the factory gallery and the legacy launcher PNG, drawn from the
same shapes as the adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py <export-512.png>

Bronze-gold tile #A67C3D, black-lacquer yongzhong silhouette #221C18 (shank, xuan ring, almond body with an
arched mouth, 2 x 2 x 3 bosses left in the tile colour) and a thin cinnabar #B5382A beam line below (design
icon). Coordinates are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1080
K = S / 108.0
TILE = (0xA6, 0x7C, 0x3D, 255)
INK = (0x22, 0x1C, 0x18, 255)
CINNABAR = (0xB5, 0x38, 0x2A, 255)


def quad(p0, p1, p2, n=24):
    """Points of a quadratic Bezier curve."""
    out = []
    for i in range(n + 1):
        t = i / n
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        out.append((x, y))
    return out


def poly(d, pts, fill):
    d.polygon([(x * K, y * K) for x, y in pts], fill=fill)


def draw(size, rounded):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if rounded:
        d.rounded_rectangle((0, 0, S, S), radius=int(S * 0.22), fill=TILE)
    else:
        d.rectangle((0, 0, S, S), fill=TILE)
    poly(d, [(48, 20), (60, 20), (60, 24), (48, 24)], INK)
    poly(d, [(50.5, 24), (57.5, 24), (58.5, 40), (49.5, 40)], INK)
    poly(d, [(47, 31), (61, 31), (61, 35), (47, 35)], INK)
    body = [(40, 40), (68, 40)] + quad((68, 40), (72, 60), (75, 80))[1:] + quad((75, 80), (54, 71), (33, 80))[1:] + quad((33, 80), (36, 60), (40, 40))[1:]
    poly(d, body, INK)
    r = 1.8 * K
    for y, xs in ((47, (44, 49, 59, 64)), (54, (43.6, 48.8, 59.2, 64.4)), (61, (43.2, 48.6, 59.4, 64.8))):
        for x in xs:
            d.ellipse((x * K - r, y * K - r, x * K + r, y * K + r), fill=TILE)
    poly(d, [(30, 85), (78, 85), (78, 88), (30, 88)], CINNABAR)
    return im.resize((size, size), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "icon-512.png")
    draw(512, rounded=True).save(out)
    draw(192, rounded=False).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    print("wrote", out)


if __name__ == "__main__":
    main()
