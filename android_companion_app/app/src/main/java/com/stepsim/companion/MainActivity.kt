package com.stepsim.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val healthPermissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
    )

    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            statusText.text = if (granted.containsAll(healthPermissions)) {
                "Health Connect access granted. Steps will be written during --write-health runs."
            } else {
                "Health Connect access denied -- steps won't be written to Health Connect."
            }
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val lat = intent.getDoubleExtra(MockLocationService.EXTRA_LAT, 0.0)
            val lng = intent.getDoubleExtra(MockLocationService.EXTRA_LNG, 0.0)
            val count = intent.getIntExtra(MockLocationService.EXTRA_COUNT, 0)
            val steps = intent.getIntExtra(MockLocationService.EXTRA_STEPS, -1)
            val done = intent.getBooleanExtra(MockLocationService.EXTRA_ROUTE_DONE, false)
            statusText.text = buildString {
                append(if (done) "Route complete." else "Service running.")
                append("\n\nFix #$count\nlat = $lat\nlng = $lng")
                if (steps >= 0) append("\nsteps = $steps")
            }
        }
    }

    private lateinit var routeSummary: TextView
    private var routeLats: DoubleArray? = null
    private var routeLons: DoubleArray? = null

    private val drawRoute =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            val lats = data.getDoubleArrayExtra(RouteBuilderActivity.EXTRA_LATS) ?: return@registerForActivityResult
            val lons = data.getDoubleArrayExtra(RouteBuilderActivity.EXTRA_LONS) ?: return@registerForActivityResult
            setRoute(lats, lons, "Drawn route")
        }

    private val importGpx =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val points = contentResolver.openInputStream(uri)!!.use { Simulator.parseGpx(it) }
                if (points.size < 2) throw IllegalArgumentException("GPX needs at least two points")
                setRoute(
                    DoubleArray(points.size) { points[it].lat },
                    DoubleArray(points.size) { points[it].lon },
                    "GPX route",
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't read GPX: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    private fun setRoute(lats: DoubleArray, lons: DoubleArray, label: String) {
        routeLats = lats
        routeLons = lons
        updateRouteSummary(label)
    }

    private fun updateRouteSummary(label: String = "Route") {
        val lats = routeLats ?: return
        val lons = routeLons ?: return
        val km = Simulator.routeDistanceM(lats.indices.map { LatLon(lats[it], lons[it]) }) / 1000.0
        val speed = findViewById<EditText>(R.id.speedInput).text.toString().toDoubleOrNull() ?: 4.5
        val stride = findViewById<EditText>(R.id.strideInput).text.toString().toDoubleOrNull() ?: 0.78
        val minutes = if (speed > 0) km / speed * 60 else 0.0
        routeSummary.text = "%s: %d points, %.2f km, ~%d steps, ~%d min".format(
            label, lats.size, km, (km * 1000 / stride).toInt(), minutes.toInt()
        )
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val devOptionsButton: Button = findViewById(R.id.devOptionsButton)
        val healthConnectButton: Button = findViewById(R.id.healthConnectButton)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
        }

        startButton.setOnClickListener {
            // Android requires this to be an actually-granted runtime permission (not just
            // declared in the manifest) before a location-type foreground service can start,
            // or the service crashes with a SecurityException on startForeground().
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
                statusText.text = "Grant the location permission in the dialog, then tap Start again."
                return@setOnClickListener
            }
            startForegroundServiceCompat(Intent(this, MockLocationService::class.java))
            statusText.text = "Starting service...\n\n" +
                "If fixes never arrive, make sure this app is selected as the " +
                "mock location app under Developer options."
        }

        routeSummary = findViewById(R.id.routeSummary)
        findViewById<Button>(R.id.drawRouteButton).setOnClickListener {
            drawRoute.launch(Intent(this, RouteBuilderActivity::class.java))
        }
        findViewById<Button>(R.id.importGpxButton).setOnClickListener {
            importGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream"))
        }

        findViewById<Button>(R.id.startWalkButton).setOnClickListener {
            val lats = routeLats
            val lons = routeLons
            if (lats == null || lons == null) {
                statusText.text = "Pick a route first (draw on the map or import a GPX)."
                return@setOnClickListener
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
                statusText.text = "Grant the location permission in the dialog, then tap Start walking again."
                return@setOnClickListener
            }
            val speed = findViewById<EditText>(R.id.speedInput).text.toString().toDoubleOrNull()
            val stride = findViewById<EditText>(R.id.strideInput).text.toString().toDoubleOrNull()
            if (speed == null || speed <= 0 || stride == null || stride <= 0) {
                statusText.text = "Enter a positive speed and stride."
                return@setOnClickListener
            }
            updateRouteSummary()
            startForegroundServiceCompat(
                Intent(this, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START_ROUTE
                    putExtra(MockLocationService.EXTRA_ROUTE_LATS, lats)
                    putExtra(MockLocationService.EXTRA_ROUTE_LONS, lons)
                    putExtra(MockLocationService.EXTRA_SPEED_KMH, speed)
                    putExtra(MockLocationService.EXTRA_STRIDE_M, stride)
                    putExtra(
                        MockLocationService.EXTRA_WRITE_HEALTH,
                        findViewById<CheckBox>(R.id.writeHealthCheck).isChecked
                    )
                }
            )
            statusText.text = "Walking started. It keeps going with the screen off; tap Stop to end.\n\n" +
                "If nothing moves, make sure this app is selected as the mock location app."
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            statusText.text = "Service stopped."
        }

        devOptionsButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Couldn't open Developer options automatically -- open Settings manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        healthConnectButton.setOnClickListener {
            when (HealthConnectClient.getSdkStatus(this)) {
                HealthConnectClient.SDK_UNAVAILABLE ->
                    Toast.makeText(this, "This device doesn't support Health Connect.", Toast.LENGTH_LONG).show()
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setPackage("com.android.vending")
                        data = Uri.parse(
                            "market://details?id=com.google.android.apps.healthdata" +
                                "&url=healthconnect%3A%2F%2Fonboarding"
                        )
                    })
                }
                else -> requestHealthPermissions.launch(healthPermissions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MockLocationService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }
}
