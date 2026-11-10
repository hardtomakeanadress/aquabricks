# AquaBricks

A cosy pixel-art aquarium for a **Chromecast with Google TV** and a phone app that
turns flat LEGO fish built by kids into swimming sprites.

```
  phone                                 TV / Chromecast HD
┌──────────────────────┐   LAN HTTP   ┌─────────────────────────────┐
│ AquaBricks Scanner   │ ───────────► │ AquaBricks Aquarium         │
│                      │  PNG + name  │                             │
│ CameraX photo        │              │ 512x288 pixel-art renderer  │
│ 4-corner crop        │              │ fish + decor + bubbles/food │
│ background cutout    │              │ remembers every fish        │
│ LEGO-stud pixel grid │              │ http://TV_IP:8765           │
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
4. **Auto stud grid** – the app detects the real LEGO stud pitch from the repeating
   stud/gap edges. Because the grid follows the physical bricks, a 2×4 brick stays a
   2×4 block and the overall stud count is preserved instead of being squeezed into
   an arbitrary 8/12/16/24 columns. A vertical pitch that disagrees with the
   horizontal stud pitch is rejected instead of collapsing the fish to one row;
   the sampler uses the detected lattice origin, not a stretched bounding box.
   If no reliable lattice is found (for example a smooth white tile), the exact
   silhouette is kept and rebuilt as a two-stud-high brick mosaic instead of
   inventing a fine 40×40 grid. **Natural** keeps the full photo instead.
5. **Brick colours** – each cell is sampled from the flat face around its stud (not
   the highlight or the gaps) and snapped to the nearest LEGO colour using a
   perceptual CIELAB match. The **Match LEGO colours** checkbox can be turned off
   for unusual/custom bricks.
6. **Brick sprite** – the sprite is repainted as LEGO pieces: each occupied cell gets
   a flat brick face, a bevelled outline and a raised round stud. In the default
   brick modes the tank never receives a cropped patch of the original photo
   texture; **Natural** intentionally sends the cutout photo instead.
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
  choice is remembered across launches.
- Pixel-art decor includes tall swaying kelp, feathery ferns, broad-leaf anubias,
  sea grass, a red branching coral, sea fan, tube sponges, brain coral, urchin,
  anemone, a castle, a broken shipwreck, chest, rocks, starfish, shells, drifting
  caustic ribbons, dappled sunlight pools, bubbles and plankton. The soft plants
  are animated and deliberately drawn as clear silhouettes instead of featureless
  blobs.
- Fish swim with a carangiform-style traveling wave: the head stays rigid, the rear
  body bends smoothly, and the tail beat frequency follows the actual swim speed.
  Whole-body pitch is smoothed; there is no yaw oscillation or body stretching.
  Direction changes ease through an edge-on turn instead of an instant mirror flip.
  Each fish has its own tail phase, tempo and activity rhythm, so a school does not
  beat, slow down or dart in lockstep. Fish prefer distant waypoints, so they roam
  both halves and the full water column. Occasional bursts, depth scaling and
  shadows remain.
- Fish also move through a depth axis: they swim toward and away from the glass,
  shrinking, fading slightly and sitting higher when farther away. Far fish are
  drawn first and near fish last.
- The fish are deliberately small enough for two or three to share the tank, and
  each one has its name in a small label underneath. New arrivals still get a
  bubble burst and a name plaque.
- Brick colours are shown untouched: the fish are drawn after all water overlays, so
  the scanned LEGO palette stays accurate on the TV.
- Press **OK / Enter / Space** (or tap the screen) to drop food; fish chase and eat it.
- The tank name and LAN address appear subtly at the bottom of the screen.

## Build

Requirements: JDK 17+, Android SDK platform 35 and build-tools 35. The repository
includes a Gradle wrapper.

```bash
# point Gradle at your SDK
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :aquarium-tv:assembleDebug :fish-scanner:assembleDebug
```

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
3. Discover the pairing endpoint and pair from the build machine:

```bash
adb mdns services                    # note the _adb-tls-pairing port
adb pair 192.168.0.178:<pairPort> <six-digit-code>
adb mdns services                    # note the _adb-tls-connect port
adb connect 192.168.0.178:<debugPort>
adb -s 192.168.0.178:<debugPort> install -r dist/aquarium-tv-debug.apk
adb -s 192.168.0.178:<debugPort> shell monkey \
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
   `192.168.0.178:8765`).
2. Build a flat fish from LEGO bricks on a plain baseplate or sheet of paper.
3. Open **AquaBricks Scanner**; the home screen offers **SCAN A FISH** and
   **MANAGE FISH IN THE TANK**. Choose **SCAN A FISH**, put the fish in the
   viewfinder, and tap the shutter.
4. Drag the four corners onto the edges of the fish.
5. Pick a name, who made it and which way the head faces. The stud grid and LEGO
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

- renders at 512×288 and lets the GPU upscale with nearest-neighbour filtering
  (chunky pixels are the intended look),
- pre-renders the static aquascape and vignette into one bitmap, then composites
  animated plant layers from two transparent overlays redrawn at 15 fps,
- draws fish sprites pre-scaled to their on-screen size, uses `drawBitmapMesh`
  for the tail/body wave, and uses a hardware surface canvas for the upscale,
- caps each fish at 38% of the tank width, so even a very long, thin scan fits
  without invalidating its swim bounds; unreadable sprites are skipped,
- uses 30 fps and caps the tank at 64 fish,
- ships with no AndroidX/Compose runtime and no image-loading libraries.

Measured on the Chromecast HD: the TV APK is about **926 KB**, the running process
uses about **61–64 MB PSS** (8.4 MB Java heap, 7.9 MB native, 3.8 MB GL), and the
render thread uses roughly **46% of one core** with one fish at 30 fps (about 12%
of the four-core CPU). Before static scenery and pre-scaled sprites it was around 75%.

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

AquaBricks is a personal DIY project and is not affiliated with the LEGO Group.
