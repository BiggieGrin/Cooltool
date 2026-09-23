package com.stepsim.companion

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Random
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class Fix(
    val lat: Double,
    val lon: Double,
    val tOffsetS: Double,      // seconds since simulation start
    val cumulativeM: Double,
    val stepIndex: Int,        // cumulative step count at this fix
    val isPause: Boolean = false,
)

data class RouteStats(
    val totalDistanceM: Double,
    val totalSteps: Int,
    val totalDurationS: Double,
    val waypointCount: Int,
)

data class SimParams(
    val strideM: Double = 0.78,
    val speedKmh: Double = 4.5,
    val speedNoiseKmh: Double = 0.6,
    val cadenceMin: Double = 105.0,
    val cadenceMax: Double = 125.0,
    val pauseMinS: Double = 5.0,
    val pauseMaxS: Double = 15.0,
    val gpsJitterM: Double = 1.2,
    val pauseProbabilityAtTurn: Double = 0.6,
)

/** Kotlin port of the motion simulation in step_route_simulator.py. */
object Simulator {
    private const val EARTH_RADIUS_M = 6371000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dphi = Math.toRadians(lat2 - lat1)
        val dlambda = Math.toRadians(lon2 - lon1)
        val a = sin(dphi / 2) * sin(dphi / 2) +
            cos(phi1) * cos(phi2) * sin(dlambda / 2) * sin(dlambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun routeDistanceM(route: List<LatLon>): Double {
        var total = 0.0
        for (i in 1 until route.size) {
            total += haversineM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        }
        return total
    }

    /** Extracts (lat, lon) from <trkpt>/<rtept>/<wpt> elements, ignoring XML namespaces. */
    fun parseGpx(input: InputStream): List<LatLon> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val points = mutableListOf<LatLon>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) points += LatLon(lat, lon)
                }
            }
            event = parser.next()
        }
        require(points.isNotEmpty()) { "No <trkpt>/<rtept>/<wpt> elements with lat/lon found" }
        return points
    }

    private class DensePoint(val lat: Double, val lon: Double, val isWaypoint: Boolean)

    /** Inserts points so no gap exceeds [maxGapM]; flags the original waypoints (turn nodes). */
    private fun densify(waypoints: List<LatLon>, maxGapM: Double): List<DensePoint> {
        val dense = mutableListOf(DensePoint(waypoints[0].lat, waypoints[0].lon, true))
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]
            val b = waypoints[i]
            val segLen = haversineM(a.lat, a.lon, b.lat, b.lon)
            val steps = max(1, (segLen / maxGapM).toInt())
            for (s in 1..steps) {
                val f = s.toDouble() / steps
                dense += DensePoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, s == steps)
            }
        }
        return dense
    }

    /**
     * Walks along [waypoints], emitting one Fix per simulated footstep. Speed varies
     * (Gaussian noise), cadence is drawn per step, and turn nodes may get a short pause.
     */
    fun simulateWalk(
        waypoints: List<LatLon>,
        params: SimParams = SimParams(),
        seed: Long? = null,
    ): Pair<List<Fix>, RouteStats> {
        require(waypoints.size >= 2) { "Need at least two waypoints to form a route" }
        val rng = if (seed != null) Random(seed) else Random()
        val dense = densify(waypoints, max(1.0, params.strideM))

        val fixes = ArrayList<Fix>()
        var t = 0.0
        var cumulativeM = 0.0
        var stepIndex = 0
        var segIdx = 1
        var posLat = dense[0].lat
        var posLon = dense[0].lon
        fixes += Fix(posLat, posLon, t, 0.0, 0)

        while (segIdx < dense.size) {
            val target = dense[segIdx]
            val segLen = haversineM(posLat, posLon, target.lat, target.lon)
            if (segLen < 1e-6) {
                segIdx++
                continue
            }

            val stepLen = min(params.strideM, segLen)
            val f = stepLen / segLen
            val newLat = posLat + (target.lat - posLat) * f
            val newLon = posLon + (target.lon - posLon) * f

            val stepSpeedKmh = max(0.5, params.speedKmh + rng.nextGaussian() * params.speedNoiseKmh)
            val stepSpeedMs = stepSpeedKmh * 1000 / 3600
            val cadenceSpm = params.cadenceMin + rng.nextDouble() * (params.cadenceMax - params.cadenceMin)
            val cadenceDt = 60.0 / cadenceSpm
            val distDt = stepLen / stepSpeedMs
            val dt = (cadenceDt + distDt) / 2.0

            val jitterLat = (rng.nextDouble() * 2 - 1) * params.gpsJitterM / 111320.0
            val jitterLon = (rng.nextDouble() * 2 - 1) * params.gpsJitterM /
                (111320.0 * cos(Math.toRadians(newLat)) + 1e-9)

            t += dt
            cumulativeM += stepLen
            stepIndex++
            fixes += Fix(newLat + jitterLat, newLon + jitterLon, t, cumulativeM, stepIndex)

            posLat = newLat
            posLon = newLon

            if (f >= 1.0 - 1e-9) {
                val wasWaypoint = dense[segIdx].isWaypoint
                segIdx++
                if (wasWaypoint && rng.nextDouble() < params.pauseProbabilityAtTurn) {
                    t += params.pauseMinS + rng.nextDouble() * (params.pauseMaxS - params.pauseMinS)
                    fixes += Fix(posLat, posLon, t, cumulativeM, stepIndex, isPause = true)
                }
            }
        }

        return fixes to RouteStats(cumulativeM, stepIndex, t, waypoints.size)
    }
}
