# step_route_simulator.py

A single-file Python developer tool for simulating a humanized walking route
on Android: it turns a GPX track, an inline list of waypoints, or a
hand-drawn map route into a stream of mock GPS fixes plus matching step-count
data, for testing location-aware and Health-Connect-integrated apps.

Only the Python standard library is used — no `pip install` required
(Python 3.8+).

## What it's for

- Exercising a location-based app's GPS handling (turn-by-turn nav, geofences,
  live tracking UI) against a repeatable, realistic route instead of manually
  walking around with a physical device.
- Feeding a fitness/Health-Connect integration deterministic step and
  distance data while you build dashboards, streak logic, or sync code,
  without needing to actually walk the route yourself.
- Quickly previewing route geometry, step counts, and timing before running
  a live device test (`--dry-run`).

Use it against apps and accounts you control, and respect the terms of
service of anything that consumes the resulting data — this tool does not
bypass a service's own anti-fraud checks.

## 1. Prerequisites on the Android side

### Enable USB debugging
1. Settings → About phone → tap **Build number** 7 times to unlock Developer
   options.
2. Settings → System → Developer options → enable **USB debugging**.
3. Connect the device via USB (or start an Android Studio emulator) and
   accept the "Allow USB debugging?" prompt.
4. Verify the connection:
   ```bash
   adb devices
   ```
   You should see your device/emulator listed as `device` (not
   `unauthorized`).

### Set a mock location app (real devices only)
The **`--method emulator`** live-streaming mode only works against the
Android Studio emulator (it uses the emulator console's `geo fix` command).
To drive a **real device**, you need a small companion app that owns the
mock-location provider, because ADB itself cannot write to a real device's
`LocationManager`:

1. Build and install [`android_companion_app/`](android_companion_app/) — a
   minimal app included in this repo that's already wired up for this
   script's broadcast protocol. See that folder's README for full setup.
2. Developer options → **Select mock location app** → choose **StepSim
   Companion** (or your own companion app, if you're rolling your own — it
   needs a `BroadcastReceiver` for the intent action passed via
   `--broadcast-action`, default `com.stepsim.MOCK_LOCATION`, reading the
   `lat` / `lng` float extras and forwarding them to
   `LocationManager.setTestProviderLocation(...)`).
3. Start the service in the companion app.
4. Run this script with `--live --method broadcast --receiver-package
   com.stepsim.companion`.

### Health Connect
`--export-health` **generates a JSON file** shaped like Health Connect's
`StepsRecord`/`DistanceRecord` insert payloads — it does not write to Health
Connect itself (there is no ADB bridge for that).

To actually get steps into Health Connect, use `--write-health` alongside
`--live --method broadcast`: as the route streams, the script buckets steps
into `--bucket-seconds`-wide intervals (same width `--export-health` uses)
and broadcasts each bucket to the companion app, which calls
`HealthConnectClient.insertRecords(...)` for you in real time. See
[`android_companion_app/`](android_companion_app/)'s README for the one-time
"Grant Health Connect access" step this requires. This only works on a real
device via the companion app — there's no equivalent for the
`--method emulator` path.

If you'd rather write your own importer instead (e.g. to batch-import a
previously `--export-health`'d file), build a small companion app using the
[Health Connect Jetpack client](https://developer.android.com/health-and-fitness/guides/health-connect)
that reads the JSON and calls `HealthConnectClient.insertRecords(...)`.

## 2. Installation

Nothing to install — just download the script:

```bash
python3 step_route_simulator.py --help
```

## 3. Route input

Pick exactly one:

| Flag | Description |
|---|---|
| `--gpx <file>` | Parse a GPX file (`<trkpt>`, `<rtept>`, or `<wpt>` elements — works with exports from Google Maps, Strava, GPX Studio, etc). |
| `--waypoints "lat1,lng1 \| lat2,lng2 \| ..."` | Inline pipe-separated waypoints. |
| `--draw-map` | Opens `route_builder.html` (embedded, served locally) with an interactive Leaflet.js map. Click to add waypoints, adjust speed/stride sliders, then click **Export route to CLI** — the page POSTs the route back to the running script. |

`--save-route <file.gpx>` writes whatever route was resolved (drawn, inline,
or re-parsed GPX) out to a GPX file — mainly useful with `--draw-map` or
`--waypoints`, since otherwise a drawn/inline route only exists for that one
run and can't be reused without redrawing/retyping it.

## 4. Motion tuning

| Flag | Default | Meaning |
|---|---|---|
| `--stride` | `0.78` m | Stride length used to convert distance → step count. |
| `--speed-min` | `3.5` km/h | Slowest pace; speed drifts randomly between min and max. |
| `--speed-max` | `5.5` km/h | Fastest pace. |
| `--cadence-min` / `--cadence-max` | `105` / `125` spm | Range of per-step cadence. |
| `--pause-min` / `--pause-max` | `5` / `15` s | Range of pause duration at route turn points (e.g. crossing a street). |
| `--seed` | random | Fix for a reproducible simulation. |

## 5. Execution modes

You can combine `--export-health` and `--live` in one run; `--dry-run` runs
alone and never touches ADB or the filesystem.

### Dry run — inspect a route before doing anything live
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --dry-run
```
Prints total distance, estimated step count, estimated duration, pause
count, and a preview of the first fixes in the coordinate stream.

### Draw a route interactively, then stream it live
```bash
python3 step_route_simulator.py --draw-map --live
```
Opens the map in your default browser at `http://127.0.0.1:8934/`. After you
export the route, the script streams it to the connected emulator/device.

### Stream a GPX route live to an Android Studio emulator
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live --method emulator
```

### Stream live to a real device via a companion mock-location app
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live \
  --method broadcast --device <adb-serial> \
  --broadcast-action com.myapp.MOCK_LOCATION --receiver-package com.myapp.debug
```

### Stream live location *and* write steps into Health Connect
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live \
  --method broadcast --device <adb-serial> --receiver-package com.stepsim.companion \
  --write-health
```
Requires the [`android_companion_app/`](android_companion_app/) with Health
Connect access already granted (see its README) — steps and distance are
inserted a bucket at a time as the route plays out.

### Speed up playback
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live --speed-multiplier 4
```

### Generate a Health Connect JSON payload
```bash
python3 step_route_simulator.py --gpx morning_walk.gpx \
  --export-health steps.json --bucket-seconds 30 --start-time 2024-06-01T08:00:00+00:00
```
Produces `steps.json` with a `summary` block plus bucketed `stepsRecords` and
`distanceRecords` arrays.

### Inline waypoints, no file needed
```bash
python3 step_route_simulator.py \
  --waypoints "40.7128,-74.0060 | 40.7135,-74.0050 | 40.7150,-74.0040" \
  --dry-run
```

## 6. How it works, briefly

1. **Route parsing** — GPX/inline/drawn waypoints become an ordered list of
   `(lat, lon)` points.
2. **Densification** — each segment between waypoints is subdivided so no gap
   exceeds one stride length, using the Haversine formula for segment
   lengths.
3. **Step-by-step walk** — the script advances one stride at a time along the
   densified path, drawing a fresh speed and cadence sample for each step
   (Gaussian speed noise, uniform cadence in `[cadence-min, cadence-max]`)
   and a small GPS jitter, and occasionally inserting a pause at an original
   waypoint (turn) node.
4. **Output** — the resulting list of timestamped fixes feeds `--dry-run`,
   `--live` (ADB), and `--export-health` (JSON) directly, so all three modes
   describe the exact same simulated walk.
