#!/usr/bin/env python3
"""Generates the pixel-art TV banner and launcher icons with PIL.

Run from the repository root:  python3 scripts/generate_assets.py
"""
from PIL import Image, ImageDraw
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TV_RES = os.path.join(ROOT, "aquarium-tv", "src", "main", "res")
SCANNER_RES = os.path.join(ROOT, "fish-scanner", "src", "main", "res")

DEEP = (18, 58, 72, 255)
DEEP2 = (10, 35, 46, 255)
TEAL = (62, 156, 154, 255)
TEAL2 = (27, 88, 102, 255)
ACCENT = (255, 209, 102, 255)
CREAM = (255, 244, 214, 255)
CORAL = (232, 104, 93, 255)
ORANGE = (242, 166, 90, 255)
GREEN = (78, 140, 90, 255)
GREEN2 = (63, 127, 85, 255)
SAND = (227, 192, 122, 255)
EYE = (250, 250, 250, 255)
PUPIL = (20, 30, 34, 255)

FONT = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "10001", "11110", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    " ": ["00000"] * 7,
}


def draw_text(draw, text, x, y, scale, color):
    cursor = x
    for character in text:
        glyph = FONT.get(character, FONT[" "])
        for row, bits in enumerate(glyph):
            for col, bit in enumerate(bits):
                if bit == "1":
                    draw.rectangle(
                        [cursor + col * scale, y + row * scale,
                         cursor + (col + 1) * scale - 1, y + (row + 1) * scale - 1],
                        fill=color,
                    )
        cursor += 6 * scale


def gradient(size, top, bottom):
    image = Image.new("RGBA", size)
    draw = ImageDraw.Draw(image)
    width, height = size
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4))
        draw.line([(0, y), (width, y)], fill=color)
    return image


