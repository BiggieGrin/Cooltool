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
import android.text.Editable
import android.text.TextWatcher
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
import kotlin.random.Random

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
            val totalSteps = intent.getIntExtra(MockLocationService.EXTRA_TOTAL_STEPS, -1)
            val done = intent.getBooleanExtra(MockLocationService.EXTRA_ROUTE_DONE, false)
            val health = intent.getStringExtra(MockLocationService.EXTRA_HEALTH_STATUS).orEmpty()
            statusText.text = buildString {
                append(if (done) "Route complete." else "Service running.")
                append("\n\nFix #$count\nlat = $lat\nlng = $lng")
                if (steps >= 0) append("\nsteps = $steps/$totalSteps")
                if (health.isNotEmpty()) append("\n$health")
            }
        }
    }

    private lateinit var routeSummary: TextView
    private var routeLats: DoubleArray? = null
    private var routeLons: DoubleArray? = null
    private var routeLabel = "Route"
    // Fixed per route so the total shown here is exactly what the service later walks.
    private var routeSeed = 0L

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
        routeSeed = Random.nextLong()
        routeLabel = label
        updateRouteSummary()
    }

    private fun updateRouteSummary() {
        val lats = routeLats ?: return
        val lons = routeLons ?: return
        val km = Simulator.routeDistanceM(lats.indices.map { LatLon(lats[it], lons[it]) }) / 1000.0
        val speedMin = findViewById<EditText>(R.id.speedMinInput).text.toString().toDoubleOrNull() ?: 3.5
        val speedMax = findViewById<EditText>(R.id.speedMaxInput).text.toString().toDoubleOrNull() ?: 5.5
        val stride = findViewById<EditText>(R.id.strideInput).text.toString().toDoubleOrNull() ?: 0.78
        if (speedMin <= 0 || speedMax <= 0 || stride <= 0) return
        val route = lats.indices.map { LatLon(lats[it], lons[it]) }
        val (_, stats) = Simulator.simulateWalk(
            route, SimParams(strideM = stride, speedMinKmh = speedMin, speedMaxKmh = speedMax), routeSeed
        )
        routeSummary.text = "%s: %d points, %.2f km, %d steps, ~%d min".format(
            routeLabel, lats.size, km, stats.totalSteps, (stats.totalDurationS / 60).toInt()
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

        // Default on, and remembered, so steps aren't silently skipped after a relaunch.
        val prefs = getSharedPreferences("stepsim", Context.MODE_PRIVATE)
        val writeHealthCheck = findViewById<CheckBox>(R.id.writeHealthCheck)
        writeHealthCheck.isChecked = prefs.getBoolean("write_health", true)
        writeHealthCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("write_health", checked).apply()
        }

        routeSummary = findViewById(R.id.routeSummary)
        val refreshSummary = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateRouteSummary()
        }
        findViewById<EditText>(R.id.speedMinInput).addTextChangedListener(refreshSummary)
        findViewById<EditText>(R.id.speedMaxInput).addTextChangedListener(refreshSummary)
        findViewById<EditText>(R.id.strideInput).addTextChangedListener(refreshSummary)
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
            val speedMin = findViewById<EditText>(R.id.speedMinInput).text.toString().toDoubleOrNull()
            val speedMax = findViewById<EditText>(R.id.speedMaxInput).text.toString().toDoubleOrNull()
            val stride = findViewById<EditText>(R.id.strideInput).text.toString().toDoubleOrNull()
            if (speedMin == null || speedMin <= 0 || speedMax == null || speedMax < speedMin ||
                stride == null || stride <= 0
            ) {
                statusText.text = "Enter positive min/max speeds (max >= min) and stride."
                return@setOnClickListener
            }
            updateRouteSummary()
            startForegroundServiceCompat(
                Intent(this, MockLocationService::class.java).apply {
                    action = MockLocationService.ACTION_START_ROUTE
                    putExtra(MockLocationService.EXTRA_ROUTE_LATS, lats)
                    putExtra(MockLocationService.EXTRA_ROUTE_LONS, lons)
                    putExtra(MockLocationService.EXTRA_SPEED_MIN_KMH, speedMin)
                    putExtra(MockLocationService.EXTRA_SPEED_MAX_KMH, speedMax)
                    putExtra(MockLocationService.EXTRA_STRIDE_M, stride)
                    putExtra(MockLocationService.EXTRA_SEED, routeSeed)
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
