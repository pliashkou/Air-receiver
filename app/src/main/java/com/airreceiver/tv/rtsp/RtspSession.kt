package com.airreceiver.tv.rtsp

import android.util.Log
import com.airreceiver.tv.crypto.AirPlayCrypto
import com.airreceiver.tv.crypto.PairingHandler
import com.airreceiver.tv.crypto.PlayFairNative
import com.airreceiver.tv.media.AudioPlayer
import com.airreceiver.tv.media.VideoDecoder
import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles one AirPlay 2 client connection.
 *
 * Protocol flow (from RPiPlay):
 *   GET  /info              → device capability plist
 *   POST /pair-setup        → Ed25519 public key exchange
 *   POST /pair-verify       → X25519 ECDH + Ed25519 signature verification
 *   POST /fp-setup          → FairPlay key setup (two phases)
 *   SETUP  (phase 1)        → eiv/ekey exchange, timing init → response: {timingPort, eventPort}
 *   SETUP  (phase 2)        → streams with streamConnectionID → response: {streams: [{dataPort}]}
 *   RECORD                  → starts data flow
 *   FLUSH / TEARDOWN        → pause / stop
 */
class RtspSession(
    private val controlSocket: Socket,
    private val videoDecoder: VideoDecoder,
    private val audioPlayer: AudioPlayer,
    serverEdPrivateKeyBytes: ByteArray?,
    private val sharedCrypto: RtspServer.SharedCryptoState,
    private val onSessionEnd: (RtspSession) -> Unit
) {
    private val tag = "RtspSession"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(true)

    private val pairing   = PairingHandler(serverEdPrivateKeyBytes)
    private var fpKeymsg: ByteArray? = null
    private var aesKey:   ByteArray? = null
    private var aesIv:    ByteArray? = null
    private var streamCipher: org.bouncycastle.crypto.modes.SICBlockCipher? = null

    private var videoDataServer: ServerSocket? = null
    private var audioDataServer: ServerSocket? = null
    private var videoStreamConnId: Long = 0
    private var sessionId = "AirPlay-${System.currentTimeMillis()}"
    private var frameCount = 0

    // NTP client state — we send timing requests TO the iPhone
    private var ntpClientJob: Job? = null
    private var ntpSocket: java.net.DatagramSocket? = null
    private var ntpLocalPort = 0
    private var remoteTimingPort = 0
    private var remoteAddress: InetAddress? = null

    fun start() {
        scope.launch { runControlLoop() }
    }

    fun stop() {
        running.set(false)
        runCatching { controlSocket.close() }
        runCatching { videoDataServer?.close() }
        runCatching { audioDataServer?.close() }
        ntpClientJob?.cancel()
        scope.cancel()
    }

    // ── Control loop ──────────────────────────────────────────────────────────

    private suspend fun runControlLoop() = withContext(Dispatchers.IO) {
        try {
            val input  = BufferedInputStream(controlSocket.getInputStream())
            val output = DataOutputStream(controlSocket.getOutputStream())
            remoteAddress = controlSocket.inetAddress
            Log.i(tag, "Session from ${controlSocket.inetAddress}")
            while (running.get() && !controlSocket.isClosed) {
                val req = readRequest(input) ?: break
                val resp = dispatch(req)
                output.write(resp)
                output.flush()
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(tag, "Control: ${e.message}")
        } finally {
            stop(); onSessionEnd(this@RtspSession)
        }
    }

    // ── HTTP/RTSP request parsing ─────────────────────────────────────────────

    data class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun readRequest(input: BufferedInputStream): Request? {
        var requestLine: String? = null
        while (requestLine == null) {
            val line = readLine(input) ?: return null
            if (line.isNotBlank()) requestLine = line
        }
        val parts = requestLine.trim().split(" ")
        if (parts.size < 2) return null
        val method = parts[0]; val uri = parts.getOrElse(1) { "/" }

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf
        } else ByteArray(0)

        Log.d(tag, ">> $method $uri (body=${body.size})")
        return Request(method, uri, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private fun dispatch(req: Request): ByteArray {
        val cseq = req.headers["CSeq"] ?: "0"
        return when {
            req.method == "GET"  && req.uri.startsWith("/info")  -> handleInfo(cseq)
            req.method == "POST" && req.uri == "/pair-setup"     -> handlePairSetup(req, cseq)
            req.method == "POST" && req.uri == "/pair-verify"    -> handlePairVerify(req, cseq)
            req.method == "POST" && req.uri == "/fp-setup"       -> handleFpSetup(req, cseq)
            req.method == "POST" && req.uri == "/feedback"       -> ok(cseq)
            req.method == "SETUP"                                -> handleSetup(req, cseq)
            req.method == "RECORD"                               -> handleRecord(req, cseq)
            req.method == "FLUSH"                                -> handleFlush(cseq)
            req.method == "TEARDOWN"                             -> handleTeardown(req, cseq)
            req.method == "OPTIONS"                              -> handleOptions(cseq)
            req.method == "GET_PARAMETER" -> handleGetParameter(req, cseq)
            req.method == "SET_PARAMETER" -> ok(cseq)
            else -> { Log.w(tag, "Unhandled: ${req.method} ${req.uri}"); ok(cseq) }
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleOptions(cseq: String) = textResponse(cseq, extraHeaders = mapOf(
        "Public" to "ANNOUNCE,SETUP,RECORD,PAUSE,FLUSH,TEARDOWN,OPTIONS,GET_PARAMETER,SET_PARAMETER,POST,GET"
    ))

    /**
     * /info response — matches RPiPlay's raop_handler_info exactly.
     * Features: 0x1E5A7FFFF7, statusFlags: 68 (0x44)
     */
    private fun handleInfo(cseq: String): ByteArray {
        val macHex = pairing.serverEdPublicKeyBytes.toHexColons()
        val dict = NSDictionary()

        // Core identity
        dict["deviceID"]    = NSString(macHex)
        dict["macAddress"]  = NSString(macHex)
        dict["name"]        = NSString("MiBox AirPlay")
        dict["model"]       = NSString("AppleTV2,1")
        dict["sourceVersion"] = NSString("220.68")
        dict["pi"]          = NSString(stableUuid(macHex))
        dict["pk"]          = NSData(pairing.serverEdPublicKeyBytes)
        dict["vv"]          = NSNumber(2)

        // Features and status — match RPiPlay
        dict["features"]    = NSNumber(0x5A7FFFF7L or (0x1EL shl 32))
        dict["statusFlags"] = NSNumber(68L) // 0x44

        // Keep-alive
        dict["keepAliveLowPower"]         = NSNumber(true)
        dict["keepAliveSendStatsAsBody"]  = NSNumber(true)

        // Audio formats
        val audioFormats = NSArray(2)
        for ((i, type) in listOf(100, 101).withIndex()) {
            val fmt = NSDictionary()
            fmt["type"] = NSNumber(type)
            fmt["audioInputFormats"]  = NSNumber(67108860L)
            fmt["audioOutputFormats"] = NSNumber(67108860L)
            audioFormats.setValue(i, fmt)
        }
        dict["audioFormats"] = audioFormats

        // Audio latencies
        val audioLatencies = NSArray(2)
        for ((i, type) in listOf(100, 101).withIndex()) {
            val lat = NSDictionary()
            lat["type"] = NSNumber(type)
            lat["audioType"] = NSString("default")
            lat["inputLatencyMicros"]  = NSNumber(0)
            lat["outputLatencyMicros"] = NSNumber(0)
            audioLatencies.setValue(i, lat)
        }
        dict["audioLatencies"] = audioLatencies

        // Display capabilities
        val displays = NSArray(1)
        val display = NSDictionary()
        display["uuid"]           = NSString("e0ff8a27-6738-3d56-8a16-cc53aacee925")
        display["widthPhysical"]  = NSNumber(0)
        display["heightPhysical"] = NSNumber(0)
        display["width"]          = NSNumber(1920)
        display["height"]         = NSNumber(1080)
        display["widthPixels"]    = NSNumber(1920)
        display["heightPixels"]   = NSNumber(1080)
        display["rotation"]       = NSNumber(false)
        display["refreshRate"]    = NSNumber(1.0 / 60.0)
        display["overscanned"]    = NSNumber(true)
        display["features"]       = NSNumber(14)
        displays.setValue(0, display)
        dict["displays"] = displays

        val body = BinaryPropertyListWriter.writeToArray(dict)
        return binaryResponse(cseq, body, "application/x-apple-binary-plist",
            extraHeaders = mapOf("Server" to "AirTunes/220.68"))
    }

    private fun stableUuid(deviceId: String): String {
        val seed = deviceId.replace(":", "").take(12).toLongOrNull(16) ?: 0xAABBCCDDEEFFL
        return "%08x-%04x-4%03x-b%03x-%012x".format(
            seed and 0xFFFFFFFFL,
            (seed shr 32) and 0xFFFFL,
            (seed shr 48) and 0x0FFFL,
            (seed shr 52) and 0x0FFFL,
            seed and 0xFFFFFFFFFFFFL
        )
    }

    private fun handlePairSetup(req: Request, cseq: String): ByteArray {
        if (req.body.size < 32) return error(cseq, 400, "Bad pair-setup")
        val serverPubKey = pairing.handlePairSetup(req.body.copyOf(32))
        return binaryResponse(cseq, serverPubKey, "application/octet-stream")
    }

    private fun handlePairVerify(req: Request, cseq: String): ByteArray {
        val data = req.body
        if (data.size < 4) return error(cseq, 400, "Bad pair-verify")
        return when (data[0].toInt() and 0xFF) {
            1 -> {
                val payload = data.copyOfRange(4, data.size)
                val response = pairing.handlePairVerifyPhase1(payload)
                    ?: return error(cseq, 500, "Pairing failed")
                binaryResponse(cseq, response, "application/octet-stream")
            }
            0 -> {
                val signature = data.copyOfRange(4, data.size)
                if (!pairing.handlePairVerifyPhase2(signature)) {
                    Log.w(tag, "pair-verify phase2 signature check failed (non-fatal)")
                }
                // Save ECDH secret for session reuse
                pairing.ecdhSecret?.let { sharedCrypto.ecdhSecret = it }
                textResponse(cseq)
            }
            else -> error(cseq, 400, "Unknown pair-verify phase")
        }
    }

    private fun handleFpSetup(req: Request, cseq: String): ByteArray {
        val body = req.body
        return when (body.size) {
            16 -> {
                val resp = PlayFairNative.setup(body) ?: return error(cseq, 500, "FP setup failed")
                binaryResponse(cseq, resp, "application/octet-stream")
            }
            164 -> {
                fpKeymsg = body.copyOf()
                sharedCrypto.fpKeymsg = fpKeymsg
                val resp = PlayFairNative.handshake(body) ?: return error(cseq, 500, "FP handshake failed")
                binaryResponse(cseq, resp, "application/octet-stream")
            }
            else -> error(cseq, 400, "Bad fp-setup length ${body.size}")
        }
    }

    /**
     * Two-phase SETUP (matching RPiPlay):
     *
     * Phase 1 (has eiv/ekey): Decrypt AES key, start NTP client.
     *   Response: {timingPort, eventPort}
     *
     * Phase 2 (has streams): Allocate data ports, derive per-stream cipher.
     *   Response: {streams: [{type, dataPort}]}
     */
    private fun handleSetup(req: Request, cseq: String): ByteArray {
        if (req.body.isEmpty()) return textResponse(cseq)
        return try {
            val plist = BinaryPropertyListParser.parse(req.body) as? NSDictionary
                ?: return textResponse(cseq)

            Log.d(tag, "SETUP plist keys: ${plist.allKeys().toList()}")

            val eivNode  = plist["eiv"]  as? NSData
            val ekeyNode = plist["ekey"] as? NSData
            val streamsNode = plist["streams"] as? NSArray

            if (eivNode != null && ekeyNode != null) {
                // ── SETUP Phase 1: key exchange + timing ──
                val eiv  = eivNode.bytes()
                val ekey = ekeyNode.bytes()
                val timingProtocol = (plist["timingProtocol"] as? NSString)?.content ?: "NTP"
                val clientTimingPort = (plist["timingPort"] as? NSNumber)?.intValue() ?: 0
                val et = (plist["et"] as? NSNumber)?.intValue() ?: -1
                Log.d(tag, "SETUP-1: eiv=${eiv.size}B ekey=${ekey.size}B timingProtocol=$timingProtocol et=$et clientTimingPort=$clientTimingPort")

                aesIv = eiv
                val km = fpKeymsg ?: sharedCrypto.fpKeymsg
                if (km != null && ekey.size == 72) {
                    aesKey = PlayFairNative.decrypt(km, ekey)
                    if (aesKey != null) {
                        sharedCrypto.aesKey = aesKey
                        sharedCrypto.aesIv = aesIv
                    }
                    Log.i(tag, "AES key derived: ${if (aesKey != null) "OK" else "FAILED"}")
                } else {
                    Log.w(tag, "Cannot decrypt: km=${km?.size} ekey=${ekey.size}")
                }

                // Start NTP client — we send timing requests TO the iPhone
                val res = NSDictionary()
                res["eventPort"] = NSNumber(7000)
                if (timingProtocol == "NTP" && clientTimingPort > 0) {
                    startNtpClient(clientTimingPort)
                    res["timingPort"] = NSNumber(ntpLocalPort)
                    Log.d(tag, "SETUP-1 response: timingPort=$ntpLocalPort eventPort=7000")
                } else {
                    Log.i(tag, "Client uses $timingProtocol — skipping NTP")
                    Log.d(tag, "SETUP-1 response: eventPort=7000")
                }

                // Phase 1 response: ONLY timingPort + eventPort, NO streams
                val body = BinaryPropertyListWriter.writeToArray(res)
                return binaryResponse(cseq, body, "application/x-apple-binary-plist",
                    extraHeaders = mapOf("Server" to "AirTunes/220.68"))
            }

            if (streamsNode != null) {
                // ── SETUP Phase 2: stream allocation ──
                Log.d(tag, "SETUP-2: ${streamsNode.count()} streams")
                val resStreams = NSArray(streamsNode.count())

                for (i in 0 until streamsNode.count()) {
                    val streamDict = streamsNode.objectAtIndex(i) as? NSDictionary ?: continue
                    val type = (streamDict["type"] as? NSNumber)?.intValue() ?: continue

                    when (type) {
                        110 -> {
                            // Video/mirroring stream
                            val connId = (streamDict["streamConnectionID"] as? NSNumber)?.longValue() ?: 0L
                            videoStreamConnId = connId
                            Log.i(tag, "SETUP-2: video streamConnectionID=$connId")

                            // Derive per-stream cipher using iPhone's streamConnectionID
                            finalizeStreamCipher(connId)

                            // Open video data TCP server
                            val videoSrv = ServerSocket()
                            videoSrv.reuseAddress = true
                            videoSrv.bind(java.net.InetSocketAddress(0)) // ephemeral port
                            videoDataServer = videoSrv
                            Log.i(tag, "Video data port: ${videoSrv.localPort}")

                            val resStream = NSDictionary()
                            resStream["type"]     = NSNumber(110)
                            resStream["dataPort"] = NSNumber(videoSrv.localPort)
                            resStreams.setValue(i, resStream)
                        }
                        96 -> {
                            // Audio stream
                            val audioSrv = ServerSocket()
                            audioSrv.reuseAddress = true
                            audioSrv.bind(java.net.InetSocketAddress(0)) // ephemeral port
                            audioDataServer = audioSrv
                            Log.i(tag, "Audio data port: ${audioSrv.localPort}")

                            val resStream = NSDictionary()
                            resStream["type"]        = NSNumber(96)
                            resStream["dataPort"]    = NSNumber(audioSrv.localPort)
                            resStream["controlPort"] = NSNumber(audioSrv.localPort)
                            resStreams.setValue(i, resStream)
                        }
                        else -> {
                            Log.w(tag, "Unknown stream type: $type")
                        }
                    }
                }

                val res = NSDictionary()
                res["streams"] = resStreams
                val body = BinaryPropertyListWriter.writeToArray(res)
                Log.d(tag, "SETUP-2 response: streams configured")

                // Start accepting data connections now that ports are allocated
                videoDataServer?.let { srv -> scope.launch { acceptAndReadVideoData(srv) } }
                audioDataServer?.let { srv -> scope.launch { acceptAndReadAudioData(srv) } }

                return binaryResponse(cseq, body, "application/x-apple-binary-plist",
                    extraHeaders = mapOf("Server" to "AirTunes/220.68"))
            }

            Log.w(tag, "SETUP: no eiv/ekey and no streams — unknown phase")
            textResponse(cseq)
        } catch (e: Exception) {
            Log.e(tag, "SETUP error: ${e.message}", e)
            textResponse(cseq)
        }
    }

    private fun handleGetParameter(req: Request, cseq: String): ByteArray {
        val body = req.body.toString(Charsets.UTF_8).trim()
        Log.d(tag, "GET_PARAMETER body: '$body'")
        return if (body.contains("volume")) {
            val responseBody = "volume: 0.000000\r\n"
            binaryResponse(cseq, responseBody.toByteArray(), "text/parameters")
        } else {
            ok(cseq)
        }
    }

    private fun handleRecord(req: Request, cseq: String): ByteArray {
        Log.i(tag, "RECORD — ready for data ingestion")
        // Video/audio accept is started after SETUP-2 allocates the ports
        return textResponse(cseq, extraHeaders = mapOf(
            "Audio-Latency"     to "11025",
            "Audio-Jack-Status" to "connected; type=analog"
        ))
    }

    private fun handleFlush(cseq: String): ByteArray { videoDecoder.flush(); return ok(cseq) }

    private fun handleTeardown(req: Request, cseq: String): ByteArray {
        running.set(false)
        if (req.body.isNotEmpty()) {
            Log.w(tag, "TEARDOWN body hex: ${req.body.joinToString("") { "%02X".format(it) }}")
            try {
                val plist = BinaryPropertyListParser.parse(req.body)
                Log.w(tag, "TEARDOWN plist: $plist")
            } catch (e: Exception) {
                Log.w(tag, "TEARDOWN body (text): ${req.body.toString(Charsets.UTF_8)}")
            }
        }
        return ok(cseq)
    }

    // ── NTP Client (we send timing requests TO the iPhone) ───────────────────

    /**
     * RPiPlay acts as NTP CLIENT: sends 32-byte timing requests to the iPhone's
     * timingPort every 3 seconds. The iPhone responds with timestamps.
     * Packet format: {0x80, 0xd2, 0x00, 0x07, ...} with send time at offset 24.
     */
    private fun startNtpClient(clientPort: Int) {
        remoteTimingPort = clientPort
        // Create socket synchronously so ntpLocalPort is available for the SETUP response
        val sock = java.net.DatagramSocket()
        ntpSocket = sock
        ntpLocalPort = sock.localPort
        val remote = remoteAddress ?: return
        Log.i(tag, "NTP client: local=$ntpLocalPort → remote=${remote.hostAddress}:$clientPort")

        ntpClientJob = scope.launch(Dispatchers.IO) {
            try {

                while (isActive && running.get()) {
                    try {
                        val request = ByteArray(32)
                        request[0] = 0x80.toByte()
                        request[1] = 0xd2.toByte()
                        request[2] = 0x00
                        request[3] = 0x07
                        writeNtpTime(request, 24)

                        sock.send(java.net.DatagramPacket(request, request.size,
                            remote, remoteTimingPort))

                        val respBuf = ByteArray(128)
                        val respPacket = java.net.DatagramPacket(respBuf, respBuf.size)
                        sock.soTimeout = 1000
                        sock.receive(respPacket)
                        Log.d(tag, "NTP: got timing response (${respPacket.length} bytes)")
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (isActive) Log.w(tag, "NTP send error: ${e.message}")
                    }
                    delay(3000)
                }
                sock.close()
            } catch (e: Exception) {
                Log.e(tag, "NTP client error: ${e.message}")
            }
        }
    }

    private fun writeNtpTime(arr: ByteArray, off: Int) {
        val now = System.currentTimeMillis()
        val sec = (now / 1000) + 2208988800L // NTP epoch offset
        val frac = ((now % 1000) * 0x100000000L / 1000)
        for (i in 0..3) arr[off + i] = ((sec shr (24 - i * 8)) and 0xFF).toByte()
        for (i in 0..3) arr[off + 4 + i] = ((frac shr (24 - i * 8)) and 0xFF).toByte()
    }

    // ── Stream key derivation ────────────────────────────────────────────────

    private fun finalizeStreamCipher(connectionId: Long) {
        // Fall back to shared crypto state if this session didn't do key exchange
        val key = aesKey ?: sharedCrypto.aesKey
        if (key == null) {
            Log.e(tag, "No AES key available — cannot create stream cipher")
            return
        }
        val secret = pairing.ecdhSecret ?: sharedCrypto.ecdhSecret
        val effectiveSecret = secret ?: ByteArray(32)
        val derived = AirPlayCrypto.deriveStreamKey(key, effectiveSecret, connectionId) ?: return
        streamCipher = AirPlayCrypto.createStreamCipher(derived.first, derived.second)
        Log.i(tag, "Stream cipher ready (ecdh=${secret != null}, connectionId=$connectionId)")
    }

    // ── Video data reader ─────────────────────────────────────────────────────

    private suspend fun acceptAndReadVideoData(server: ServerSocket) = withContext(Dispatchers.IO) {
        try {
            Log.i(tag, "Waiting for video connection on port ${server.localPort}...")
            server.soTimeout = 30_000
            val socket = server.accept()
            Log.i(tag, "Video connected from ${socket.inetAddress}")
            val dis = DataInputStream(socket.getInputStream())
            val header = ByteArray(128)

            while (running.get()) {
                dis.readFully(header)
                // AirPlay mirror uses LITTLE-ENDIAN byte order
                val payloadSize = readIntLE(header, 0)
                val payloadType = header[4].toInt() and 0xFF

                if (payloadSize <= 0 || payloadSize > 8 * 1024 * 1024) {
                    Log.w(tag, "Bad payload size: $payloadSize"); break
                }
                val payload = ByteArray(payloadSize)
                dis.readFully(payload)

                when (payloadType) {
                    0 -> {
                        // Type 0 = encrypted video frame
                        streamCipher?.let { AirPlayCrypto.decryptInPlace(it, payload) }
                        frameCount++
                        if (frameCount <= 5) {
                            val nalType = if (payload.size > 4) payload[4].toInt() and 0x1F else -1
                            Log.i(tag, "Frame #$frameCount type=$payloadType nalType=$nalType size=${payload.size}")
                        }
                        val annexB = avccToAnnexB(payload)
                        videoDecoder.decodeFrame(annexB)
                    }
                    1 -> parseCodecInfo(header, payload)
                    5 -> {
                        // Type 5 = binary plist control/heartbeat (NOT encrypted, NOT video)
                        Log.d(tag, "Control plist type=5 size=$payloadSize")
                    }
                    else -> Log.w(tag, "Unknown payload type: $payloadType size=$payloadSize")
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(tag, "Video data error: ${e.message}")
        }
    }

    private suspend fun acceptAndReadAudioData(server: ServerSocket) = withContext(Dispatchers.IO) {
        try {
            server.soTimeout = 30_000
            val socket = server.accept()
            val dis = DataInputStream(socket.getInputStream())
            Log.i(tag, "Audio connected from ${socket.inetAddress}")
            while (running.get()) {
                val length = dis.readInt()
                if (length <= 0 || length > 512 * 1024) break
                val data = ByteArray(length)
                dis.readFully(data)
                streamCipher?.let { AirPlayCrypto.decryptInPlace(it, data) }
                audioPlayer.decodeAndPlay(data)
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(tag, "Audio data error: ${e.message}")
        }
    }

    // ── Codec info (payload_type == 1) ────────────────────────────────────────

    private fun parseCodecInfo(header: ByteArray, payload: ByteArray) {
        val width  = java.lang.Float.intBitsToFloat(readIntLE(header, 40))
        val height = java.lang.Float.intBitsToFloat(readIntLE(header, 44))
        Log.i(tag, "Codec info: ${width.toInt()}x${height.toInt()}")
        videoDecoder.setCodecParamsFromStream(payload, width.toInt(), height.toInt())
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private fun avccToAnnexB(avcc: ByteArray): ByteArray {
        val out = ByteArray(avcc.size)
        var pos = 0
        while (pos + 4 <= avcc.size) {
            val naluLen = ((avcc[pos].toInt() and 0xFF) shl 24) or
                          ((avcc[pos+1].toInt() and 0xFF) shl 16) or
                          ((avcc[pos+2].toInt() and 0xFF) shl 8) or
                           (avcc[pos+3].toInt() and 0xFF)
            if (naluLen <= 0 || pos + 4 + naluLen > avcc.size) break
            out[pos] = 0; out[pos+1] = 0; out[pos+2] = 0; out[pos+3] = 1
            System.arraycopy(avcc, pos + 4, out, pos + 4, naluLen)
            pos += 4 + naluLen
        }
        return out
    }

    private fun readIntLE(buf: ByteArray, offset: Int) =
        (buf[offset].toInt() and 0xFF) or
        ((buf[offset+1].toInt() and 0xFF) shl 8) or
        ((buf[offset+2].toInt() and 0xFF) shl 16) or
        ((buf[offset+3].toInt() and 0xFF) shl 24)

    private fun readLongLE(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    // ── Response builders ─────────────────────────────────────────────────────

    private fun ok(cseq: String) = textResponse(cseq)

    private fun textResponse(cseq: String, extraHeaders: Map<String, String> = emptyMap()): ByteArray {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n")
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun binaryResponse(
        cseq: String, body: ByteArray, contentType: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): ByteArray {
        val header = buildString {
            append("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        return header.toByteArray(Charsets.UTF_8) + body
    }

    private fun error(cseq: String, code: Int, msg: String) =
        "RTSP/1.0 $code $msg\r\nCSeq: $cseq\r\n\r\n".toByteArray(Charsets.UTF_8)

    private fun ByteArray.toHexColons() = joinToString(":") { "%02X".format(it) }
}
