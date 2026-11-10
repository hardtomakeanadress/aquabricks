#!/usr/bin/env python3
"""Generate four distinct painted aquarium backdrops with Pollinations.ai.

The service is free but rate-limited; this script waits and retries. Output is
cropped to remove the service watermark and resized to the TV scene resolution.
Generated images are baked into the APK, so the app itself never uses the network.
"""

from __future__ import annotations

import io
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "build/ai-backgrounds"
OUT_DIR = ROOT / "aquarium-tv/src/main/res/drawable-nodpi"
OUT_SIZE = (640, 360)
PROMPTS = {
    "reef": (
        "painterly 2D game background, full-bleed underwater tropical coral reef, "
        "colorful branching corals, sea fans, brain coral, tube sponges, anemones, "
        "sandy seabed along the bottom, turquoise water, soft sunbeams, cozy stylized "
        "illustration, no fish, no text, no watermark, no aquarium glass, no frame, "
        "wide 16:9"
    ),
    "kelp": (
        "empty underwater side view of a dense giant kelp forest, many tall "
        "golden-brown kelp fronds with gas bladders rising from a rocky seabed, "
        "bright green-blue water, strong sunbeams, boulders and holdfasts, painterly "
        "cozy game background, full-bleed, absolutely no animals, no fish, no "
        "sharks, no silhouettes, no text, no watermark, no tank frame, no water "
        "surface, no sky, wide 16:9"
    ),
    "wreck": (
        "underwater painting of a large pile of shattered wooden ship wreckage on "
        "the sandy sea floor, broken hull planks and curved wooden ribs, splintered "
        "beams, torn canvas scraps, wooden cargo crates and barrels and a rusty "
        "anchor scattered in the pale sand, blue-green water, sun rays shining down "
        "from the surface, colorful painterly 2D adventure game background, wide "
        "16:9, no fish, no people, no text, no watermark, no beach, no sky"
    ),
    "river": (
        "underwater side view inside a clear freshwater riverbed, smooth pebbles "
        "and rounded river stones, twisted driftwood roots, fallen brown oak leaves, "
        "reeds and lily pads near the surface above, warm green water, dappled "
        "light, painterly game background, full-bleed, no fish, no text, no "
        "watermark, no tank frame, no sky, wide 16:9"
    ),
}
SEEDS = {"reef": 6101, "kelp": 6202, "wreck": 9701, "river": 6404}
# The free tier currently exposes Sana; it follows the underwater wreck prompt far
# better than Flux, which keeps painting a ship on the surface.
MODELS = {"reef": "flux", "kelp": "flux", "wreck": "sana", "river": "flux"}
# The wreck render puts the service mark low enough that the regular crop would
# keep it, so drop the right edge and a thin bottom strip instead.
CROPS = {"wreck": (0, 0, 960, 540)}
DEFAULT_CROP = (128, 72, 896, 504)


def generate(name: str, prompt: str) -> Image.Image | None:
    query = urllib.parse.urlencode(
        {
            "width": 1024,
            "height": 576,
            "nologo": "true",
            "model": MODELS[name],
            "seed": SEEDS[name],
        }
    )
    url = (
        "https://image.pollinations.ai/prompt/"
        + urllib.parse.quote(prompt, safe="")
        + "?"
        + query
    )
    request = urllib.request.Request(url, headers={"User-Agent": "AquaBricks/1.0"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                data = response.read()
            image = Image.open(io.BytesIO(data)).convert("RGB")
            RAW_DIR.mkdir(parents=True, exist_ok=True)
            (RAW_DIR / f"{name}.jpg").write_bytes(data)
            return image
        except Exception as error:  # includes rate limit HTTP errors
            body = ""
            if hasattr(error, "read"):
                try:
                    body = error.read().decode("utf-8", "replace")
                except Exception:
                    pass
            wait = 46
            if body:
                try:
                    payload = json.loads(body)
                    wait = 50
                    print(f"{name}: {payload.get('error', payload)}")
                except Exception:
                    print(f"{name}: HTTP error {error}")
            else:
                print(f"{name}: {error}")
            time.sleep(wait)
    return None


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    selected = sys.argv[1:] or list(PROMPTS)
    for index, name in enumerate(selected):
        if name not in PROMPTS:
            print(f"Unknown theme: {name}")
            continue
        prompt = PROMPTS[name]
        if index:
            time.sleep(46)
        image = generate(name, prompt)
        if image is None:
            print(f"{name}: generation failed")
            continue
        # Crop away the Pollinations marks at the edges while preserving 16:9.
        image = image.crop(CROPS.get(name, DEFAULT_CROP))
        image = image.resize(OUT_SIZE, Image.Resampling.LANCZOS)
        output = OUT_DIR / f"background_{name}.webp"
        image.save(output, format="WEBP", quality=88, method=6)
        print(f"{name}: wrote {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
