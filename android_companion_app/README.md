# StepSim Companion

A minimal Android app that lets `step_route_simulator.py --live --method
broadcast` drive a **real device's** location, not just the emulator.

It does two things:
1. Registers itself as a test location provider for `GPS_PROVIDER` and
   `NETWORK_PROVIDER` (via `LocationManager.addTestProvider`) — this only
   takes effect once you select it as the device's **mock location app**.
2. Runs a foreground service with a `BroadcastReceiver` listening for the
   `com.stepsim.MOCK_LOCATION` intent the script sends, and forwards each
   `lat`/`lng` fix straight into the test provider.

This matches the script's built-in default (`--broadcast-action
com.stepsim.MOCK_LOCATION`) and package (`com.stepsim.companion`) — no extra
flags needed for the common case.

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

### Stopping

Tap **Stop service** in the app, or just close/force-stop it — the test
provider is torn down in `onDestroy`.

## Notes / caveats

- This only affects the device's `LocationManager` fused providers, not
  raw GNSS chip output — apps that read location the normal way (through
  `FusedLocationProviderClient`/`LocationManager`) will see it; apps doing
  something unusual (e.g. reading raw GNSS measurements) won't.
- Only use this against apps/accounts you control, per the top-level
  [README.md](../README.md)'s usage note.
