#!/usr/bin/env python3
"""
step_route_simulator.py
========================

A single-file Android developer tool for simulating a walking route: it turns a
GPX track / list of waypoints / hand-drawn map route into a humanized stream of
GPS fixes and matching step-count data, for testing location-aware and
Health-Connect-integrated Android apps.

Typical uses
------------
    # Summarize a GPX route without touching ADB or the filesystem
    python3 step_route_simulator.py --gpx morning_walk.gpx --dry-run

    # Draw a route on a map, then stream it live to a connected emulator/device
    python3 step_route_simulator.py --draw-map --live

    # Stream a route described as raw waypoints
    python3 step_route_simulator.py --waypoints "40.7128,-74.0060 | 40.7130,-74.0055" --live

    # Generate a Health Connect StepsRecord/DistanceRecord JSON payload
    python3 step_route_simulator.py --gpx morning_walk.gpx --export-health steps.json

Only the Python standard library is used (argparse, math, json, time,
subprocess, xml.etree.ElementTree, http.server, webbrowser, ...) so the file
runs anywhere Python 3.8+ is available -- no pip install required.
"""

from __future__ import annotations

import argparse
import http.server
import json
import math
import os
import random
import socket
import subprocess
import sys
import threading
import time
import webbrowser
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import List, Optional, Tuple

EARTH_RADIUS_M = 6371000.0
DEFAULT_STRIDE_M = 0.78
DEFAULT_SPEED_KMH = 4.5
DEFAULT_SPEED_NOISE_KMH = 0.6
DEFAULT_CADENCE_MIN = 105
DEFAULT_CADENCE_MAX = 125
DEFAULT_PAUSE_MIN_S = 5.0
DEFAULT_PAUSE_MAX_S = 15.0
DEFAULT_GPS_JITTER_M = 1.2


# --------------------------------------------------------------------------- #
# Geometry helpers
# --------------------------------------------------------------------------- #

def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance between two lat/lon points, in meters."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (math.sin(dphi / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2)
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dlambda = math.radians(lon2 - lon1)
    x = math.sin(dlambda) * math.cos(phi2)
    y = (math.cos(phi1) * math.sin(phi2)
         - math.sin(phi1) * math.cos(phi2) * math.cos(dlambda))
    return (math.degrees(math.atan2(x, y)) + 360) % 360


def interpolate_point(lat1: float, lon1: float, lat2: float, lon2: float, f: float) -> Tuple[float, float]:
    """Linear interpolation, adequate for the short segment lengths (<< 1km) used here."""
    return lat1 + (lat2 - lat1) * f, lon1 + (lon2 - lon1) * f


# --------------------------------------------------------------------------- #
# Route input parsing
# --------------------------------------------------------------------------- #

def parse_gpx(path: str) -> List[Tuple[float, float]]:
    """Extract an ordered list of (lat, lon) waypoints from a GPX file.

    Handles both <trkpt> (track points, e.g. Strava/Google Maps exports) and
    <rtept>/<wpt> (route/waypoints, e.g. GPX Studio) elements, with or
    without an XML namespace.
    """
    tree = ET.parse(path)
    root = tree.getroot()

    def strip_ns(tag: str) -> str:
        return tag.split('}', 1)[1] if '}' in tag else tag

    points: List[Tuple[float, float]] = []
    for elem in root.iter():
        tag = strip_ns(elem.tag)
        if tag in ("trkpt", "rtept", "wpt"):
            lat = elem.get("lat")
            lon = elem.get("lon")
            if lat is not None and lon is not None:
                points.append((float(lat), float(lon)))

    if not points:
        raise ValueError(f"No <trkpt>/<rtept>/<wpt> elements with lat/lon found in {path}")
    return points


