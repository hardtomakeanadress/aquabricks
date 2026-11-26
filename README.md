# AquaBricks

A cosy pixel-art aquarium for a **Chromecast with Google TV** and a phone app that
turns flat fish built from interlocking bricks into swimming sprites.

```
  phone                                 TV / Chromecast HD
┌──────────────────────┐   LAN HTTP   ┌─────────────────────────────┐
│ AquaBricks Scanner   │ ───────────► │ AquaBricks Aquarium         │
│                      │  PNG + name  │                             │
│ CameraX photo        │              │ 640x360 pixel-art renderer  │
│ 4-corner crop        │              │ fish + decor + bubbles/food │
│ background cutout    │              │ remembers every fish        │
│ Stud-based grid      │              │ http://TV_IP:8765           │
└──────────────────────┘              └─────────────────────────────┘
```

The two apps talk directly over the home Wi-Fi. There is no cloud service, no
account, and the phone never needs internet access. Every fish is stored as a PNG
plus a small JSON index in the TV app's private storage, so the tank is rebuilt
whenever the app is opened.

## Repository layout

```
aquarium-tv/     Android TV app (no AndroidX/Compose; SurfaceView + Canvas)
fish-scanner/    phone app (CameraX, 4-corner perspective crop, pure-Kotlin image pipeline)
scripts/         asset generation, test samples, build/device test helpers
dist/            copied APKs after a build
```

## What the fish scanner does

1. **Cameras** – CameraX preview with a wide viewfinder guide.
2. **Corners** – drag four semi-transparent handles around the fish; a magnifier
   appears above your finger with a crosshair so you can see exactly where the
   crop line meets the fish. The crop is perspective-corrected.
3. **Cutout** – the plain background is flood-filled away from the borders, locally
   following lighting gradients so textured baseplates and shadows disappear.
4. **Auto stud grid** – the app detects the spacing between studs from the repeating
   stud/gap edges. Because the grid follows the physical bricks, a 2×4 brick stays a
   2×4 block and the overall stud count is preserved instead of being squeezed into
   an arbitrary 8/12/16/24 columns. A vertical pitch that disagrees with the
   horizontal stud pitch is rejected instead of collapsing the fish to one row;
   the sampler uses the detected lattice origin, not a stretched bounding box.
   If no reliable lattice is found (for example a smooth white tile), the exact
   silhouette is kept and rebuilt as a two-stud-high brick mosaic instead of
   inventing a fine 40×40 grid. **Natural** keeps the full photo instead.
5. **Brick colours** – each cell is sampled from the flat face around its stud (not
   the highlight or the gaps) and matched to the nearest brick colour in CIELAB.
   Turn off **Match brick colours** for unusual or custom bricks.
6. **Brick sprite** – the image is redrawn as bricks. Each occupied cell gets
   a flat brick face, a bevelled outline, a raised round stud and a thin dark joint
   where it meets the next brick. In the default brick modes the tank never receives
   a cropped patch of the original photo texture; **Natural** intentionally sends
   the cutout photo instead.
7. **Faces** – premade pixel-art eyes (round, happy, angry, sleepy) and mouths
   (smile, grin, frown, open) can be selected and dragged onto the fish. They are
   boldly scaled for the TV, so eyes and mouths stay readable instead of becoming
   dots when a fish is scaled down. The face is painted into the sprite, so it
   arrives on the TV exactly as placed.
8. **Send** – the phone broadcasts a UDP discovery packet, finds the TV, and POSTs the
   PNG with the fish name, maker and facing direction.
9. **Manage** – **MANAGE FISH** on the camera screen opens the tank’s fish list
   without requiring a scan; removal asks for confirmation first.

## What the aquarium does

- Four selectable aquascapes, each with its own water and sand palette, hardscape
  and plant mix: **Coral Reef**, **Kelp Forest**, **Sunken Wreck** and
  **Riverbed**. Press **LEFT/RIGHT** (or **T**) on the TV remote to cycle; the
  current landscape and the **PRESS LEFT / RIGHT** hint are shown in the top-left
  corner, and the choice is remembered across launches.
- Pixel-art decor includes tall swaying kelp, feathery ferns, broad-leaf anubias,
  sea grass, floating surface leaves with dangling roots, a red branching coral,
  sea fan, tube sponges, brain coral, urchin, anemone, a castle, a broken
  shipwreck, chest, rocks, starfish, shells, drifting caustic ribbons, dappled
  sunlight pools, bubbles and plankton. The soft plants are animated and
  deliberately drawn as clear silhouettes instead of featureless blobs.
- A 30-second underwater ambience loop, built from a CC0 field recording by
  Kinoton, plays at low volume. Press
  **UP** to turn sound on and **DOWN** to turn it off. **M** or the remote's
  **MUTE** button also toggles it; the choice is remembered.
