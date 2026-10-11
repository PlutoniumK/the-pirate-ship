package com.jingalala.pirateship

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Pirate Ship's OWN self-updater — reads a DEDICATED version record so it can never pick up
 * The Cap's phone APK. Source: the distribution Worker's /car_version (JSON: version_code,
 * version_name, apk_url, notes). Compares to this build's versionCode, downloads the APK, and
 * hands it to the system installer via FileProvider. Signed with the Pirate Ship's own key, so a
 * sideloaded update installs in place.
 */
object CarUpdater {
    private const val VERSION_URL = "https://get.jingalala.workers.dev/car_version"

    data class Info(val code: Int, val name: String, val apkUrl: String, val notes: String)

    /** Returns update info only when a strictly-newer build is published, else null. */
    fun check(): Info? {
        return try {
            val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection)
            conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(body)
            val code = j.optInt("version_code", 0)
            val apk = j.optString("apk_url", "")
            if (code > BuildConfig.VERSION_CODE && apk.startsWith("http"))
                Info(code, j.optString("version_name", ""), apk, j.optString("notes", "")) else null
        } catch (e: Exception) { null }
    }

    /** Download the APK to external cache and launch the installer. Call off the main thread. */
    fun downloadAndInstall(act: Activity, apkUrl: String) {
        try {
            val dir = File(act.externalCacheDir ?: act.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
            val dest = File(dir, "PirateShip-update.apk")
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000; conn.readTimeout = 60000; conn.connect()
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
            conn.disconnect()
            if (dest.length() < 100 * 1024) return   // sanity: a real APK is > 100KB
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", dest)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (e: Exception) { /* ignore — user can retry */ }
    }
}
