package com.airreceiver.tv.mdns

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.airreceiver.tv.util.NetworkUtils
import kotlinx.coroutines.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Registers this device as an AirPlay receiver via Bonjour / mDNS so that
 * iOS and macOS discover it in their AirPlay device picker.
 *
 * Advertises both services (matching RPiPlay):
 *   _airplay._tcp.local.  port 7000
 *   _raop._tcp.local.     port 7000  (service name = MAC@DeviceName)
 */
class MdnsAdvertiser(private val context: Context) {
    private val tag = "MdnsAdvertiser"
    private var jmDns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."
        const val RAOP_SERVICE_TYPE    = "_raop._tcp.local."
        const val AIRPLAY_PORT = 7000

        // Feature flags matching RPiPlay (0x5A7FFFF7 lower, 0x1E upper)
        const val FEATURES = "0x5A7FFFF7,0x1E"
        const val FLAGS = "0x4"
    }

    /** Ed25519 public key hex string for mDNS pk field. Set before start(). */
    var serverPublicKeyHex: String = ""

    fun start(deviceName: String = "AirReceiver") {
        scope.launch {
            try {
                acquireMulticastLock()
                val address = NetworkUtils.getWifiIpAddress(context)
                val mac = NetworkUtils.getMacAddress()
                val macNoColons = mac.replace(":", "")

                // _airplay._tcp — primary AirPlay discovery
                val airplayProps = mapOf(
                    "deviceid" to mac,
                    "features" to FEATURES,
                    "flags"    to FLAGS,
                    "model"    to "AppleTV3,2",
                    "pk"       to serverPublicKeyHex,
                    "pi"       to stableUuid(mac),
                    "srcvers"  to "220.68",
                    "vv"       to "2"
                )

                // _raop._tcp — audio/mirroring discovery (name = MAC@DeviceName)
                val raopProps = mapOf(
                    "ch"       to "2",
                    "cn"       to "0,1,2,3",
                    "da"       to "true",
                    "et"       to "0,3,5",
                    "vv"       to "2",
                    "ft"       to "0x5A7FFFF7",
                    "am"       to "AppleTV3,2",
                    "md"       to "0,1,2",
                    "rhd"      to "5.6.0.0",
                    "pw"       to "false",
                    "sr"       to "44100",
                    "ss"       to "16",
                    "sv"       to "false",
                    "tp"       to "UDP",
                    "txtvers"  to "1",
                    "sf"       to FLAGS,
                    "vs"       to "220.68",
                    "vn"       to "65537",
                    "pk"       to serverPublicKeyHex
                )

                val airplayService = ServiceInfo.create(
                    AIRPLAY_SERVICE_TYPE, deviceName, AIRPLAY_PORT, 0, 0, airplayProps
                )
                val raopService = ServiceInfo.create(
                    RAOP_SERVICE_TYPE, "${macNoColons}@${deviceName}", AIRPLAY_PORT, 0, 0, raopProps
                )

                val dns = if (address != null) JmDNS.create(address, deviceName) else JmDNS.create()
                dns.registerService(airplayService)
                dns.registerService(raopService)
                jmDns = dns
                Log.i(tag, "Registered '$deviceName' as AirPlay receiver (pk=${serverPublicKeyHex.take(16)}...)")
            } catch (e: Exception) {
                Log.e(tag, "mDNS registration failed: ${e.message}")
            }
        }
    }

    fun stop() {
        scope.launch {
            runCatching { jmDns?.unregisterAllServices() }
            runCatching { jmDns?.close() }
            runCatching { multicastLock?.release() }
            jmDns = null
        }
        scope.cancel()
    }

    private fun acquireMulticastLock() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("AirPlayMulticast")
        lock.setReferenceCounted(true)
        lock.acquire()
        multicastLock = lock
    }

    private fun stableUuid(mac: String): String {
        val seed = mac.replace(":", "").toLongOrNull(16) ?: 0xAABBCCDDEEFFL
        return String.format(
            "%08x-%04x-4%03x-b%03x-%012x",
            seed and 0xFFFFFFFFL,
            (seed shr 32) and 0xFFFFL,
            (seed shr 48) and 0x0FFFL,
            (seed shr 52) and 0x0FFFL,
            seed and 0xFFFFFFFFFFFFL
        )
    }
}
