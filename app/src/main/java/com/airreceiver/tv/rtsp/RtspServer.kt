package com.airreceiver.tv.rtsp

import android.util.Log
import com.airreceiver.tv.media.AudioPlayer
import com.airreceiver.tv.media.VideoDecoder
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TCP server that listens on [AIRPLAY_PORT] (7000) and spawns an [RtspSession]
 * for each incoming iOS / macOS client connection.
 */
class RtspServer(
    private val videoDecoder: VideoDecoder,
    private val audioPlayer: AudioPlayer,
    private val serverEdPrivateKeyBytes: ByteArray? = null
) {
    private val tag = "RtspServer"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = CopyOnWriteArrayList<RtspSession>()
    private var serverSocket: ServerSocket? = null

    /** Shared crypto state so reconnecting clients can reuse keys from prior sessions. */
    val sharedCrypto = SharedCryptoState()

    /** Called when the last active session ends (disconnect/teardown). */
    var onAllSessionsEnded: (() -> Unit)? = null

    class SharedCryptoState {
        @Volatile var aesKey: ByteArray? = null
        @Volatile var aesIv: ByteArray? = null
        @Volatile var fpKeymsg: ByteArray? = null
        @Volatile var ecdhSecret: ByteArray? = null
    }

    companion object {
        const val AIRPLAY_PORT = 7000
    }

    fun start() {
        scope.launch {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(java.net.InetSocketAddress(AIRPLAY_PORT))
                serverSocket = srv
                Log.i(tag, "Listening on port $AIRPLAY_PORT")
                while (isActive) {
                    val client = srv.accept()
                    Log.i(tag, "New client: ${client.inetAddress}")
                    val session = RtspSession(client, videoDecoder, audioPlayer, serverEdPrivateKeyBytes, sharedCrypto) { ended ->
                        activeSessions.remove(ended)
                        if (activeSessions.isEmpty()) {
                            onAllSessionsEnded?.invoke()
                        }
                    }
                    activeSessions.add(session)
                    session.start()
                }
            } catch (e: Exception) {
                if (scope.isActive) Log.e(tag, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        activeSessions.forEach { it.stop() }
        activeSessions.clear()
        runCatching { serverSocket?.close() }
        scope.cancel()
    }
}
