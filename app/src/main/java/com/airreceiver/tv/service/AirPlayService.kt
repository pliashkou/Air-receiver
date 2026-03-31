package com.airreceiver.tv.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.airreceiver.tv.MainActivity
import com.airreceiver.tv.R
import com.airreceiver.tv.crypto.PairingHandler
import com.airreceiver.tv.mdns.MdnsAdvertiser
import com.airreceiver.tv.media.AudioPlayer
import com.airreceiver.tv.media.VideoDecoder
import com.airreceiver.tv.rtsp.RtspServer
import java.io.File

/**
 * Foreground service that owns the AirPlay receiver lifecycle:
 *  - mDNS advertiser (so the device appears in AirPlay menus)
 *  - RTSP server (protocol negotiation)
 *  - Video decoder + Audio player (media pipeline)
 *
 * The [MainActivity] binds to this service to provide the render [Surface]
 * and receive status updates.
 */
class AirPlayService : Service() {
    private val tag = "AirPlayService"

    inner class LocalBinder : Binder() {
        fun getService() = this@AirPlayService
    }

    private val binder = LocalBinder()

    val videoDecoder = VideoDecoder()
    val audioPlayer  = AudioPlayer()
    private lateinit var rtspServer: RtspServer
    private lateinit var mdnsAdvertiser: MdnsAdvertiser

    var onStatusChanged: ((String) -> Unit)? = null
    var onStreamingStarted: (() -> Unit)? = null
    var onStreamingStopped: (() -> Unit)? = null
    var onVideoSizeChanged: ((Int, Int) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for AirPlay…"))

        val serverKey = loadOrCreateServerKey()
        val handler = PairingHandler(serverKey)
        val pkHex = handler.serverEdPublicKeyBytes.joinToString("") { "%02x".format(it) }

        videoDecoder.onFirstFrameRendered = {
            onStreamingStarted?.invoke()
        }
        videoDecoder.onVideoSizeChanged = { w, h ->
            onVideoSizeChanged?.invoke(w, h)
        }

        mdnsAdvertiser = MdnsAdvertiser(this)
        mdnsAdvertiser.serverPublicKeyHex = pkHex
        rtspServer = RtspServer(videoDecoder, audioPlayer, serverKey)
        rtspServer.onAllSessionsEnded = {
            videoDecoder.stop()
            onStreamingStopped?.invoke()
        }
    }

    private fun loadOrCreateServerKey(): ByteArray {
        val keyFile = File(filesDir, "server_ed25519.key")
        if (keyFile.exists()) {
            val bytes = keyFile.readBytes()
            if (bytes.size == 32) {
                Log.i(tag, "Loaded persistent Ed25519 server key")
                return bytes
            }
        }
        val handler = PairingHandler()
        val keyBytes = handler.privateKeyBytes
        keyFile.writeBytes(keyBytes)
        Log.i(tag, "Generated and saved new Ed25519 server key")
        return keyBytes
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "AirReceiver"
        mdnsAdvertiser.start(name)
        rtspServer.start()
        updateStatus("Ready — connect from AirPlay as \"$name\"")
        Log.i(tag, "Service started as '$name'")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        rtspServer.stop()
        mdnsAdvertiser.stop()
        videoDecoder.stop()
        audioPlayer.stop()
        super.onDestroy()
    }

    fun setSurface(surface: Surface) = videoDecoder.setSurface(surface)

    private fun updateStatus(msg: String) {
        onStatusChanged?.invoke(msg)
        updateNotification(msg)
    }

    // ---- Notification ----

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirReceiver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AirPlay Receiver", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "airplay_receiver"
    }
}
