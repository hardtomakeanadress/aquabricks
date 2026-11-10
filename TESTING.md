# Testing AquaBricks

The project has four layers of verification. All JVM tests run on any machine with
a JDK and the Android SDK; instrumented tests need an Android device or emulator.

## 1. JVM unit tests (fast, run on every change)

### Aquarium TV

| Test | What it proves |
|------|----------------|
| `HttpRequestReaderTest` | request line/query parsing, case-insensitive headers, exact binary bodies, truncated bodies, oversized bodies, expired request deadline, malformed traffic |
| `FishRepositoryTest` | fish survive a repository restart, overlapping repository instances cannot overwrite each other, JSON persistence, name sanitisation, 64-fish cap, corrupt index recovery, missing-PNG recovery, delete semantics |
| `FishTest` | 20,000 simulation steps stay inside the tank; a hungry fish reaches food; a 600×40 scan is width-capped at 512×288; tail beat stays speed-matched, integrates its current rate without uptime phase jumps, and multiple fish do not beat in lockstep; whole-body pitch changes smoothly; direction changes ease through an edge-on turn instead of mirroring instantly; fish visit both halves and the full water column; depth changes apparent size without escaping the tank |
| `AquariumSceneTest` (Robolectric, native Skia) | the real scene renders a non-black, colourful frame; a fish changes the frame; 2.5 s of updates changes it again; food sinks, expires and feeding recovers; theme switching rebuilds a different layout without losing fish and reverse cycling wraps; a fully transparent sprite is skipped without crashing; debug PNGs are written |
| `FishServerRobolectricTest` | real HTTP server + PNG decoding + callbacks + JSON listing + delete; non-PNG rejection; UDP discovery beacon reply |

### Fish scanner

| Test | What it proves |
|------|----------------|
| `FishImageProcessorTest` | auto-crop bounds, perspective warp colour preservation, background removal, stud-grid dimensions, no-fish error, noisy backgrounds, brick-mosaic fallback when no lattice is found, concave-crop rejection, fallback LEGO-colour matching, empty-grid rejection |
| `GridAndColourTest` | detects a known stud pitch/count, samples flat brick faces rather than stud highlights/gaps, snaps a 2×2 brick to one LEGO colour, palette-off preserves an off-palette colour, sRGB gamma 2.4 picks the correct palette entry, reconciles silhouette pitches, samples at a detected lattice origin |
| `SamplePhotoTest` | a realistic synthetic photo (textured baseplate, studs, shadows, noise, tilted camera) becomes a transparent auto-grid sprite; auto stud count is correct and the cream eye survives |
| `WhitePaperScanTest` | a real red-fish-on-white-paper scan keeps a plausible 2D stud grid instead of collapsing to one row; paper shadow is removed while the grey brick core survives at low and high cutout settings; output is rebuilt as LEGO bricks |
| `BrickRendererTest` | detected cells become a studded, bevelled brick surface; smooth-shape fallback keeps small tabs while adding studs |
| `CropQuadViewTest` | dragging a crop corner draws the magnifier loupe (real Skia rendering) and cannot make the crop concave |
| `FaceDialogTest` | changing the eye model keeps a position already dragged in the editor |
| `EditActivityTest` | changing the colour mode disables Send immediately, closing the debounce window for sending the previous sprite |
| `ManageTankActivityTest` | the real manager screen lists a fish from a fake tank, asks for confirmation, sends `DELETE`, and refreshes to an empty tank |
| `HomeActivityTest` | the launcher offers both SCAN A FISH and MANAGE FISH IN THE TANK, and scanning opens the camera screen explicitly |
| `FacePainterTest` | every eye/mouth model draws boldly enough for the TV, expressions differ (happy vs angry), positions are normalised, mirroring works, visual previews are written |
| `ImageUtilsRobolectricTest` | Bitmap↔ARGB round-trip, JPEG decode limits, PNG encoding, checkerboard preview |
| `TankAddressTest` | manual host/port parsing and invalid input rejection |

Run them:

```bash
./gradlew :aquarium-tv:testDebugUnitTest :fish-scanner:testDebugUnitTest
```

Reports: `aquarium-tv/build/reports/tests/testDebugUnitTest/index.html`,
`fish-scanner/build/reports/tests/testDebugUnitTest/index.html`.

Visual artefacts are written to each module's `build/test-artifacts/`:
`tv-empty.png`, `tv-fish.png`, `tv-animated.png`, `sample-01-source.png`,
`sample-02-cutout.png`, `sample-03-sprite.png`, `sample-04-auto-sprite.png`,
`face-base.png`, `face-happy.png`, `face-angry.png`, `01-source.png`,
`02-cutout.png`, `03-sprite.png`, `crop-before.png`, `crop-dragging.png`,
`home-screen.png`, `white-paper-sprite.png`, `white-paper-cut-*.png`.

