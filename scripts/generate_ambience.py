#!/usr/bin/env python3
"""Build the looping underwater ambience from a CC0 field recording.

Source: "Underwater Ambience" by Kinoton
        https://freesound.org/s/393819/
License: Creative Commons 0 (public domain, no attribution required)

The freesound preview is used as the source so the script needs no account.
The liveliest 30 seconds are trimmed, gently filtered, crossfaded into a
seamless loop and normalised. Requires ffmpeg for MP3 decode / OGG encode.
"""

from __future__ import annotations

import array
import subprocess
import sys
import urllib.error
import urllib.request
import wave
from pathlib import Path

RATE = 48_000
CHANNELS = 2
LOOP_SECONDS = 30.0
CROSSFADE_SECONDS = 2.0
TRIM_START_SECONDS = 21.0
PEAK = 0.85

ROOT = Path(__file__).resolve().parents[1]
CACHE_PATH = ROOT / "build/ambience-samples/kinoton/ambience.mp3"
RAW_PATH = ROOT / "build/ambience-samples/kinoton/segment.wav"
OGG_PATH = ROOT / "aquarium-tv/src/main/res/raw/aquarium_ambience.ogg"
SOURCE_URL = "https://cdn.freesound.org/previews/393/393819_2247456-hq.mp3"


def download() -> None:
    if CACHE_PATH.exists() and CACHE_PATH.stat().st_size > 1_000_000:
        return
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(SOURCE_URL, headers={"User-Agent": "AquaBricks/1.0"})
    with urllib.request.urlopen(request, timeout=180) as response:
        CACHE_PATH.write_bytes(response.read())


def decode_segment() -> None:
    total = LOOP_SECONDS + CROSSFADE_SECONDS
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", str(TRIM_START_SECONDS), "-t", str(total),
            "-i", str(CACHE_PATH),
            "-af", "highpass=f=40,lowpass=f=10000",
            "-ar", str(RATE), "-ac", str(CHANNELS),
            "-c:a", "pcm_s16le", str(RAW_PATH),
        ],
        check=True,
    )


def build_loop() -> float:
    with wave.open(str(RAW_PATH), "rb") as source:
        channels = source.getnchannels()
        frame_count = source.getnframes()
        raw = array.array("h")
        raw.frombytes(source.readframes(frame_count))
    if channels != CHANNELS:
        raise SystemExit("Unexpected channel count from ffmpeg")

    fade = int(CROSSFADE_SECONDS * RATE)
    loop_frames = frame_count - fade
    loop = array.array("h", raw[: loop_frames * CHANNELS])
    for i in range(fade):
        t = i / fade
        base = i * CHANNELS
        tail = (loop_frames + i) * CHANNELS
        for c in range(CHANNELS):
            loop[base + c] = int(raw[tail + c] * (1.0 - t) + raw[base + c] * t)

    peak = max(abs(value) for value in loop) or 1
    scale = (PEAK * 32767.0) / peak
    for i in range(len(loop)):
        loop[i] = int(max(-32768, min(32767, loop[i] * scale)))

    with wave.open(str(RAW_PATH), "wb") as output:
        output.setnchannels(CHANNELS)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(loop.tobytes())
    return loop_frames / RATE


def encode() -> None:
    OGG_PATH.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-i", str(RAW_PATH), "-c:a", "libvorbis", "-q:a", "4", str(OGG_PATH)],
        check=True,
    )
    RAW_PATH.unlink(missing_ok=True)


def main() -> int:
    try:
        download()
        decode_segment()
        seconds = build_loop()
        encode()
    except (OSError, subprocess.CalledProcessError, urllib.error.URLError) as error:
        print(f"Could not build ambience: {error}", file=sys.stderr)
        return 1
    print(f"Wrote {OGG_PATH} ({OGG_PATH.stat().st_size} bytes, {seconds:.1f}s)")
    print("Source: Kinoton, 'Underwater Ambience', CC0 - https://freesound.org/s/393819/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
