package com.airreceiver.tv

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.airreceiver.tv.service.AirPlayService

/**
 * Full-screen activity that:
 *  1. Starts [AirPlayService] as a foreground service
 *  2. Binds to it to pass the [SurfaceView] render surface
 *  3. Shows a status text overlay ("waiting" / sender name)
 */
class MainActivity : FragmentActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var overlayLayout: LinearLayout
    private var airPlayService: AirPlayService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as AirPlayService.LocalBinder).getService()
            airPlayService = service

            // Give the decoder its render surface (already available from SurfaceHolder callback)
            surfaceView.holder.surface?.let { service.setSurface(it) }

            service.onStatusChanged = { msg ->
                runOnUiThread { statusText.text = msg }
            }
            service.onStreamingStarted = {
                runOnUiThread {
                    overlayLayout.visibility = View.GONE
                }
            }
            service.onStreamingStopped = {
                runOnUiThread {
                    overlayLayout.visibility = View.VISIBLE
                }
            }
            service.onVideoSizeChanged = { w, h ->
                runOnUiThread { adjustSurfaceAspectRatio(w, h) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            airPlayService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        statusText  = findViewById(R.id.statusText)
        overlayLayout = findViewById(R.id.overlayLayout)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                airPlayService?.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        startAndBindService()
    }

    override fun onDestroy() {
        runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }

    private fun adjustSurfaceAspectRatio(videoWidth: Int, videoHeight: Int) {
        val parent = surfaceView.parent as? View ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW == 0 || parentH == 0 || videoWidth == 0 || videoHeight == 0) return

        val videoAspect = videoWidth.toFloat() / videoHeight
        val parentAspect = parentW.toFloat() / parentH

        val (newW, newH) = if (videoAspect > parentAspect) {
            // Video is wider — fit to width
            parentW to (parentW / videoAspect).toInt()
        } else {
            // Video is taller — fit to height
            (parentH * videoAspect).toInt() to parentH
        }

        val params = surfaceView.layoutParams as android.widget.FrameLayout.LayoutParams
        params.width = newW
        params.height = newH
        params.gravity = android.view.Gravity.CENTER
        surfaceView.layoutParams = params
        android.util.Log.i("MainActivity", "Surface resized: ${videoWidth}x$videoHeight → ${newW}x$newH")
    }

    private fun startAndBindService() {
        val intent = Intent(this, AirPlayService::class.java).apply {
            putExtra(AirPlayService.EXTRA_DEVICE_NAME, "Remote Play")
        }
        startForegroundService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }
}
