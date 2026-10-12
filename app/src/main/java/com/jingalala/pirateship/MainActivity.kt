package com.jingalala.pirateship

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The Pirate Ship — car head-unit companion to The Cap.
 *
 * Phase 2d/2e: full-screen WebView that either shows a setup page (confirm the phone's IP + enter
 * the pairing code) or, once paired, loads the Cap UI served BY the phone
 * (http://<phone>:8099/ui/?t=<token>). A WebViewClient rewrites any 127.0.0.1 request to the
 * phone's /media proxy. car_play is handed to onCarPlay (bundled libVLC player arrives in 2f).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CarLink.load(this)

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(PirateBridge(this), "PirateNative")
        web.webViewClient = CarWebClient()

        setContentView(web)
        goImmersive()

        if (CarLink.configured()) loadPhoneUi() else loadSetup()

        // If the car's WebView is too old for the modern UI, nudge an update (non-blocking).
        if (RendererDecider.webViewOutdated(this)) nudgeWebViewUpdate()

        // Background self-update check (its OWN channel — never The Cap's app_version). It runs OFF
        // the UI thread so it can NEVER make the car "hang on checking", and it is purely OPTIONAL:
        // an update is only ever OFFERED, never forced, and once the user taps "Later" for a given
        // version we never ask again for that version (so it never feels force-pushed). The car always
        // keeps playing the library on whatever version it's on.
        Thread {
            val info = CarUpdater.check()
            if (info != null && info.code > skippedUpdateCode()) runOnUiThread { promptUpdate(info) }
        }.start()
    }

    private fun prefs() = getSharedPreferences("pirate", MODE_PRIVATE)
    private fun skippedUpdateCode(): Int = try { prefs().getInt("skip_update_code", 0) } catch (e: Exception) { 0 }
    private fun setSkippedUpdateCode(code: Int) { try { prefs().edit().putInt("skip_update_code", code).apply() } catch (e: Exception) {} }

    private fun promptUpdate(info: CarUpdater.Info) {
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("A newer version of The Cap" + (if (info.name.isNotBlank()) " (${info.name})" else "") +
                        " is ready to install." + (if (info.notes.isNotBlank()) "\n\n${info.notes}" else ""))
                .setPositiveButton("Install") { _, _ -> Thread { CarUpdater.downloadAndInstall(this, info.apkUrl) }.start() }
                // "Later" REMEMBERS this version so we don't nag again every launch (no force-push feel).
                .setNegativeButton("Later") { _, _ -> setSkippedUpdateCode(info.code) }
                .setCancelable(true)
                .show()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun nudgeWebViewUpdate() {
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Update for the best experience")
                .setMessage("Your car's web engine is out of date, so some things may not look right. Updating Android System WebView fixes it.")
                .setPositiveButton("Update") { _, _ -> openWebViewUpdate() }
                .setNegativeButton("Continue", null)
                .show()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun openWebViewUpdate() {
        val pkg = "com.google.android.webview"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    // ---- loading ----
    fun loadPhoneUi() { web.loadUrl(CarLink.uiUrl()) }

    private fun loadSetup() {
        web.loadDataWithBaseURL("http://pair.local/", setupHtml(), "text/html", "utf-8", null)
    }

    /** From the setup page: verify the phone answers, then save + load, else show an error. */
    fun onConnectRequested(host: String, token: String) {
        val h = CarLink.normalizeHost(host); val t = token.trim()
        if (h.isEmpty() || t.isEmpty()) { psError("Enter the phone address and the pairing code."); return }
        Thread {
            val ok = pingPhone(h, t)
            runOnUiThread {
                if (ok) { CarLink.save(this, h, t); loadPhoneUi() }
                else psError("Couldn't reach The Cap on your phone. Check the phone's hotspot is on, Car Mode is on, and the code is right.")
            }
        }.start()
    }

    private fun pingPhone(h: String, t: String): Boolean {
        return try {
            val conn = (URL(CarLink.pingUrl(h)).openConnection() as HttpURLConnection)
            conn.connectTimeout = 6000; conn.readTimeout = 6000
            conn.connect()
            val code = conn.responseCode
            val body = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
            conn.disconnect()
            code == 200 && body.contains("\"app\":\"thecap\"")
        } catch (e: Exception) { false }
    }

    private fun psError(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
        web.evaluateJavascript("window.psError && window.psError('$safe')", null)
    }

    private fun psStatus(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
        web.evaluateJavascript("window.psStatus && window.psStatus('$safe')", null)
    }

    /** One-tap: auto-find the phone on the LAN, then grab the token during its pairing window. */
    fun onFindAndConnect() {
        psStatus("Looking for your phone…")
        Thread {
            val host = CarLink.discover(this)
            if (host == null) {
                runOnUiThread { psError("Couldn't find your phone. Make sure Car Mode is on and the car is on the same Wi-Fi/hotspot.") }
                return@Thread
            }
            runOnUiThread { psStatus("Found it. On the phone, open Car Mode and tap “Pair a new car”…") }
            var token: String? = null
            val deadline = System.currentTimeMillis() + 90_000L
            while (System.currentTimeMillis() < deadline) {
                token = CarLink.requestPair(host)
                if (token != null) break
                try { Thread.sleep(2000) } catch (_: Exception) {}
            }
            if (token != null) {
                CarLink.save(this, host, token)
                runOnUiThread { loadPhoneUi() }
            } else {
                runOnUiThread { psError("Didn't get the OK from your phone. On the phone tap “Pair a new car”, then try again.") }
            }
        }.start()
    }

    // ---- car_* native actions ----
    fun onCarPlay(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val url = o.optString("url", "")
        val title = o.optString("title", "")
        val playable = rewriteLoopback(url)
        if (playable.isBlank()) { toast("Nothing to play"); return }
        PlayerActivity.start(this, playable, title)
    }

    fun openExternal(url: String) {
        if (url.isBlank()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("No browser on this screen") }
    }

    fun onLinkUp() { /* connected — reserved for a status chip */ }
    fun onLinkDown() { /* disconnected — car-bridge.js auto-reconnects the WS */ }

    // ---- update ladder: phone → car (PirateBridge.installUpdate / installFromUrl) ----
    /**
     * car_update pushed from the phone. The url points at the phone's /ship_apk, so we pass our
     * pairing token as the X-Cap-Token header (CarLink.token) so the phone authorizes the pull.
     * Downloads + installs on a background Thread. (Android still shows the final installer prompt.)
     */
    fun onInstallUpdate(json: String) {
        val url = try { JSONObject(json).optString("url", "") } catch (e: Exception) { "" }
        if (url.isBlank()) { toast("No update URL"); return }
        val token = CarLink.token.takeIf { it.isNotBlank() }
        Thread { CarUpdater.downloadAndInstallFrom(this, url, token) }.start()
    }

    /** Install from any absolute url (e.g. the dedicated worker's /ship_apk) — no token header. */
    fun onInstallFromUrl(url: String) {
        if (url.isBlank()) { toast("No update URL"); return }
        Thread { CarUpdater.downloadAndInstallFrom(this, url, null) }.start()
    }

    /** 127.0.0.1 stream URLs live on the PHONE; route them through the phone's /media proxy. */
    private fun rewriteLoopback(url: String): String {
        if (url.isBlank()) return url
        return try {
            val h = URI(url).host
            if ((h == "127.0.0.1" || h == "localhost") && CarLink.configured()) CarLink.mediaUrl(url) else url
        } catch (e: Exception) { url }
    }

    // ---- immersive / lifecycle ----
    fun goImmersive() {
        @Suppress("DEPRECATION")
        web.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    // ---- WebViewClient: intercept loopback → the phone's /media proxy ----
    private inner class CarWebClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            val range = try { request.requestHeaders?.get("Range") } catch (e: Exception) { null }
            return intercept(url, range) ?: super.shouldInterceptRequest(view, request)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
            if (url == null) return null
            return intercept(url, null) ?: super.shouldInterceptRequest(view, url)
        }

        private fun intercept(url: String, range: String?): WebResourceResponse? {
            return try {
                val h = URI(url).host ?: return null
                if (h != "127.0.0.1" && h != "localhost") return null
                if (!CarLink.configured()) return null
                val conn = (URL(CarLink.mediaUrl(url)).openConnection() as HttpURLConnection)
                conn.setRequestProperty("X-Cap-Token", CarLink.token)
                if (range != null) conn.setRequestProperty("Range", range)
                conn.connectTimeout = 10000; conn.readTimeout = 65000
                conn.connect()
                val mime = (conn.contentType ?: "video/mp4").substringBefore(";").trim().ifBlank { "video/mp4" }
                WebResourceResponse(mime, null, conn.inputStream)
            } catch (e: Exception) { null }
        }
    }

    // ---- setup page (themed to match The Cap; ES5/flex-only so it renders on any WebView) ----
    private fun setupHtml(): String {
        val gw = CarLink.gatewayIp(this)
        val prefHost = if (CarLink.host.isNotBlank()) CarLink.host else gw
        return """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  html,body{height:100%;margin:0}
  body{background:#160d2c;color:#eae3ff;font-family:'Segoe UI',Roboto,sans-serif;
       display:flex;align-items:center;justify-content:center}
  .card{width:92%;max-width:540px;background:rgba(40,28,74,.72);border:1px solid rgba(246,198,81,.35);
        border-radius:20px;padding:26px 26px;box-shadow:0 10px 36px rgba(0,0,0,.45);text-align:center}
  .hat{font-size:46px}
  h1{color:#f6c651;font-size:23px;font-weight:800;margin:8px 0 2px}
  p.sub{color:#a99fd0;font-size:13px;margin:0 0 18px}
  button.primary{width:100%;background:linear-gradient(180deg,#ffe09a,#f6c651);color:#2a1850;font-weight:800;
         border:none;border-radius:14px;padding:16px;font-size:17px}
  .status{color:#ffe09a;font-size:14px;margin-top:14px;min-height:18px}
  .err{color:#ff8a8a;font-size:13px;margin-top:8px;min-height:16px}
  details{margin-top:16px;text-align:left}
  summary{color:#a99fd0;font-size:13px;cursor:pointer;text-align:center}
  label{display:block;color:#a99fd0;font-size:12px;margin:12px 0 4px}
  input{width:100%;box-sizing:border-box;background:rgba(20,14,40,.8);border:1px solid rgba(246,198,81,.3);
        border-radius:11px;padding:11px 12px;font-size:16px;color:#eae3ff}
  button.ghost{width:100%;margin-top:14px;background:rgba(255,255,255,.08);color:#eae3ff;
         border:1px solid rgba(246,198,81,.35);border-radius:12px;padding:12px;font-size:14px}
  .hint{color:#8f85b8;font-size:12px;margin-top:16px;line-height:1.5}
</style></head>
<body><div class="card">
  <div class="hat">&#9875;</div>
  <h1>Connect to your phone</h1>
  <p class="sub">On your phone: The Cap &rarr; Settings &rarr; Car Mode</p>
  <button id="find" class="primary">Find &amp; connect</button>
  <div class="status" id="st"></div>
  <div class="err" id="err"></div>
  <details>
    <summary>Enter manually instead</summary>
    <label>Phone address</label>
    <input id="ip" type="text" inputmode="decimal" value="$prefHost" placeholder="192.168.1.4">
    <label>Pairing code</label>
    <input id="code" type="text" value="${CarLink.token}" placeholder="shown on the phone">
    <button id="go" class="ghost">Connect manually</button>
  </details>
  <div class="hint">Tip: make sure the car is on the same Wi-Fi/hotspot as the phone, then tap <b>Find &amp; connect</b> and, on the phone, tap <b>Pair a new car</b> — no codes.</div>
</div>
<script>
  function resetBtns(){var f=document.getElementById('find');if(f){f.disabled=false;f.textContent='Find & connect';}var g=document.getElementById('go');if(g){g.disabled=false;g.textContent='Connect manually';}}
  window.psError=function(m){document.getElementById('err').textContent=m||'';document.getElementById('st').textContent='';resetBtns();};
  window.psStatus=function(m){document.getElementById('st').textContent=m||'';document.getElementById('err').textContent='';};
  (function(){try{if(window.PirateNative&&PirateNative.gateway){var g=PirateNative.gateway();var ipf=document.getElementById('ip');if(g&&ipf&&!ipf.value)ipf.value=g;}}catch(e){}})();
  document.getElementById('find').onclick=function(){this.disabled=true;this.textContent='Searching…';document.getElementById('err').textContent='';try{PirateNative.findAndConnect();}catch(e){window.psError('Setup error');}};
  document.getElementById('go').onclick=function(){var ip=document.getElementById('ip').value,code=document.getElementById('code').value;document.getElementById('err').textContent='';this.disabled=true;this.textContent='Connecting…';try{PirateNative.connect(ip,code);}catch(e){window.psError('Setup error');}};
</script>
</body></html>
"""
    }
}
