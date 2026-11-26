#!/usr/bin/env python3
"""Generates scripts/demo-fish.png: a clearly fish-shaped brick-coloured sprite
used by the phone E2E script and for demos. It is not used by the scanner tests."""
from PIL import Image
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CELL = 24

RED = (201, 26, 9, 255)       # brick Red
ORANGE = (254, 138, 24, 255)  # brick Orange
YELLOW = (242, 205, 55, 255)  # brick Yellow
WHITE = (244, 244, 244, 255)
BLACK = (27, 42, 52, 255)

# 20 x 12 studs, head to the right.
PATTERN = [
    "....................",
    "........fffff.......",
    ".......fffffff......",
    ".....rrrrrrrrrrr....",
    "...rrrrrrrrrrrrrrr..",
    "ooorrrrrrrrrrrwwr...",
    "ooorrrrrrrrrrrwkwr..",
    ".oorrrrrrrrrrrrrrr..",
    "...rrrrrrrrrrrrrrr..",
    ".....rrrrrrrrrrr....",
    "........fffff.......",
    ".........fffff......",
]
COLORS = {"r": RED, "o": ORANGE, "f": YELLOW, "w": WHITE, "k": BLACK}


def main():
    width = len(PATTERN[0]) * CELL
    height = len(PATTERN) * CELL
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    pixels = image.load()
    for row, line in enumerate(PATTERN):
        for col, symbol in enumerate(line):
            color = COLORS.get(symbol)
            if color is None:
                continue
            for y in range(row * CELL, (row + 1) * CELL):
                for x in range(col * CELL, (col + 1) * CELL):
                    pixels[x, y] = color
    path = os.path.join(ROOT, "scripts", "demo-fish.png")
    image.save(path)
    print("Wrote", path, image.size)


if __name__ == "__main__":
    main()