`SamplePhotoTest` reads the committed `.bin` fixture under
`fish-scanner/src/test/resources/samples/`. After changing
`scripts/generate_test_samples.py`, regenerate the sample and refresh that fixture;
the test does not rewrite it automatically.

## 2. Static analysis

```bash
./gradlew :aquarium-tv:lintDebug :fish-scanner:lintDebug
```

Both apps target API 35, minSdk 26. Reports live under
`<module>/build/reports/lint-results-debug.html`.

## 3. Instrumented tests (real Android runtime)

| App | Test | What it proves |
|-----|------|----------------|
| TV | `FishServerInstrumentedTest` | real `BitmapFactory`, real filesystem, HTTP upload/list/delete and restart persistence |
| TV | `AquariumSmokeTest` | launches `MainActivity`, takes a real device screenshot after 3 s, asserts it is colourful, writes `tv-smoke.png` to the app's external files dir |
| Scanner | `EditActivityPipelineTest` | a JPEG is decoded, auto-cropped, cut out, grid-resampled, and the Send button becomes enabled with a stud count |
| Scanner | `TankClientInstrumentedTest` | a real socket receives `POST /fish`, URL-encoded name/creator headers, exact PNG body; GET `/fish` listing and DELETE are parsed; 200 and 409 JSON responses are surfaced correctly |
| Scanner | `MainActivitySmokeTest` | the camera screen inflates and shows its title |

Build the test APKs (does not need a device):

```bash
./gradlew :aquarium-tv:assembleDebugAndroidTest :fish-scanner:assembleDebugAndroidTest
```

Run on a connected device/emulator:

```bash
adb devices
ANDROID_SERIAL=<serial> ./gradlew :aquarium-tv:connectedDebugAndroidTest
ANDROID_SERIAL=<serial> ./gradlew :fish-scanner:connectedDebugAndroidTest
```

> **Data warning:** on this AGP/Chromecast combination, `connectedDebugAndroidTest`
> uninstalls the app after the run. For the aquarium that also deletes its private
> `files/fish` storage. Back up the tank first, or run the instrumented suite on a
> spare device/profile. The real tank should be updated with `install -r`, which
> preserves data. Example backup while the app is installed:
>
> ```bash
> adb exec-out run-as com.aquabricks.aquarium tar -C files -cf - fish > fish-backup.tar
> ```
>
> and restore after reinstalling:
>
> ```bash
> adb push fish-backup.tar /data/local/tmp/
> adb shell run-as com.aquabricks.aquarium tar -C files -xf /data/local/tmp/fish-backup.tar
> ```

Or use the helper:

```bash
./scripts/run_device_tests.sh          # both apps on one attached device
# when several devices are attached, set ANDROID_SERIAL explicitly
TV_IP=192.168.0.178 ./scripts/run_device_tests.sh   # also `adb connect` the TV
```

### Chromecast HD setup for instrumented tests

1. TV: **Settings → System → About → Android TV OS build**, press select 7×.
2. **Settings → System → Developer options → Wireless debugging** (the Chromecast
   HD uses pairing codes on current firmware).
3. From the build machine, discover the pairing endpoint and pair:
   `adb mdns services` (look for `_adb-tls-pairing._tcp`), then
   `adb pair 192.168.0.178:<pairPort> <six-digit-code>`.
4. Connect to the advertised debug port (`adb connect 192.168.0.178:<debugPort>`)
   and use the full serial from `adb devices` as `ANDROID_SERIAL`.
5. Run `./scripts/run_device_tests.sh` with that `ANDROID_SERIAL`.

The TV screenshot is pulled to `build/device-artifacts/` by the helper script.

### Phone setup

Enable **Developer options → USB debugging** (or wireless debugging), connect it,
and run the scanner tests. Camera permission is not needed for the editor test;
`MainActivitySmokeTest` only checks the screen, not the camera hardware.

## 4. Manual acceptance checklist

The automated suite cannot judge whether a fish *looks* right. Before shipping:

- [ ] Scan a real flat LEGO fish on a baseplate; the cutout removes the baseplate.
- [ ] Check the **AUTO GRID** stud count matches the actual build (e.g. 14×8).
- [ ] Confirm brick colours match: red stays LEGO red, orange stays orange, and a
      light/cream eye brick survives. Toggle **Match LEGO colours** off to compare.
- [ ] Pick a happy eye/smile and an angry eye/frown; drag them to different positions
      and confirm the face arrives on the TV exactly where placed.
