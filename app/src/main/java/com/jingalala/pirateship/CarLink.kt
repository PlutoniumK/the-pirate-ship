package com.jingalala.pirateship

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    // token in the query so a plain player (libVLC) can fetch it without custom headers.
    fun mediaUrl(loopback: String) = base() + "/media?u=" + enc(loopback) + "&token=" + enc(token)
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

    /** Accept a pasted full URL or bare IP — strip scheme/port/path down to just the host. */
    fun normalizeHost(input: String): String {
        var s = input.trim()
        if (s.startsWith("http://")) s = s.substring(7)
        if (s.startsWith("https://")) s = s.substring(8)
        return s.substringBefore("/").substringBefore(":").trim()
    }

    private fun localIp(ctx: Context): String {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip == 0) "" else "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
        } catch (e: Exception) { "" }
    }

    /** Is a Cap Car Host answering at this address? */
    fun isHost(host: String, timeoutMs: Int = 700): Boolean {
        return try {
            val c = (URL(pingUrl(host)).openConnection() as HttpURLConnection)
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.connect()
            val ok = c.responseCode == 200 &&
                c.inputStream.bufferedReader().use { it.readText() }.contains("\"app\":\"thecap\"")
            c.disconnect(); ok
        } catch (e: Exception) { false }
    }

    /** Find the phone's Car Host on the LAN: DHCP gateway first, then a parallel /24 subnet sweep.
     *  Works whether we're on the phone's hotspot OR a shared router. Call off the main thread. */
    fun discover(ctx: Context): String? {
        gatewayIp(ctx).takeIf { it.isNotBlank() }?.let { if (isHost(it)) return it }
        val mine = localIp(ctx); if (mine.isBlank()) return null
        val prefix = mine.substringBeforeLast('.')
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val host = "$prefix.$i"
            if (host == mine) continue
            pool.submit { if (found.get() == null && isHost(host, 600)) found.compareAndSet(null, host) }
        }
        pool.shutdown()
        try { pool.awaitTermination(9, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        pool.shutdownNow()
        return found.get()
    }

    /** Ask the phone for the token — succeeds only while the user has a pairing window open. */
    fun requestPair(host: String): String? {
        return try {
            val c = (URL("http://${host.trim()}:$PORT/pair").openConnection() as HttpURLConnection)
            c.connectTimeout = 4000; c.readTimeout = 4000; c.connect()
            if (c.responseCode != 200) { c.disconnect(); return null }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val j = JSONObject(body)
            if (j.optBoolean("ok", false)) j.optString("token", "").takeIf { it.isNotBlank() } else null
        } catch (e: Exception) { null }
    }
}
