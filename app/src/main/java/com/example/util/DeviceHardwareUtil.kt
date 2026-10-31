package com.example.util

import android.content.Context
import android.provider.Settings
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * Android 9 (API 28) va smartboard (interaktiv doska)larda apparat MAC manzilini
 * va qurilma identifikatorini aniqlash utiliti.
 */
object DeviceHardwareUtil {

    /**
     * Qurilmaning haqiqiy apparat MAC manzilini aniqlash.
     * Android 9 da NetworkInterface orqali apparat MAC manzilini to'liq olish mumkin.
     * Interaktiv doskalarda (Rockchip, MStar, Amlogic) odatda kabel (eth0) yoki Wi-Fi (wlan0) ishlatiladi.
     */
    fun getDeviceMacAddress(context: Context): String {
        // 1-usul: java.net.NetworkInterface orqali (Android 9 API 28 da to'liq ruxsat etilgan)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                val list = Collections.list(interfaces)
                val sorted = list.sortedWith { a, b ->
                    val scoreA = getInterfacePriority(a.name)
                    val scoreB = getInterfacePriority(b.name)
                    scoreA.compareTo(scoreB)
                }

                for (nif in sorted) {
                    if (nif.isLoopback) continue
                    val macBytes = nif.hardwareAddress ?: continue
                    if (macBytes.isEmpty()) continue

                    val sb = StringBuilder()
                    for (b in macBytes) {
                        sb.append(String.format("%02X:", b))
                    }
                    if (sb.isNotEmpty()) {
                        sb.deleteCharAt(sb.length - 1)
                    }
                    val macStr = sb.toString()
                    if (isValidMac(macStr)) {
                        return macStr
                    }
                }
            }
        } catch (_: Exception) {}

        // 2-usul: Android 9 Smartboard tizim fayllari orqali (/sys/class/net/eth0/address)
        val sysPaths = listOf(
            "/sys/class/net/eth0/address",
            "/sys/class/net/eth1/address",
            "/sys/class/net/wlan0/address"
        )
        for (path in sysPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val macStr = file.readText().trim().uppercase()
                    if (isValidMac(macStr)) {
                        return macStr
                    }
                }
            } catch (_: Exception) {}
        }

        // 3-usul: Apparat MAC topilmasa, Android ID qaytariladi
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun getInterfacePriority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower == "eth0" -> 0
            lower.startsWith("eth") -> 1
            lower == "wlan0" -> 2
            lower.startsWith("wlan") -> 3
            else -> 4
        }
    }

    fun isValidMac(mac: String): Boolean {
        if (mac.length != 17) return false
        val upper = mac.uppercase()
        if (upper == "02:00:00:00:00:00" || upper == "00:00:00:00:00:00") return false
        val parts = upper.split(":")
        if (parts.size != 6) return false
        for (part in parts) {
            if (part.length != 2) return false
            if (part.toIntOrNull(16) == null) return false
        }
        return true
    }
}
