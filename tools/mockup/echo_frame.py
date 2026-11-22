#!/usr/bin/env python3
"""
Put a 960x480 board screenshot into a front-facing illustration of an Echo Show 5 (2nd gen).

The frame is drawn from scratch (no product photo), to the device's proportions: a 5.5-inch 2:1
screen in a charcoal glass face with rounded corners, the camera at the top right and the slotted
speaker lip along the base.

  python3 tools/mockup/echo_frame.py board.png docs/screenshots/echo-show-5.png
"""
import sys
from PIL import Image, ImageDraw, ImageFilter

S = 2                                  # output pixels per screen pixel
SCREEN = (960 * S, 480 * S)
SIDE, TOP, BOTTOM = 92 * S, 88 * S, 96 * S
RADIUS = 64 * S
FACE = (SCREEN[0] + 2 * SIDE, SCREEN[1] + TOP + BOTTOM)
LIP = 26 * S                           # visible speaker lip below the face
PAD = 60 * S                           # room for the shadow


def rounded(size, radius, fill):
    im = Image.new("L", size, 0)
    ImageDraw.Draw(im).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=fill)
    return im


def main(src, dst):
    shot = Image.open(src).convert("RGB")
    if shot.size != (960, 480):
        shot = shot.resize((960, 480), Image.LANCZOS)
    shot = shot.resize(SCREEN, Image.NEAREST)     # bitmap font: keep pixels crisp

    W, H = FACE[0] + 2 * PAD, FACE[1] + LIP + 2 * PAD
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    fx, fy = PAD, PAD

    # Soft shadow under the whole device.
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle((fx + 20 * S, fy + 40 * S, fx + FACE[0] - 20 * S, fy + FACE[1] + LIP + 10 * S),
                                         RADIUS, fill=110)
    sh = sh.filter(ImageFilter.GaussianBlur(26 * S))
    out.paste(Image.new("RGBA", (W, H), (0, 0, 0, 255)), (0, 0), sh)

    # Speaker lip (fabric base seen below the face) with its slots.
    lip_w = FACE[0] - 2 * RADIUS // 2
    lx = fx + (FACE[0] - lip_w) // 2
    lip = Image.new("RGBA", (lip_w, LIP + RADIUS), (38, 39, 42, 255))
    out.paste(lip, (lx, fy + FACE[1] - RADIUS), rounded(lip.size, RADIUS // 2, 255))
    d = ImageDraw.Draw(out)
    slot_w, gap = 5 * S, 9 * S
    n = (lip_w - 2 * RADIUS) // (slot_w + gap)
    sx = lx + (lip_w - n * (slot_w + gap) + gap) // 2
    for i in range(n):
        x = sx + i * (slot_w + gap)
        d.rounded_rectangle((x, fy + FACE[1] + 7 * S, x + slot_w, fy + FACE[1] + LIP - 7 * S), slot_w // 2, fill=(14, 14, 16, 255))

    # Outer rim, then the glass face.
    out.paste(Image.new("RGBA", FACE, (58, 60, 64, 255)), (fx, fy), rounded(FACE, RADIUS, 255))
    inset = 4 * S
    glass = (FACE[0] - 2 * inset, FACE[1] - 2 * inset)
    g = Image.new("RGBA", glass, (16, 16, 18, 255))
    gd = ImageDraw.Draw(g)
    for y in range(glass[1]):                     # faint top-to-bottom sheen
        a = int(14 * (1 - y / glass[1]))
        gd.line((0, y, glass[0], y), fill=(16 + a, 16 + a, 19 + a, 255))
    out.paste(g, (fx + inset, fy + inset), rounded(glass, RADIUS - inset, 255))

    # Screen with a hairline black border.
    sx0, sy0 = fx + SIDE, fy + TOP
    d.rectangle((sx0 - 2 * S, sy0 - 2 * S, sx0 + SCREEN[0] + 2 * S - 1, sy0 + SCREEN[1] + 2 * S - 1), fill=(0, 0, 0, 255))
    out.paste(shot, (sx0, sy0))

    # Camera (top right) with its privacy shutter track.
    cx, cy, r = fx + FACE[0] - SIDE - 18 * S, fy + TOP // 2, 7 * S
    d.rounded_rectangle((cx - 3 * r, cy - r - 2 * S, cx + r + 2 * S, cy + r + 2 * S), r + 2 * S, fill=(24, 24, 27, 255))
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(6, 6, 8, 255), outline=(44, 46, 52, 255), width=S)
    d.ellipse((cx - r // 3, cy - r // 3, cx + r // 3, cy + r // 3), fill=(30, 36, 52, 255))

    # Very light diagonal glare across the glass.
    glare = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glare).polygon([(fx + FACE[0] * 0.55, fy), (fx + FACE[0] * 0.75, fy), (fx + FACE[0] * 0.45, fy + FACE[1]),
                                   (fx + FACE[0] * 0.25, fy + FACE[1])], fill=5)
    mask = Image.new("L", (W, H), 0)
    mask.paste(rounded(FACE, RADIUS, 255), (fx, fy))
    glare = Image.composite(glare, Image.new("L", (W, H), 0), mask)
    out = Image.composite(Image.new("RGBA", (W, H), (255, 255, 255, 255)), out, glare)
    out.save(dst, optimize=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