- Hold **OK** on the remote (or press **S** on a keyboard) to turn the shark
  on or off. A small shark icon beside the sound icon shows the state; the
  choice is remembered. Disabling it lets an active shark recede into the
  distance instead of vanishing.
- The screen is kept clear: after the brief startup hints, only the fish, their
  small names, a compact landscape chip and a tiny sound icon remain.
- Fish swim with a carangiform-style traveling wave: the head stays rigid, the rear
  body bends smoothly, and the tail beat frequency follows the actual swim speed.
  Whole-body pitch is smoothed; there is no yaw oscillation or body stretching.
  Fish glide through calm, committed arcs with a limited turn rate, occasional
  pauses and independent waypoint timing, so they do not ping-pong across the tank
  or flip direction every second. Direction changes ease through an edge-on turn
  instead of an instant mirror flip. Each fish has its own tail phase, tempo and
  activity rhythm, so a school does not beat, slow down or dart in lockstep.
  Occasional bursts and shadows remain.
- Fish also move through a depth axis: they swim toward and away from the glass,
  shrinking, fading slightly and sitting noticeably higher when farther away.
  Waypoints deliberately visit both the near and far glass, so front-to-back
  swimming is visible rather than just left/right motion. Far fish are drawn first
  and near fish last.
- A large shark makes a rare, irregular pass: about a minute after launch, then
  every 2-4 minutes. A traveling wave runs through its whole body, the pectoral
  fin and jaw move independently, and it banks and surges instead of sliding on
  rails. It tracks a fish vertically, lunges at the end, scatters the school and
  eats one or two fish. It never takes the last fish. Eaten fish are deleted from
  the tank index, so they stay gone after a restart and disappear from the phone's
  "manage fish" list.
- The fish are deliberately small enough for two or three to share the tank, and
  each one has its name in a small label underneath. New arrivals still get a
  bubble burst and a name plaque.
- Brick colours are shown untouched: the fish are drawn after all water overlays, so
  the scanned brick colours stay accurate on the TV.
- Press **OK / Enter / Space** (or tap the screen) to drop food. Pellets sink
  slowly and are taken at the fish's mouth, with a small bubble puff, rather than
  disappearing through the body.
- The tank name and LAN address are shown during startup/empty-tank hints; the
  normal tank view stays free of persistent instructions.

## Build

Requirements: JDK 17+, Android SDK platform 35 and build-tools 35. The repository
includes a Gradle wrapper.

```bash
# point Gradle at your SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :aquarium-tv:assembleDebug :fish-scanner:assembleDebug
```

