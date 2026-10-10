package com.jingalala.pirateship

import android.content.Context
import android.net.wifi.WifiManager
import java.net.URLEncoder

/**
 * Where the paired phone is, and how to reach it. Persisted so the car reconnects on its own.
 * host = the phone's LAN IP (defaults to the DHCP gateway, i.e. the phone when we're on its
 * hotspot); token = the pairing code shown on the phone's Car Mode screen.
 */
object CarLink {
    const val PORT = 8099
    private const val PREFS = "pirateship"

    @Volatile var host: String = ""
    @Volatile var token: String = ""

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host = sp.getString("host", "") ?: ""
        token = sp.getString("token", "") ?: ""
    }

    fun save(ctx: Context, h: String, t: String) {
        host = h.trim(); token = t.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", host).putString("token", token).apply()
    }

    fun clear(ctx: Context) {
        host = ""; token = ""
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun configured() = host.isNotBlank() && token.isNotBlank()
    fun base() = "http://$host:$PORT"
    fun uiUrl() = base() + "/ui/?t=" + enc(token)
    fun mediaUrl(loopback: String) = base() + "/media?u=" + enc(loopback)
    fun pingUrl(h: String) = "http://${h.trim()}:$PORT/ping"

    private fun enc(s: String) = try { URLEncoder.encode(s, "UTF-8") } catch (e: Exception) { s }

    /** The phone's IP when we're on its hotspot == this device's DHCP gateway. Works on KitKat. */
    fun gatewayIp(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g == 0) "" else "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
        } catch (e: Exception) { "" }
    }
}
