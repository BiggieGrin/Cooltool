# StepSim Companion

An Android app that simulates a walking route **entirely on the phone** — no
laptop needed — driving the device's mock location and optionally writing
steps into **Health Connect**. It can also still be driven from a laptop by
`step_route_simulator.py --live --method broadcast`.

## Standalone use (no laptop)

1. Do the one-time setup below (steps 1–2b: install, select as mock location
   app, optionally grant Health Connect).
2. In the app, pick a route: **Draw route on map** (tap waypoints, then *Use
   route*; needs internet for map tiles) or **Import GPX file**.
3. Set speed / stride, tick **Write steps to Health Connect** if wanted, and tap
   **Start walking**. It runs in a foreground service, so it keeps going with
   the screen off. Tap **Stop** to end it.

The simulation (stride, speed noise, cadence, GPS jitter, pauses at turns) is
a Kotlin port of the script's, in `Simulator.kt`.

The rest of this document covers the laptop-driven mode.

It does three things:
1. Registers itself as a test location provider for `GPS_PROVIDER` and
   `NETWORK_PROVIDER` (via `LocationManager.addTestProvider`) — this only
   takes effect once you select it as the device's **mock location app**.
2. Runs a foreground service with a `BroadcastReceiver` listening for the
   `com.stepsim.MOCK_LOCATION` intent the script sends, and forwards each
   `lat`/`lng` fix straight into the test provider.
3. Listens for the `com.stepsim.STEPS` intent the script's `--write-health`
   flag sends, and inserts a matching `StepsRecord`/`DistanceRecord` into
   Health Connect via `HealthConnectClient.insertRecords(...)` — requires
   granting Health Connect access from the app first (step 2 below).

This matches the script's built-in defaults (`--broadcast-action
com.stepsim.MOCK_LOCATION`, `--health-broadcast-action com.stepsim.STEPS`)
and package (`com.stepsim.companion`) — no extra flags needed for the common
case.

## Setup

### 1. Build and install

Open this folder (`android_companion_app/`) as a project in Android Studio
and hit **Run** with your device selected, or from the command line:

```bash
cd android_companion_app
./gradlew installDebug   # or: gradlew.bat installDebug on Windows,
                          # or just build+run from Android Studio if you don't have a wrapper jar yet
```

(If there's no Gradle wrapper jar checked in, opening the project in Android
Studio once will offer to generate it — that's the easiest path.)

### 2. Select it as the mock location app

In the app, tap **Open Developer options**, then:

Settings → System → Developer options → **Select mock location app** →
choose **StepSim Companion**.

(If Developer options isn't unlocked yet: Settings → About phone → tap
**Build number** 7 times.)

### 2b. (Optional) Grant Health Connect access

Only needed if you'll run with `--write-health`. Tap **Grant Health Connect
access** in the app. If Health Connect isn't installed/updated on the
device, this opens its Play Store listing instead — install/update it, then
tap the button again to get the actual permission prompt (write access to
Steps and Distance).

### 3. Start the service

Back in the app, tap **Start mock location service**. The status line and
a persistent notification confirm it's listening. If you see "ERROR: not
selected as mock location app", go back to step 2 — Android silently throws
`SecurityException` on `addTestProvider` until that's set.

### 4. Run the simulator against it

```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live \
  --method broadcast --device <adb-serial> \
  --receiver-package com.stepsim.companion
```

`--broadcast-action` doesn't need to be passed — it already defaults to
`com.stepsim.MOCK_LOCATION`, which is what this app listens for. Passing
`--receiver-package com.stepsim.companion` just restricts the broadcast to
this app instead of every registered receiver on the device.

You should see the app's status line update with each fix (`Fix #N: lat=...
lng=...`), and the target app under test should see its location move along
the simulated route.

Add `--write-health` to also write steps into Health Connect (requires step
2b above):

```bash
python3 step_route_simulator.py --gpx morning_walk.gpx --live \
  --method broadcast --device <adb-serial> \
  --receiver-package com.stepsim.companion --write-health
```

Every `--bucket-seconds` (default 30s) the app inserts a `StepsRecord` +
`DistanceRecord` for the interval just played and updates its notification
with a running total. If Health Connect access wasn't granted, the
notification will say "Health write failed: ..." instead.

### Stopping

Tap **Stop service** in the app, or just close/force-stop it — the test
provider is torn down in `onDestroy`.

## Notes / caveats

- This only affects the device's `LocationManager` fused providers, not
  raw GNSS chip output — apps that read location the normal way (through
  `FusedLocationProviderClient`/`LocationManager`) will see it; apps doing
  something unusual (e.g. reading raw GNSS measurements) won't.
- The `--write-health` steps land in **Health Connect**, not directly in any
  particular fitness app. Whether a given app shows them depends on that
  app reading from Health Connect and having sync enabled — check its
  settings for a "connected apps"/"data sources" option if steps don't show
  up. Apps that keep their own private step store instead of using Health
  Connect won't pick them up at all.
- Only use this against apps/accounts you control, per the top-level
  [README.md](../README.md)'s usage note.