The ambience loop is committed as an OGG asset. It is built from
["Underwater Ambience" by Kinoton](https://freesound.org/s/393819/), released
under Creative Commons 0. To regenerate it (requires `ffmpeg`), run
`python3 scripts/generate_ambience.py`.

APKs:

```
aquarium-tv/build/outputs/apk/debug/aquarium-tv-debug.apk
fish-scanner/build/outputs/apk/debug/fish-scanner-debug.apk
```

## Install on a Chromecast with Google TV

The Chromecast HD is an Android TV device, so the tank is a normal sideloaded app.

1. On the TV: **Settings → System → About → Android TV OS build**, press select 7×.
2. Back in **Settings → System → Developer options**, enable **Wireless debugging**
   (current Chromecast HD firmware shows a pairing code and ports).
3. Discover the pairing endpoint and pair from the build machine. Replace the
   example IP address with your TV’s address:

```bash
adb mdns services                    # note the _adb-tls-pairing port
adb pair 192.0.2.10:<pairPort> <six-digit-code>
adb mdns services                    # note the _adb-tls-connect port
adb connect 192.0.2.10:<debugPort>
adb -s 192.0.2.10:<debugPort> install -r dist/aquarium-tv-debug.apk
adb -s 192.0.2.10:<debugPort> shell monkey \
  -p com.aquabricks.aquarium -c android.intent.category.LEANBACK_LAUNCHER 1
```

Or pair once, then run `TV_SERIAL=<serial-from-adb-devices> ./scripts/install_tv.sh`.
The app appears in the Google TV Apps row as **AquaBricks Aquarium**.

## Install on the phone

```bash
adb install -r fish-scanner/build/outputs/apk/debug/fish-scanner-debug.apk
```

The scanner needs camera permission only. It stores nothing in the cloud.

## Trying both apps on one phone

The tank is a normal Android app and also runs on a phone (hold it in landscape).
This is the fastest smoke test before deploying to the TV:

1. Install both APKs: `adb install -r dist/aquarium-tv-debug.apk` and
   `adb install -r dist/fish-scanner-debug.apk` (or copy them over and tap them).
2. Open **AquaBricks Aquarium** first and leave it in recents. It serves the tank
   on port 8765 while the app process is alive.
3. Open **AquaBricks Scanner**, scan a fish and tap send. The scanner probes
   `127.0.0.1:8765` as well as broadcasting, so the tank on the same phone is
   found even when the router blocks broadcast loopback. You can also type
   `127.0.0.1:8765` into the manual address field.
4. Switch back to the aquarium; the fish is swimming. Force-stop and reopen it to
   confirm persistence.

## Using it

1. Open the aquarium on the TV. Its address is shown bottom-right (for example
   `192.0.2.10:8765`).
2. Build a flat fish from interlocking bricks on a plain baseplate or sheet of paper.
3. Open **AquaBricks Scanner**; the home screen offers **SCAN A FISH** and
   **MANAGE FISH IN THE TANK**. Choose **SCAN A FISH**, put the fish in the
   viewfinder, and tap the shutter.
4. Drag the four corners onto the edges of the fish.
5. Pick a name, who made it and which way the head faces. The stud grid and brick
   colours are detected automatically; the status line shows the detected grid,
   e.g. `AUTO GRID 14 x 8 STUDS`. Raise **Background cutout** only if baseplate
   remains around the fish.
6. Tap **Eyes & Mouth** to pick a premade expression and drag the eye/mouth into
   place. **Happy** and **angry** sets are included, plus sleepy eyes and open/grin
   mouths. The face is baked into the sprite.
7. Tap **Send to tank**. The scanner finds the TV automatically. If discovery is
   blocked by the router, type the TV address shown on screen.
8. The fish swims in. Close and reopen the TV app later — every fish is still there.
9. To remove a fish, tap **MANAGE FISH** at the top-right of the scanner’s camera
   screen. The scanner finds the tank automatically (or asks for its address), lists
   its fish and asks before removing one. The same manager is also available from
   the send dialog via **MANAGE** / **MANAGE FISH IN THIS TANK**.

## Local HTTP API

The tank intentionally exposes a tiny, dependency-free API on the LAN:

| Method   | Path            | Meaning |
|----------|-----------------|---------|
| `GET`    | `/health`       | tank name, fish count, port |
| `GET`    | `/fish`         | JSON list of stored fish |
| `POST`   | `/fish`         | PNG body; `X-Fish-Name`, `X-Fish-Creator`, `X-Fish-Facing` (URL-encoded) |
| `DELETE` | `/fish/<id>`    | remove a fish |
| `GET`    | `/`             | small browser page for debugging |

Discovery uses a UDP broadcast to port 8766 (`AQUABRICKS_DISCOVER`); the tank replies
with `AQUABRICKS|<name>|<httpPort>|<fishCount>`. No authentication is used — it is a
home-LAN toy. Do not expose port 8765/8766 to the internet.

## Design constraints

The Chromecast HD has 1.5 GB of RAM and a modest SoC, so the tank:

- renders at 640×360 and lets the GPU upscale with bilinear filtering, so scanned
  fish stay readable instead of showing a field of hard pixel blocks,
- pre-renders the static aquascape and vignette into one bitmap, then composites
  animated plant layers (including the floating leaves) from two transparent
  overlays redrawn at 15 fps,
- draws fish sprites at a 2× texture supersample, uses `drawBitmapMesh` for the
  tail/body wave, and uses a hardware surface canvas for the filtered upscale,
- caps each fish at 30% of the tank width and 13% of the tank height, so even a
  very long, thin scan fits without invalidating its swim bounds; unreadable
  sprites are skipped,
- uses 30 fps and caps the tank at 64 fish,
- ships with no AndroidX/Compose runtime and no image-loading libraries; audio is
  a single framework `MediaPlayer` loop (about 361 KB) with no per-frame work.

Measured on the Chromecast HD: the TV APK is about **1.33 MB**, the running process
uses about **66 MB PSS** (9.8 MB Java heap, 10.2 MB native, 4.4 MB graphics), and
the render thread uses roughly **67% of one core** with one fish at 30 fps (about
17% of the four-core CPU). The 640×360 buffer and 2× fish texture supersample cost
more than the old 512×288 frame but keep scanned fish legible.

## Testing

Unit tests, Robolectric rendering/server tests, instrumented device tests and lint
commands are documented in [TESTING.md](TESTING.md). Quick JVM run:

```bash
./gradlew :aquarium-tv:testDebugUnitTest :fish-scanner:testDebugUnitTest
```

## Troubleshooting

- **Phone cannot find the tank** – both devices must be on the same Wi-Fi network
  (guest networks and AP isolation block it). Type the TV address manually; it is
  visible at the bottom-right of the tank. If both apps are on the same phone, use
  `127.0.0.1:8765`.
- **Cutout removes part of the fish** – lower the *Background cutout* slider, or
  crop tighter so the fish fills more of the frame.
- **Cutout leaves baseplate around the fish** – raise the slider, or use a plainer
  background. White paper under a white/cream fish is the hardest case.
- **TV app not visible in the launcher** – make sure the manifest's
  `LEANBACK_LAUNCHER` category survived your build; sideload again with
  `adb install -r`.
- **Port already in use** – the tank tries 8765–8775 automatically and reports the
  chosen port on screen.
