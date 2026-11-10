#!/usr/bin/env python3
"""Creates a realistic synthetic photo of a flat LEGO fish on a baseplate.

Used by the JVM tests in the scanner module, so the image pipeline is exercised
against textured plastic, stud highlights, shadows, noise and a tilted camera.
"""
from PIL import Image, ImageDraw
import os
import random

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "fish-scanner", "src", "test", "resources", "samples")
random.seed(17)

PLATE = (214, 210, 200)
PLATE_DARK = (186, 181, 170)
PLATE_LIGHT = (228, 224, 214)
BODY = (224, 92, 54)
BODY_DARK = (176, 62, 40)
FIN = (242, 166, 90)
FIN_DARK = (196, 124, 62)
CREAM = (250, 246, 225)
PUPIL = (28, 30, 32)

PATTERN = [
    "..............",
    "..............",
    "....rrrrrr....",
    "..rrrrrrrrrr..",
    "yyrrrrrrrrrrr.",
    "yyrrrrwrrrrrr.",
    "yyrrrrrrrrrrr.",
    "..rrrrrrrrrr..",
    "....rrrrrr....",
]


def draw_stud(draw, x, y, radius, color, highlight, shadow):
    draw.ellipse([x - radius + 1, y - radius + 3, x + radius + 1, y + radius + 3], fill=shadow)
    draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)
    draw.ellipse([x - radius + 3, y - radius + 2, x + radius - 3, y + radius - 4], fill=highlight)


def main():
    width, height = 1100, 800
    image = Image.new("RGB", (width, height), PLATE)
    draw = ImageDraw.Draw(image)

    # Baseplate studs, slightly irregular so the cutout cannot rely on flat colour alone.
    for row, y in enumerate(range(30, height, 46)):
        offset = 23 if row % 2 else 0
        for x in range(30 + offset, width, 46):
            jitter = random.randint(-1, 1)
            draw_stud(
                draw,
                x + jitter,
                y + random.randint(-1, 1),
                15,
                PLATE,
                PLATE_LIGHT,
                PLATE_DARK,
            )

    cell = 56
    gap = 4
    cols = len(PATTERN[0])
    rows = len(PATTERN)
    origin_x = (width - cols * cell) // 2
    origin_y = (height - rows * cell) // 2

    for row, line in enumerate(PATTERN):
        for col, symbol in enumerate(line):
            if symbol == ".":
                continue
            x = origin_x + col * cell
            y = origin_y + row * cell
            if symbol == "y":
                color, dark = FIN, FIN_DARK
            elif symbol == "w":
                color, dark = CREAM, (188, 180, 154)
            else:
                color, dark = BODY, BODY_DARK

            # Plastic shadow and brick gap.
            draw.rectangle([x + gap + 3, y + gap + 5, x + cell - gap + 3, y + cell - gap + 5],
                           fill=(168, 160, 146))
            draw.rectangle([x + gap, y + gap, x + cell - gap, y + cell - gap],
                           outline=dark, fill=color, width=2)
            draw_stud(
                draw,
                x + cell // 2,
                y + cell // 2,
                14,
                color,
                tuple(min(255, c + 28) for c in color),
                tuple(max(0, c - 28) for c in color),
            )
            if symbol == "w":
                draw.ellipse([x + cell // 2 - 7, y + cell // 2 - 7,
                              x + cell // 2 + 7, y + cell // 2 + 7], fill=PUPIL)

    # Soft photographic lighting: a pale wash from the top left and fine sensor noise.
    overlay = Image.new("RGBA", (width, height), (255, 255, 255, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    for y in range(height):
        alpha = int(24 * max(0.0, 1.0 - y / height))
        overlay_draw.line([(0, y), (width, y)], fill=(255, 250, 235, alpha))
    image = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")

    pixels = image.load()
    for _ in range(45000):
        x = random.randrange(width)
        y = random.randrange(height)
        r, g, b = pixels[x, y]
        n = random.randint(-6, 6)
        pixels[x, y] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)))

    # Simulate a phone held slightly off-axis.
    image = image.rotate(-2.4, resample=Image.BICUBIC, expand=True, fillcolor=PLATE)
    image = image.resize((960, 700), Image.LANCZOS)

    os.makedirs(OUT, exist_ok=True)
    image.save(os.path.join(OUT, "lego-fish-photo.png"))

    # Raw little-endian ARGB for the JVM tests (Android unit tests cannot use
    # java.awt/javax.imageio on the Kotlin test classpath).
    import array
    import struct
    import sys
    pixels = array.array("I", ((255 << 24) | (r << 16) | (g << 8) | b
                               for r, g, b in image.getdata()))
    if sys.byteorder != "little":
        pixels.byteswap()
    with open(os.path.join(OUT, "lego-fish-photo.bin"), "wb") as raw:
        raw.write(struct.pack("<II", image.width, image.height))
        pixels.tofile(raw)
    print("Wrote lego-fish-photo.png and lego-fish-photo.bin")


if __name__ == "__main__":
    main()
