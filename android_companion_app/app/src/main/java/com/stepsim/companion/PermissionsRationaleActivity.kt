package com.stepsim.companion

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Required by Health Connect: an app requesting health permissions must declare an
 * activity handling ACTION_SHOW_PERMISSIONS_RATIONALE (pre-Android 14) / the
 * VIEW_PERMISSION_USAGE + HEALTH_PERMISSIONS activity-alias (Android 14+), or Health
 * Connect's permission-request screen refuses to open at all -- see AndroidManifest.xml.
 */
class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 16f
            text = "StepSim Companion writes simulated step counts and distance " +
                "(from step_route_simulator.py --write-health) into Health Connect, " +
                "as Steps and Distance records. This is a local developer testing tool: " +
                "no data leaves this device or is sent anywhere else."
        }
        setContentView(text)
    }
}
