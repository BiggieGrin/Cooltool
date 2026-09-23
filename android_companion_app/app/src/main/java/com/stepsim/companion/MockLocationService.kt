package com.stepsim.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Foreground service that:
 *  1. Registers this app as a test ("mock") location provider for
 *     GPS_PROVIDER and NETWORK_PROVIDER. This only succeeds once the user has
 *     selected this app under Settings > Developer options > Select mock
 *     location app -- otherwise LocationManager throws SecurityException.
 *  2. Listens for the broadcast that step_route_simulator.py's `--method
 *     broadcast` mode sends (default action: com.stepsim.MOCK_LOCATION, with
 *     float extras "lat" / "lng") and forwards each fix to the test provider
 *     via LocationManager.setTestProviderLocation(...).
 *  3. Listens for the broadcast `--write-health` sends (default action:
 *     com.stepsim.STEPS, with "steps"/"start_millis"/"end_millis"/"distance_m"
 *     extras) and inserts a matching StepsRecord/DistanceRecord bucket into
 *     Health Connect via HealthConnectClient.insertRecords(...). Requires
 *     Health Connect access to have been granted from MainActivity first.
 *
 * Keep this service running (don't force-stop the app) for the whole
 * duration of a `--live --method broadcast` run.
 */
class MockLocationService : Service() {

    companion object {
        const val CHANNEL_ID = "mock_location_channel"
        const val NOTIF_ID = 1

        const val ACTION_STATUS_UPDATE = "com.stepsim.companion.STATUS_UPDATE"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_COUNT = "count"

        const val EXTRA_BROADCAST_ACTION = "broadcast_action"
        const val DEFAULT_BROADCAST_ACTION = "com.stepsim.MOCK_LOCATION"

        const val EXTRA_HEALTH_BROADCAST_ACTION = "health_broadcast_action"
        const val DEFAULT_HEALTH_BROADCAST_ACTION = "com.stepsim.STEPS"

        // On-device route playback (no laptop): MainActivity sends these.
        const val ACTION_START_ROUTE = "com.stepsim.companion.START_ROUTE"
        const val ACTION_STOP_ROUTE = "com.stepsim.companion.STOP_ROUTE"
        const val EXTRA_ROUTE_LATS = "route_lats"
        const val EXTRA_ROUTE_LONS = "route_lons"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_STRIDE_M = "stride_m"
        const val EXTRA_WRITE_HEALTH = "write_health"
        const val EXTRA_STEPS = "steps"
        const val EXTRA_ROUTE_DONE = "route_done"
        private const val HEALTH_BUCKET_SECONDS = 30.0

        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }

    private lateinit var locationManager: LocationManager
    private var fixCount = 0
    private var broadcastAction = DEFAULT_BROADCAST_ACTION
    private var receiverRegistered = false

    private var healthBroadcastAction = DEFAULT_HEALTH_BROADCAST_ACTION
    private var healthReceiverRegistered = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routeJob: Job? = null
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(applicationContext) }

    private val fixReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lat = intent.getFloatExtra("lat", Float.NaN)
            val lng = intent.getFloatExtra("lng", Float.NaN)
            if (lat.isNaN() || lng.isNaN()) return
            pushFix(lat.toDouble(), lng.toDouble())
        }
    }

    // Handles step_route_simulator.py's --write-health buckets: one broadcast per
    // --bucket-seconds interval, stamped with the real wall-clock time it was sent at.
    private val healthReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val steps = intent.getIntExtra("steps", -1)
            val startMillis = intent.getLongExtra("start_millis", -1L)
            val endMillis = intent.getLongExtra("end_millis", -1L)
            val distanceM = intent.getFloatExtra("distance_m", -1f)
            if (steps <= 0 || startMillis < 0 || endMillis <= startMillis) return
            writeHealthBucket(steps, startMillis, endMillis, distanceM)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        broadcastAction = intent?.getStringExtra(EXTRA_BROADCAST_ACTION) ?: broadcastAction
        healthBroadcastAction = intent?.getStringExtra(EXTRA_HEALTH_BROADCAST_ACTION) ?: healthBroadcastAction

        startForeground(NOTIF_ID, buildNotification("Setting up mock providers..."))
        val ok = setUpTestProviders()

        if (!receiverRegistered) {
            val filter = IntentFilter(broadcastAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(fixReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(fixReceiver, filter)
            }
            receiverRegistered = true
        }

        if (!healthReceiverRegistered) {
            val healthFilter = IntentFilter(healthBroadcastAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(healthReceiver, healthFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(healthReceiver, healthFilter)
            }
            healthReceiverRegistered = true
        }

        updateNotification(
            if (ok) "Waiting for fixes on $broadcastAction"
            else "ERROR: not selected as mock location app (Developer options)"
        )

        when (intent?.action) {
            ACTION_START_ROUTE -> if (ok) startRoute(intent)
            ACTION_STOP_ROUTE -> {
                routeJob?.cancel()
                updateNotification("Route stopped. Waiting for fixes on $broadcastAction")
            }
        }
        return START_STICKY
    }

    /** Plays a route entirely on-device: simulate the walk, then feed fixes/steps in real time. */
    private fun startRoute(intent: Intent) {
        val lats = intent.getDoubleArrayExtra(EXTRA_ROUTE_LATS) ?: return
        val lons = intent.getDoubleArrayExtra(EXTRA_ROUTE_LONS) ?: return
        if (lats.size < 2 || lats.size != lons.size) return
        val route = lats.indices.map { LatLon(lats[it], lons[it]) }
        val defaults = SimParams()
        val params = defaults.copy(
            speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, defaults.speedKmh),
            strideM = intent.getDoubleExtra(EXTRA_STRIDE_M, defaults.strideM),
        )
        val writeHealth = intent.getBooleanExtra(EXTRA_WRITE_HEALTH, false)

        routeJob?.cancel()
        routeJob = serviceScope.launch {
            val (fixes, _) = Simulator.simulateWalk(route, params)

            var prevT = 0.0
            var lastSteps = 0
            var lastDist = 0.0
            var bucketStartMs = System.currentTimeMillis()
            var bucketStartSteps = 0
            var bucketStartDist = 0.0

            // Writes the steps/distance walked since the last flush, stamped with wall-clock time.
            suspend fun flushBucket() {
                val endMs = System.currentTimeMillis()
                val steps = lastSteps - bucketStartSteps
                if (writeHealth && steps > 0 && endMs > bucketStartMs) {
                    insertHealthRecords(steps, bucketStartMs, endMs, (lastDist - bucketStartDist).toFloat())
                }
                bucketStartMs = endMs
                bucketStartSteps = lastSteps
                bucketStartDist = lastDist
            }

            try {
                for (fx in fixes) {
                    val waitMs = ((fx.tOffsetS - prevT) * 1000).toLong()
                    if (waitMs > 0) delay(waitMs)
                    prevT = fx.tOffsetS
                    lastSteps = fx.stepIndex
                    lastDist = fx.cumulativeM
                    pushFix(fx.lat, fx.lon, fx.stepIndex)
                    if ((System.currentTimeMillis() - bucketStartMs) / 1000.0 >= HEALTH_BUCKET_SECONDS) {
                        flushBucket()
                    }
                }
                updateNotification("Route complete: $lastSteps steps")
                sendStatus(fixes.last().lat, fixes.last().lon, lastSteps, done = true)
            } finally {
                withContext(NonCancellable) { flushBucket() }
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(fixReceiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
            receiverRegistered = false
        }
        if (healthReceiverRegistered) {
            try {
                unregisterReceiver(healthReceiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
            healthReceiverRegistered = false
        }
        routeJob?.cancel()
        serviceScope.cancel()
        tearDownTestProviders()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Returns true if all test providers were registered successfully. */
    private fun setUpTestProviders(): Boolean {
        var allOk = true
        for (provider in PROVIDERS) {
            try {
                @Suppress("DEPRECATION") // the (String, ProviderProperties) overload needs API 31+;
                // this form still works on every supported API level.
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
            } catch (e: SecurityException) {
                // Not (yet) selected as the device's mock location app.
                allOk = false
            } catch (e: IllegalArgumentException) {
                // Provider already registered (e.g. service restarted) -- fine.
            }
        }
        return allOk
    }

    private fun tearDownTestProviders() {
        for (provider in PROVIDERS) {
            try {
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                // not registered / already torn down
            }
        }
    }

    @Synchronized
    private fun pushFix(lat: Double, lng: Double, steps: Int = -1) {
        fixCount++
        val now = System.currentTimeMillis()
        for (provider in PROVIDERS) {
            val location = Location(provider).apply {
                latitude = lat
                longitude = lng
                accuracy = 5f
                altitude = 0.0
                time = now
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0.1f
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0.1f
                }
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: Exception) {
                // provider not registered (not selected as mock app yet)
            }
        }

        updateNotification(
            if (steps >= 0) "Walking: $steps steps (fix #$fixCount)" else "Fix #$fixCount: lat=$lat lng=$lng"
        )
        sendStatus(lat, lng, steps)
    }

    private fun sendStatus(lat: Double, lng: Double, steps: Int, done: Boolean = false) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LAT, lat)
            putExtra(EXTRA_LNG, lng)
            putExtra(EXTRA_COUNT, fixCount)
            putExtra(EXTRA_STEPS, steps)
            putExtra(EXTRA_ROUTE_DONE, done)
        })
    }

    private fun writeHealthBucket(steps: Int, startMillis: Long, endMillis: Long, distanceM: Float) {
        serviceScope.launch { insertHealthRecords(steps, startMillis, endMillis, distanceM) }
    }

    /** Inserts one StepsRecord (+ DistanceRecord, if distance was sent) into Health Connect. */
    private suspend fun insertHealthRecords(steps: Int, startMillis: Long, endMillis: Long, distanceM: Float) {
        try {
            val start = Instant.ofEpochMilli(startMillis)
            val end = Instant.ofEpochMilli(endMillis)
            val zone = ZoneId.systemDefault()
            val records = mutableListOf<Record>(
                StepsRecord(
                    count = steps.toLong(),
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    metadata = Metadata.manualEntry(),
                )
            )
            if (distanceM >= 0f) {
                records += DistanceRecord(
                    distance = Length.meters(distanceM.toDouble()),
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    metadata = Metadata.manualEntry(),
                )
            }
            healthConnectClient.insertRecords(records)
            updateNotification("Health: wrote $steps steps ($fixCount fixes so far)")
        } catch (e: Exception) {
            updateNotification("Health write failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Step Route Simulator companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
