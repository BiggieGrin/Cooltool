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

        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }

    private lateinit var locationManager: LocationManager
    private var fixCount = 0
    private var broadcastAction = DEFAULT_BROADCAST_ACTION
    private var receiverRegistered = false

    private val fixReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lat = intent.getFloatExtra("lat", Float.NaN)
            val lng = intent.getFloatExtra("lng", Float.NaN)
            if (lat.isNaN() || lng.isNaN()) return
            pushFix(lat.toDouble(), lng.toDouble())
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        broadcastAction = intent?.getStringExtra(EXTRA_BROADCAST_ACTION) ?: broadcastAction

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

        updateNotification(
            if (ok) "Waiting for fixes on $broadcastAction"
            else "ERROR: not selected as mock location app (Developer options)"
        )
        return START_STICKY
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

    private fun pushFix(lat: Double, lng: Double) {
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

        updateNotification("Fix #$fixCount: lat=$lat lng=$lng")
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_LAT, lat)
            putExtra(EXTRA_LNG, lng)
            putExtra(EXTRA_COUNT, fixCount)
        })
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