def draw_pixel_fish(draw, cx, cy, size, facing_right=True):
    """Chunky fish drawn in rectangles; size is the body height."""
    body_h = size
    body_w = int(size * 1.75)
    top = cy - body_h // 2
    bottom = cy + body_h // 2
    left = cx - body_w // 2
    right = cx + body_w // 2

    # Tail
    tail_len = int(size * 0.75)
    if facing_right:
        draw.polygon(
            [(left, cy), (left - tail_len, top - size // 5), (left - tail_len, bottom + size // 5)],
            fill=CORAL,
        )
    else:
        draw.polygon(
            [(right, cy), (right + tail_len, top - size // 5), (right + tail_len, bottom + size // 5)],
            fill=CORAL,
        )

    # Body with slightly clipped corners for a pixel silhouette.
    draw.rectangle([left + size // 6, top + size // 6, right - size // 6, bottom - size // 6], fill=ORANGE)
    draw.rectangle([left, top + size // 3, right, bottom - size // 3], fill=ORANGE)
    draw.rectangle([left + size // 3, top, right - size // 3, bottom], fill=ORANGE)
    # Fin
    draw.rectangle([cx - size // 4, top - size // 5, cx + size // 5, top + size // 8], fill=ACCENT)
    draw.rectangle([cx - size // 6, bottom - size // 8, cx + size // 3, bottom + size // 5], fill=CORAL)
    # Stripes
    for i in range(2):
        sx = cx - size // 2 + i * size // 3
        draw.rectangle([sx, top + size // 3, sx + max(2, size // 8), bottom - size // 3], fill=CORAL)
    # Eye
    eye_x = right - size // 3 if facing_right else left + size // 3
    eye_y = cy - size // 6
    r = max(2, size // 7)
    draw.ellipse([eye_x - r, eye_y - r, eye_x + r, eye_y + r], fill=EYE)
    draw.ellipse([eye_x - max(1, r // 2), eye_y - max(1, r // 2),
                  eye_x + max(1, r // 2), eye_y + max(1, r // 2)], fill=PUPIL)


def draw_banner():
    image = gradient((320, 180), (62, 156, 154, 255), (12, 60, 76, 255))
    draw = ImageDraw.Draw(image)

    # Light rays
    for x0, x1 in [(30, 80), (140, 210), (250, 310)]:
        draw.polygon([(x0, 0), (x0 + 18, 0), (x1 + 34, 180), (x1, 180)], fill=(255, 244, 214, 20))

    # Sand
    draw.rectangle([0, 150, 320, 180], fill=SAND)
    draw.rectangle([0, 150, 320, 154], fill=(242, 215, 154, 255))
    for i in range(70):
        x = (i * 47) % 320
        y = 156 + (i * 29) % 22
        draw.rectangle([x, y, x + 2, y + 1], fill=(201, 162, 95, 255))

    # Kelp
    for base, height in [(20, 70), (286, 58), (300, 84)]:
        for i in range(height):
            x = base + int(6 * ((i / 14) ** 0.7) * (1 if (i // 18) % 2 else -1))
            y = 152 - i
            draw.rectangle([x, y, x + 3, y + 1], fill=GREEN)
            if i % 14 == 0 and i > 0:
                draw.rectangle([x - 5, y - 2, x + 8, y + 1], fill=GREEN2)

    # Coral
    draw.rectangle([247, 120, 250, 151], fill=CORAL)
    draw.rectangle([238, 128, 241, 151], fill=CORAL)
    draw.rectangle([256, 133, 259, 151], fill=ORANGE)
    draw.rectangle([231, 138, 234, 151], fill=ORANGE)
    draw.rectangle([245, 120, 259, 123], fill=CORAL)

    # Fish and bubbles
    draw_pixel_fish(draw, 138, 108, 42, facing_right=True)
    for bx, by, br in [(90, 55, 3), (104, 38, 2), (196, 68, 2), (210, 48, 3), (178, 30, 2)]:
        draw.ellipse([bx - br, by - br, bx + br, by + br], outline=(223, 246, 232, 180), width=1)

    # Title
    draw_text(draw, "AQUABRICKS", 52, 12, 4, CREAM)
    draw.rectangle([52, 42, 268, 44], fill=ACCENT)

    os.makedirs(os.path.join(TV_RES, "drawable-nodpi"), exist_ok=True)
    image.convert("RGB").save(os.path.join(TV_RES, "drawable-nodpi", "tv_banner.png"))


def make_icon(scanner=False):
    image = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle([0, 0, 95, 95], radius=22, fill=DEEP)
    draw.rounded_rectangle([2, 2, 93, 93], radius=20, outline=TEAL, width=2)

    if scanner:
        # Viewfinder corners
        def rect(x1, y1, x2, y2):
            draw.rectangle([min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)], fill=ACCENT)

        for (x, y, dx, dy) in [(10, 10, 1, 1), (85, 10, -1, 1), (85, 85, -1, -1), (10, 85, 1, -1)]:
            rect(x, y, x + dx * 16, y + dy * 3)
            rect(x, y, x + dx * 3, y + dy * 16)

    # Kelp behind the fish
    for base, height in [(20, 34), (72, 28)]:
        for i in range(height):
            x = base + int(4 * ((i / 12) ** 0.7) * (1 if (i // 12) % 2 else -1))
            draw.rectangle([x, 82 - i, x + 3, 83 - i], fill=GREEN2)

    draw_pixel_fish(draw, 47, 52, 28, facing_right=True)
    for bx, by, br in [(30, 30, 2), (66, 26, 2), (74, 36, 1)]:
        draw.ellipse([bx - br, by - br, bx + br, by + br], outline=(223, 246, 232, 200), width=1)
    return image


def save_icons(master, res_dir):
    for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)]:
        directory = os.path.join(res_dir, f"mipmap-{density}")
        os.makedirs(directory, exist_ok=True)
        master.resize((size, size), Image.NEAREST).save(os.path.join(directory, "ic_launcher.png"))


def main():
    draw_banner()
    save_icons(make_icon(scanner=False), TV_RES)
    save_icons(make_icon(scanner=True), SCANNER_RES)
    print("Generated tv_banner.png and launcher icons.")


if __name__ == "__main__":
    main()
