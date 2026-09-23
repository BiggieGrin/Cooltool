package com.stepsim.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/**
 * Hosts the Leaflet route builder (assets/route_builder.html) in a WebView. Tapping
 * "Use route" hands the drawn waypoints back to MainActivity as double arrays.
 * Needs internet for the OpenStreetMap tiles.
 */
class RouteBuilderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LATS = "lats"
        const val EXTRA_LONS = "lons"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.loadUrl("file:///android_asset/route_builder.html")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun sendRoute(json: String) {
            val arr = JSONArray(json)
            val lats = DoubleArray(arr.length())
            val lons = DoubleArray(arr.length())
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONArray(i)
                lats[i] = pt.getDouble(0)
                lons[i] = pt.getDouble(1)
            }
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_LATS, lats).putExtra(EXTRA_LONS, lons))
                finish()
            }
        }
    }
}