def parse_waypoints(spec: str) -> List[Tuple[float, float]]:
    """Parse a '--waypoints' CLI string: 'lat1,lng1 | lat2,lng2 | ...'."""
    points: List[Tuple[float, float]] = []
    for chunk in spec.split("|"):
        chunk = chunk.strip()
        if not chunk:
            continue
        parts = [p.strip() for p in chunk.split(",")]
        if len(parts) != 2:
            raise ValueError(f"Malformed waypoint '{chunk}', expected 'lat,lng'")
        lat, lon = float(parts[0]), float(parts[1])
        points.append((lat, lon))
    if len(points) < 2:
        raise ValueError("Need at least two waypoints to form a route")
    return points


def write_gpx(path: str, points: List[Tuple[float, float]], name: str = "Simulated Route") -> None:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="step_route_simulator.py" xmlns="http://www.topografix.com/GPX/1/1">',
        f'  <trk><name>{name}</name><trkseg>',
    ]
    for lat, lon in points:
        lines.append(f'    <trkpt lat="{lat:.7f}" lon="{lon:.7f}"></trkpt>')
    lines.append('  </trkseg></trk>')
    lines.append('</gpx>')
    with open(path, "w") as f:
        f.write("\n".join(lines))


# --------------------------------------------------------------------------- #
# Route builder (interactive Leaflet.js map)
# --------------------------------------------------------------------------- #

