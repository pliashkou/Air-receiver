package com.airreceiver.tv.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    fun getWifiIpAddress(context: Context): InetAddress? {
        @Suppress("DEPRECATION")
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ipInt = wifiManager.connectionInfo.ipAddress
        if (ipInt == 0) return null
        val ipBytes = byteArrayOf(
            (ipInt and 0xFF).toByte(),
            (ipInt shr 8 and 0xFF).toByte(),
            (ipInt shr 16 and 0xFF).toByte(),
            (ipInt shr 24 and 0xFF).toByte()
        )
        return try {
            InetAddress.getByAddress(ipBytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the wlan0 MAC address, or a deterministic fallback. */
    fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return fallbackMac()
            for (intf in interfaces) {
                if (!intf.name.startsWith("wlan")) continue
                val mac = intf.hardwareAddress ?: continue
                if (mac.size < 6) continue
                return mac.joinToString(":") { "%02X".format(it) }
            }
        } catch (_: Exception) {}
        return fallbackMac()
    }

    private fun fallbackMac(): String = "AA:BB:CC:DD:EE:FF"
}