- [ ] Flip a fish whose head faces left; it swims head-first in both directions.
- [ ] Send three fish; names appear in arrival plaques.
- [ ] Force-stop and reopen the TV app; all three fish return.
- [ ] Press OK several times; food sinks and fish chase it.
- [ ] Let the tank run 30 minutes; no memory growth, no frame drops, no drift.
- [ ] Try manual address entry with discovery blocked (hotspot/guest network).
- [ ] Open **Manage fish**, remove one, and confirm it disappears from the TV.
- [ ] Fill the tank to 64 fish; the 65th gets a clear "tank is full" error.

## Current verification status (2026-11-10)

- **80 JVM/Robolectric tests, 0 failures** — 36 in `aquarium-tv`, 44 in `fish-scanner`.
- **8 instrumented tests, 0 failures** — 2 tank tests and 6 scanner tests, run on both
  a **Pixel 7 (Android 16 / API 37)** and the **Chromecast HD (Android 14 / API 34)**.
  The tank suite was re-run on the Chromecast after the sizing fix: 2/2 passed. They
  were not rerun during the audit pass because the connected runner uninstalls the app
  and wipes the stored fish; both instrumentation APK sets still compile.
- **0 lint errors** in both modules (intentional warnings remain: small pixel-font SPs,
  locked orientations, dependency-update notices).
- Both debug APKs build, and both instrumentation APK sets compile.
- Visual artefacts inspected: tank frames, realistic cutout + auto-grid sprite,
  happy/angry face previews, and real Pixel 7 + Chromecast screenshots.
- **Real-device end-to-end tests passed:** the demo LEGO fish was POSTed to both the
  phone and the TV, screenshotted, then the app was force-stopped and relaunched; the
  fish persisted. `scripts/phone_e2e.sh` automates this for a phone.
- **Chromecast performance:** with static scenery baked, animated plants cached in
  two 15 fps overlays and fish sprites pre-scaled, the render thread uses about
  **46% of one core** at 512×288 / 30 fps with one fish (down from ~75%). The TV APK
  is about **926 KB** and the process uses about **61–64 MB PSS** (8.4 MB Java heap,
  7.9 MB native, 3.8 MB GL). The tank is installed and running.

The scanner tests also found two real bugs that pure image maths missed: Espresso 3.5
could not run on Android 16 (the smoke test now uses plain `ActivityScenario`), and
the initial RGB palette matcher mapped orange-red bricks to gold (now CIELAB).

A follow-up read-only Codex audit (`gpt-6.1-sol`) reviewed the theme and animation
changes plus the existing paths. Confirmed fixes: repository mutations are serialised
and reloaded across activity instances (closing a shutdown write race), food pellets
now sink and expire, dragged face positions survive model changes, tail phase is
accumulated instead of derived from absolute uptime, Send is invalidated as soon as
the crop/settings change, HTTP admission and request duration are bounded (with
constant-memory header parsing), sRGB uses the 2.4 gamma, the smooth fallback honours
the LEGO-colour toggle, concave crops are rejected before warping, left-facing pitch
is applied after mirroring, and render loops have per-surface generations. Regression
tests cover the repository overlap, food lifecycle, phase continuity, palette gamma,
fallback matching, concave crops, theme wrap-around, dragged face positions and the
editor's stale-Send window.

A real 507×39 scan sent from the phone exposed a third class of bug that only a
real tank load could trigger: the fish's draw width exceeded the 512 px scene,
making its valid x-range empty and throwing inside `coerceIn` on every launch.
Fish are now scaled so neither dimension can exceed the tank, unreadable sprites
are skipped instead of killing the app, and the 600×40 regression is covered by
`FishTest`. The Chromecast was force-stopped and relaunched with all three stored
fish loaded successfully.

The same real scan also exposed the scanner-side bug: its crop was tight around
the fish, but auto-grid detection trusted a vertical silhouette peak and reported
`13 x 1`, which the TV correctly rendered as a long strip. The pitch estimator now
rejects a vertical pitch that contradicts the horizontal stud pitch, and
`WhitePaperScanTest` locks in the real white-paper crop. The crop handles are now
semi-transparent and a magnifier follows the dragged corner so a finger cannot hide
the cut line. On the Pixel 7, replaying the exact cached scan with the user's saved
crop now reports **AUTO GRID 13 x 10** and shows the fish preview instead of the
strip.

## Build matrix

```bash
# everything that can run without a device
./scripts/run_tests.sh

# APKs in dist/
./scripts/build_all.sh
```

`./scripts/run_tests.sh` runs both unit-test suites, both lint tasks, both debug
builds and compiles both instrumentation APKs, then prints the on-device command.