ROUTE_BUILDER_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Step Route Builder</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<style>
  html, body { margin: 0; height: 100%; font-family: -apple-system, Arial, sans-serif; }
  #map { position: absolute; top: 0; bottom: 0; left: 0; right: 320px; }
  #panel { position: absolute; top: 0; bottom: 0; right: 0; width: 320px;
           box-sizing: border-box; padding: 16px; background: #1e1e24; color: #eee;
           overflow-y: auto; }
  h2 { font-size: 16px; margin-top: 0; }
  label { display: block; margin-top: 12px; font-size: 13px; color: #bbb; }
  input[type=range] { width: 100%; }
  .val { color: #7dd3fc; font-weight: bold; }
  button { width: 100%; margin-top: 16px; padding: 10px; border: none; border-radius: 6px;
           background: #38bdf8; color: #06202b; font-weight: bold; cursor: pointer; }
  button:hover { background: #7dd3fc; }
  button.secondary { background: #444; color: #eee; }
  #stats { margin-top: 16px; font-size: 13px; line-height: 1.6; color: #ccc; }
  #status { margin-top: 12px; font-size: 12px; color: #f59e0b; min-height: 32px; }
</style>
</head>
<body>
<div id="map"></div>
<div id="panel">
  <h2>Step Route Builder</h2>
  <div>Click the map to add waypoints. Click "Undo" to remove the last point.</div>

  <label>Walking speed: <span class="val" id="speedVal">4.5</span> km/h
    <input type="range" id="speed" min="2" max="8" step="0.1" value="4.5">
  </label>
  <label>Stride length: <span class="val" id="strideVal">0.78</span> m
    <input type="range" id="stride" min="0.5" max="1.1" step="0.01" value="0.78">
  </label>

  <button id="undoBtn" class="secondary">Undo last point</button>
  <button id="clearBtn" class="secondary">Clear route</button>
  <button id="exportBtn">Export route to CLI</button>

  <div id="stats">No route yet.</div>
  <div id="status"></div>
</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
  var map = L.map('map').setView([37.7749, -122.4194], 15);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);

  var points = [];
  var markers = [];
  var line = L.polyline([], {color: '#38bdf8', weight: 4}).addTo(map);

  // Try to center on the user's current location for convenience.
  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition(function(pos) {
      map.setView([pos.coords.latitude, pos.coords.longitude], 16);
    }, function() { /* ignore errors, keep default view */ });
  }

  function haversine(a, b) {
    var R = 6371000;
    var dLat = (b[0]-a[0]) * Math.PI/180;
    var dLon = (b[1]-a[1]) * Math.PI/180;
    var lat1 = a[0] * Math.PI/180, lat2 = b[0] * Math.PI/180;
    var h = Math.sin(dLat/2)**2 + Math.cos(lat1)*Math.cos(lat2)*Math.sin(dLon/2)**2;
    return 2 * R * Math.asin(Math.sqrt(h));
  }

  function refreshStats() {
    var dist = 0;
    for (var i = 1; i < points.length; i++) dist += haversine(points[i-1], points[i]);
    var stride = parseFloat(document.getElementById('stride').value);
    var speed = parseFloat(document.getElementById('speed').value);
    var steps = Math.round(dist / stride);
    var hours = (dist / 1000) / speed;
    var mins = Math.round(hours * 60);
    document.getElementById('stats').innerHTML =
      points.length + ' waypoints<br>' +
      'Distance: ' + (dist/1000).toFixed(2) + ' km<br>' +
      'Est. steps: ' + steps + '<br>' +
      'Est. duration: ' + mins + ' min';
  }

  map.on('click', function(e) {
    var pt = [e.latlng.lat, e.latlng.lng];
    points.push(pt);
    var m = L.circleMarker(pt, {radius: 5, color: '#f59e0b'}).addTo(map);
    markers.push(m);
    line.setLatLngs(points);
    refreshStats();
  });

  document.getElementById('speed').addEventListener('input', function() {
    document.getElementById('speedVal').textContent = this.value;
    refreshStats();
  });
  document.getElementById('stride').addEventListener('input', function() {
    document.getElementById('strideVal').textContent = this.value;
    refreshStats();
  });

  document.getElementById('undoBtn').addEventListener('click', function() {
    points.pop();
    var m = markers.pop();
    if (m) map.removeLayer(m);
    line.setLatLngs(points);
    refreshStats();
  });

  document.getElementById('clearBtn').addEventListener('click', function() {
    points = [];
    markers.forEach(function(m) { map.removeLayer(m); });
    markers = [];
    line.setLatLngs(points);
    refreshStats();
  });

  document.getElementById('exportBtn').addEventListener('click', function() {
    if (points.length < 2) {
      document.getElementById('status').textContent = 'Add at least 2 waypoints first.';
      return;
    }
    var payload = {
      waypoints: points,
      speed_kmh: parseFloat(document.getElementById('speed').value),
      stride_m: parseFloat(document.getElementById('stride').value)
    };
    document.getElementById('status').textContent = 'Sending route to CLI...';
    fetch('/export', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    }).then(function(r) {
      if (r.ok) {
        document.getElementById('status').textContent =
          'Route sent! You can close this tab and return to the terminal.';
      } else {
        document.getElementById('status').textContent = 'Export failed: server error.';
      }
    }).catch(function(err) {
      document.getElementById('status').textContent = 'Export failed: ' + err;
    });
  });
</script>
</body>
</html>
"""


class _RouteBuilderState:
    def __init__(self):
        self.result: Optional[dict] = None
        self.event = threading.Event()


def _make_handler(state: _RouteBuilderState):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # keep the CLI output clean

        def do_GET(self):
            if self.path in ("/", "/index.html"):
                body = ROUTE_BUILDER_HTML.encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            else:
                self.send_response(404)
                self.end_headers()

        def do_POST(self):
            if self.path == "/export":
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                try:
                    data = json.loads(raw.decode("utf-8"))
                    state.result = data
                    state.event.set()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"ok": true}')
                except Exception as exc:  # noqa: BLE001
                    self.send_response(400)
                    self.end_headers()
                    self.wfile.write(str(exc).encode("utf-8"))
            else:
                self.send_response(404)
                self.end_headers()

    return Handler


def run_draw_map(port: int = 8934, timeout_s: float = 600.0) -> dict:
    """Serve the embedded Leaflet route builder and block until a route is exported."""
    state = _RouteBuilderState()
    handler = _make_handler(state)
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), handler) as httpd:
        url = f"http://127.0.0.1:{port}/"
        server_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        server_thread.start()
        print(f"[draw-map] Route builder running at {url}")
        print("[draw-map] Draw your route in the browser, then click 'Export route to CLI'.")
        try:
            webbrowser.open(url)
        except Exception:
            pass

        got = state.event.wait(timeout=timeout_s)
        httpd.shutdown()

        if not got:
            raise TimeoutError("Timed out waiting for a route to be drawn/exported in the browser.")
        return state.result


# --------------------------------------------------------------------------- #
# Motion simulation
# --------------------------------------------------------------------------- #

@dataclass
class Fix:
    lat: float
    lon: float
    t_offset_s: float          # seconds since simulation start
    cumulative_m: float
    step_index: int            # cumulative step count at this fix
    is_pause: bool = False


@dataclass
class RouteStats:
    total_distance_m: float
    total_steps: int
    total_duration_s: float
    waypoint_count: int


def densify_route(waypoints: List[Tuple[float, float]], max_gap_m: float = 5.0) -> List[Tuple[float, float, bool]]:
    """Insert intermediate points along each segment so no gap exceeds max_gap_m.

    Returns a list of (lat, lon, is_original_waypoint) so turn/intersection
    nodes can be identified later for pause insertion.
    """
    dense: List[Tuple[float, float, bool]] = [(waypoints[0][0], waypoints[0][1], True)]
    for i in range(1, len(waypoints)):
        lat1, lon1 = waypoints[i - 1]
        lat2, lon2 = waypoints[i]
        seg_len = haversine_m(lat1, lon1, lat2, lon2)
        steps = max(1, int(seg_len // max_gap_m))
        for s in range(1, steps + 1):
            f = s / steps
            lat, lon = interpolate_point(lat1, lon1, lat2, lon2, f)
            dense.append((lat, lon, s == steps))
    return dense


def simulate_walk(
    waypoints: List[Tuple[float, float]],
    stride_m: float = DEFAULT_STRIDE_M,
    speed_kmh: float = DEFAULT_SPEED_KMH,
    speed_noise_kmh: float = DEFAULT_SPEED_NOISE_KMH,
    cadence_min: float = DEFAULT_CADENCE_MIN,
    cadence_max: float = DEFAULT_CADENCE_MAX,
    pause_min_s: float = DEFAULT_PAUSE_MIN_S,
    pause_max_s: float = DEFAULT_PAUSE_MAX_S,
    gps_jitter_m: float = DEFAULT_GPS_JITTER_M,
    pause_probability_at_turn: float = 0.6,
    seed: Optional[int] = None,
) -> Tuple[List[Fix], RouteStats]:
    """Walk along `waypoints`, emitting one Fix per simulated footstep.

    Speed varies around `speed_kmh` (Gaussian noise), cadence is drawn per-step
    from [cadence_min, cadence_max] spm, and turn nodes have a chance of a
    short pause to emulate waiting to cross a street.
    """
    rng = random.Random(seed)
    dense_points = densify_route(waypoints, max_gap_m=max(1.0, stride_m))

    total_distance_m = sum(
        haversine_m(dense_points[i - 1][0], dense_points[i - 1][1], dense_points[i][0], dense_points[i][1])
        for i in range(1, len(dense_points))
    )

    fixes: List[Fix] = []
    t = 0.0
    cumulative_m = 0.0
    step_index = 0

    # Walk the dense polyline in stride-length increments, resampling speed
    # noise per step and inserting pauses at waypoint (turn) nodes.
    seg_idx = 1
    pos = dense_points[0]
    fixes.append(Fix(pos[0], pos[1], t, 0.0, 0, is_pause=False))

    while seg_idx < len(dense_points):
        target = dense_points[seg_idx]
        seg_len = haversine_m(pos[0], pos[1], target[0], target[1])

        if seg_len < 1e-6:
            seg_idx += 1
            continue

        step_len = min(stride_m, seg_len)
        f = step_len / seg_len
        new_lat, new_lon = interpolate_point(pos[0], pos[1], target[0], target[1], f)

        # Humanized pace for this step.
        step_speed_kmh = max(0.5, rng.gauss(speed_kmh, speed_noise_kmh))
        step_speed_ms = step_speed_kmh * 1000 / 3600
        cadence_spm = rng.uniform(cadence_min, cadence_max)
        cadence_dt = 60.0 / cadence_spm
        # Blend cadence-implied and speed-implied timing so both constraints
        # are respected on average.
        dist_dt = step_len / step_speed_ms if step_speed_ms > 0 else cadence_dt
        dt = (cadence_dt + dist_dt) / 2.0

        # Small GPS jitter so consecutive fixes aren't perfectly on-line.
        jitter_lat = rng.uniform(-gps_jitter_m, gps_jitter_m) / 111320.0
        jitter_lon = rng.uniform(-gps_jitter_m, gps_jitter_m) / (111320.0 * math.cos(math.radians(new_lat)) + 1e-9)

        t += dt
        cumulative_m += step_len
        step_index += 1
        fixes.append(Fix(new_lat + jitter_lat, new_lon + jitter_lon, t, cumulative_m, step_index))

        pos = (new_lat, new_lon)

        reached_node = f >= 1.0 - 1e-9
        if reached_node:
            is_waypoint_node = dense_points[seg_idx][2]
            seg_idx += 1
            if is_waypoint_node and rng.random() < pause_probability_at_turn:
                pause_s = rng.uniform(pause_min_s, pause_max_s)
                t += pause_s
                fixes.append(Fix(pos[0], pos[1], t, cumulative_m, step_index, is_pause=True))

    stats = RouteStats(
        total_distance_m=cumulative_m,
        total_steps=step_index,
        total_duration_s=t,
        waypoint_count=len(waypoints),
    )
    return fixes, stats


# --------------------------------------------------------------------------- #
# ADB live streaming
# --------------------------------------------------------------------------- #

def adb_base_cmd(device: Optional[str]) -> List[str]:
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    return cmd


def check_adb_available() -> None:
    try:
        subprocess.run(["adb", "version"], check=True, capture_output=True, text=True)
    except FileNotFoundError:
        print("ERROR: 'adb' was not found on PATH. Install the Android Platform Tools first.",
              file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as exc:
        print(f"ERROR: 'adb version' failed: {exc}", file=sys.stderr)
        sys.exit(1)


def send_fix_emulator(lat: float, lon: float, device: Optional[str]) -> None:
    """Set the location of a running Android emulator via its console command."""
    cmd = adb_base_cmd(device) + ["emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"]
    subprocess.run(cmd, check=False, capture_output=True, text=True)


def send_fix_broadcast(lat: float, lon: float, device: Optional[str],
                        action: str, receiver_package: Optional[str]) -> None:
    """Broadcast a mock-location intent for a companion receiver app on a real device.

    ADB alone cannot write to a real device's LocationManager -- this requires a
    small companion app (or a tool such as Lockito / GPS JoyStick) with a
    BroadcastReceiver registered for `action`, which calls
    `LocationManager.setTestProviderLocation(...)` upon receipt.
    """
    cmd = adb_base_cmd(device) + [
        "shell", "am", "broadcast",
        "-a", action,
        "--ef", "lat", f"{lat:.7f}",
        "--ef", "lng", f"{lon:.7f}",
        "--el", "time", str(int(time.time() * 1000)),
    ]
    if receiver_package:
        cmd += ["-p", receiver_package]
    subprocess.run(cmd, check=False, capture_output=True, text=True)


def stream_live(fixes: List[Fix], method: str, device: Optional[str],
                 broadcast_action: str, receiver_package: Optional[str],
                 speed_multiplier: float = 1.0) -> None:
    check_adb_available()
    print(f"[live] Streaming {len(fixes)} fixes via '{method}' "
          f"(speed x{speed_multiplier})... Ctrl+C to stop.")
    prev_t = 0.0
    try:
        for i, fx in enumerate(fixes):
            wait = max(0.0, (fx.t_offset_s - prev_t) / max(speed_multiplier, 1e-6))
            if wait > 0:
                time.sleep(wait)
            prev_t = fx.t_offset_s

            if method == "emulator":
                send_fix_emulator(fx.lat, fx.lon, device)
            else:
                send_fix_broadcast(fx.lat, fx.lon, device, broadcast_action, receiver_package)

            tag = "PAUSE" if fx.is_pause else "STEP "
            print(f"\r[live] {tag} #{i+1}/{len(fixes)}  "
                  f"lat={fx.lat:.6f} lon={fx.lon:.6f}  "
                  f"steps={fx.step_index}  t={fx.t_offset_s:6.1f}s", end="", flush=True)
        print("\n[live] Route complete.")
    except KeyboardInterrupt:
        print("\n[live] Interrupted by user.")


# --------------------------------------------------------------------------- #
# Health Connect payload export
# --------------------------------------------------------------------------- #

def export_health_connect_json(
    fixes: List[Fix],
    stats: RouteStats,
    out_path: str,
    start_time: Optional[datetime] = None,
    bucket_seconds: float = 30.0,
) -> None:
    """Write a JSON payload shaped like Health Connect StepsRecord/DistanceRecord
    insert requests, bucketed into `bucket_seconds`-wide intervals.

    This produces the *data*, not a live insert -- actually writing it into
    Health Connect on-device requires a companion app using the Health Connect
    Jetpack client (androidx.health.connect.client) to read this file and call
    `HealthConnectClient.insertRecords(...)`.
    """
    if start_time is None:
        start_time = datetime.now(timezone.utc)

    steps_records = []
    distance_records = []

    bucket_start_idx = 0
    bucket_start_t = 0.0
    bucket_start_steps = 0
    bucket_start_dist = 0.0

    def flush_bucket(end_idx: int, end_t: float, end_steps: int, end_dist: float):
        if end_idx <= bucket_start_idx:
            return
        step_count = end_steps - bucket_start_steps
        distance_m = end_dist - bucket_start_dist
        if step_count <= 0 and distance_m <= 0:
            return
        bucket_start_dt = start_time + timedelta(seconds=bucket_start_t)
        bucket_end_dt = start_time + timedelta(seconds=end_t)
        steps_records.append({
            "recordType": "StepsRecord",
            "startTime": bucket_start_dt.isoformat(),
            "endTime": bucket_end_dt.isoformat(),
            "count": step_count,
        })
        distance_records.append({
            "recordType": "DistanceRecord",
            "startTime": bucket_start_dt.isoformat(),
            "endTime": bucket_end_dt.isoformat(),
            "distanceMeters": round(distance_m, 3),
        })

    for i, fx in enumerate(fixes):
        if fx.t_offset_s - bucket_start_t >= bucket_seconds:
            flush_bucket(i, fx.t_offset_s, fx.step_index, fx.cumulative_m)
            bucket_start_idx = i
            bucket_start_t = fx.t_offset_s
            bucket_start_steps = fx.step_index
            bucket_start_dist = fx.cumulative_m

    last = fixes[-1]
    flush_bucket(len(fixes), last.t_offset_s, last.step_index, last.cumulative_m)

    payload = {
        "generatedBy": "step_route_simulator.py",
        "summary": {
            "totalDistanceMeters": round(stats.total_distance_m, 2),
            "totalSteps": stats.total_steps,
            "totalDurationSeconds": round(stats.total_duration_s, 1),
            "startTime": start_time.isoformat(),
            "endTime": (start_time + timedelta(seconds=stats.total_duration_s)).isoformat(),
        },
        "stepsRecords": steps_records,
        "distanceRecords": distance_records,
    }

    with open(out_path, "w") as f:
        json.dump(payload, f, indent=2)

    print(f"[export-health] Wrote {len(steps_records)} StepsRecord + "
          f"{len(distance_records)} DistanceRecord buckets to {out_path}")


# --------------------------------------------------------------------------- #
# Dry-run summary
# --------------------------------------------------------------------------- #

def print_dry_run(stats: RouteStats, fixes: List[Fix], preview_n: int = 10) -> None:
    print("=" * 60)
    print("STEP ROUTE SIMULATOR -- DRY RUN SUMMARY")
    print("=" * 60)
    print(f"  Waypoints:        {stats.waypoint_count}")
    print(f"  Total distance:   {stats.total_distance_m/1000:.3f} km")
    print(f"  Estimated steps:  {stats.total_steps}")
    dur = timedelta(seconds=round(stats.total_duration_s))
    print(f"  Estimated time:   {dur} ({stats.total_duration_s:.1f}s)")
    pauses = sum(1 for f in fixes if f.is_pause)
    print(f"  Pause events:     {pauses}")
    print("-" * 60)
    print(f"  Coordinate stream preview (first {preview_n} of {len(fixes)} fixes):")
    print(f"  {'#':>5}  {'t(s)':>8}  {'lat':>11}  {'lon':>12}  {'steps':>6}  flag")
    for i, fx in enumerate(fixes[:preview_n]):
        flag = "PAUSE" if fx.is_pause else ""
        print(f"  {i:>5}  {fx.t_offset_s:8.1f}  {fx.lat:11.6f}  {fx.lon:12.6f}  {fx.step_index:6d}  {flag}")
    if len(fixes) > preview_n:
        print(f"  ... ({len(fixes) - preview_n} more fixes)")
    print("=" * 60)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="step_route_simulator.py",
        description="Simulate a humanized walking route: mock GPS + Health Connect step data.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    src = p.add_argument_group("Route input (choose one)")
    src.add_argument("--gpx", metavar="FILE", help="Path to a GPX file (trkpt/rtept/wpt).")
    src.add_argument("--waypoints", metavar="STR",
                      help='Inline waypoints, e.g. "40.71,-74.00 | 40.72,-74.01"')
    src.add_argument("--draw-map", action="store_true",
                      help="Open an interactive Leaflet map to draw the route.")
    src.add_argument("--draw-map-port", type=int, default=8934,
                      help="Local port for the route-builder web page.")

    motion = p.add_argument_group("Motion / cadence tuning")
    motion.add_argument("--stride", type=float, default=DEFAULT_STRIDE_M,
                         help="Stride length in meters.")
    motion.add_argument("--speed", type=float, default=DEFAULT_SPEED_KMH,
                         help="Average walking speed in km/h.")
    motion.add_argument("--speed-noise", type=float, default=DEFAULT_SPEED_NOISE_KMH,
                         help="Std-dev of walking speed noise in km/h.")
    motion.add_argument("--cadence-min", type=float, default=DEFAULT_CADENCE_MIN,
                         help="Minimum step cadence, steps/min.")
    motion.add_argument("--cadence-max", type=float, default=DEFAULT_CADENCE_MAX,
                         help="Maximum step cadence, steps/min.")
    motion.add_argument("--pause-min", type=float, default=DEFAULT_PAUSE_MIN_S,
                         help="Minimum pause duration at a turn node, seconds.")
    motion.add_argument("--pause-max", type=float, default=DEFAULT_PAUSE_MAX_S,
                         help="Maximum pause duration at a turn node, seconds.")
    motion.add_argument("--seed", type=int, default=None,
                         help="Random seed, for reproducible simulations.")

    modes = p.add_argument_group("Execution modes")
    modes.add_argument("--live", action="store_true",
                        help="Stream fixes to a connected Android device/emulator via ADB.")
    modes.add_argument("--export-health", metavar="FILE",
                        help="Write a Health Connect StepsRecord/DistanceRecord JSON payload.")
    modes.add_argument("--dry-run", action="store_true",
                        help="Print a summary only; no ADB calls or file writes.")

    live = p.add_argument_group("--live options")
    live.add_argument("--method", choices=["emulator", "broadcast"], default="emulator",
                       help="'emulator' uses `adb emu geo fix` (Android Studio emulator). "
                            "'broadcast' sends an intent for a companion mock-location "
                            "receiver app on a real device.")
    live.add_argument("--device", metavar="SERIAL", default=None,
                       help="Target device serial (see `adb devices`).")
    live.add_argument("--broadcast-action", default="com.stepsim.MOCK_LOCATION",
                       help="Intent action used with --method broadcast.")
    live.add_argument("--receiver-package", default=None,
                       help="Restrict the broadcast to a specific receiver package.")
    live.add_argument("--speed-multiplier", type=float, default=1.0,
                       help="Playback speed multiplier (2.0 = twice as fast as real time).")

    health = p.add_argument_group("--export-health options")
    health.add_argument("--start-time", default=None,
                         help="ISO-8601 start timestamp (default: now, UTC).")
    health.add_argument("--bucket-seconds", type=float, default=30.0,
                         help="Width of each StepsRecord/DistanceRecord interval.")

    return p


def resolve_waypoints(args: argparse.Namespace) -> Tuple[List[Tuple[float, float]], float, float]:
    """Returns (waypoints, speed_kmh, stride_m), pulling overrides from the map UI if used."""
    sources = [bool(args.gpx), bool(args.waypoints), bool(args.draw_map)]
    if sum(sources) == 0:
        print("ERROR: provide one of --gpx, --waypoints, or --draw-map.", file=sys.stderr)
        sys.exit(2)
    if sum(sources) > 1:
        print("ERROR: --gpx, --waypoints, and --draw-map are mutually exclusive.", file=sys.stderr)
        sys.exit(2)

    speed_kmh = args.speed
    stride_m = args.stride

    if args.gpx:
        waypoints = parse_gpx(args.gpx)
    elif args.waypoints:
        waypoints = parse_waypoints(args.waypoints)
    else:
        result = run_draw_map(port=args.draw_map_port)
        raw_points = result.get("waypoints", [])
        waypoints = [(float(p[0]), float(p[1])) for p in raw_points]
        if len(waypoints) < 2:
            print("ERROR: drawn route needs at least 2 waypoints.", file=sys.stderr)
            sys.exit(2)
        speed_kmh = float(result.get("speed_kmh", speed_kmh))
        stride_m = float(result.get("stride_m", stride_m))
        print(f"[draw-map] Received {len(waypoints)} waypoints "
              f"(speed={speed_kmh} km/h, stride={stride_m} m).")

    return waypoints, speed_kmh, stride_m


def main(argv: Optional[List[str]] = None) -> None:
    args = build_arg_parser().parse_args(argv)

    if not (args.live or args.export_health or args.dry_run):
        print("ERROR: choose at least one mode: --live, --export-health, and/or --dry-run.",
              file=sys.stderr)
        sys.exit(2)

    waypoints, speed_kmh, stride_m = resolve_waypoints(args)

    fixes, stats = simulate_walk(
        waypoints,
        stride_m=stride_m,
        speed_kmh=speed_kmh,
        speed_noise_kmh=args.speed_noise,
        cadence_min=args.cadence_min,
        cadence_max=args.cadence_max,
        pause_min_s=args.pause_min,
        pause_max_s=args.pause_max,
        seed=args.seed,
    )

    if args.dry_run:
        print_dry_run(stats, fixes)
        return  # dry-run never touches ADB or the filesystem

    if args.export_health:
        start_time = (datetime.fromisoformat(args.start_time)
                      if args.start_time else datetime.now(timezone.utc))
        export_health_connect_json(fixes, stats, args.export_health,
                                    start_time=start_time,
                                    bucket_seconds=args.bucket_seconds)

    if args.live:
        stream_live(fixes, method=args.method, device=args.device,
                    broadcast_action=args.broadcast_action,
                    receiver_package=args.receiver_package,
                    speed_multiplier=args.speed_multiplier)


if __name__ == "__main__":
    main()
