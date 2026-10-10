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
    }

    // ---- loading ----
    fun loadPhoneUi() { web.loadUrl(CarLink.uiUrl()) }

    private fun loadSetup() {
        web.loadDataWithBaseURL("http://pair.local/", setupHtml(), "text/html", "utf-8", null)
    }

    /** From the setup page: verify the phone answers, then save + load, else show an error. */
    fun onConnectRequested(host: String, token: String) {
        val h = host.trim(); val t = token.trim()
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

    // ---- car_* native actions ----
    fun onCarPlay(json: String) {
        // 2f will launch the bundled libVLC player here. For now, resolve the URL + acknowledge.
        val url = try { JSONObject(json).optString("url", "") } catch (e: Exception) { "" }
        val playable = rewriteLoopback(url)
        if (playable.isBlank()) { toast("Nothing to play"); return }
        toast("Playing on the car…")
        // TODO(2f): startActivity(PlayerActivity.intent(this, playable, title, …))
    }

    fun openExternal(url: String) {
        if (url.isBlank()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("No browser on this screen") }
    }

    fun onLinkUp() { /* connected — reserved for a status chip */ }
    fun onLinkDown() { /* disconnected — car-bridge.js auto-reconnects the WS */ }

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
  .card{width:92%;max-width:520px;background:rgba(40,28,74,.7);border:1px solid rgba(246,198,81,.35);
        border-radius:18px;padding:22px 24px;box-shadow:0 8px 30px rgba(0,0,0,.4)}
  .hat{font-size:40px;text-align:center}
  h1{color:#f6c651;font-size:22px;font-weight:800;text-align:center;margin:6px 0 2px}
  p.sub{color:#a99fd0;font-size:13px;text-align:center;margin:0 0 16px}
  label{display:block;color:#a99fd0;font-size:12px;margin:12px 0 4px}
  input{width:100%;box-sizing:border-box;background:rgba(20,14,40,.8);border:1px solid rgba(246,198,81,.3);
        border-radius:11px;padding:11px 12px;font-size:16px;color:#eae3ff}
  button{width:100%;margin-top:18px;background:#f6c651;color:#2a1850;font-weight:800;border:none;
         border-radius:12px;padding:13px;font-size:15px}
  .err{color:#ff8a8a;font-size:13px;margin-top:12px;min-height:16px;text-align:center}
  .hint{color:#8f85b8;font-size:12px;margin-top:14px;line-height:1.5}
</style></head>
<body><div class="card">
  <div class="hat">&#9875;</div>
  <h1>Connect to your phone</h1>
  <p class="sub">On your phone: The Cap &rarr; Settings &rarr; Car Mode &rarr; Turn on</p>
  <label>Phone address</label>
  <input id="ip" type="text" inputmode="decimal" value="$prefHost" placeholder="192.168.43.1">
  <label>Pairing code</label>
  <input id="code" type="text" value="${CarLink.token}" placeholder="shown on the phone">
  <button id="go">Connect</button>
  <div class="err" id="err"></div>
  <div class="hint">Make sure the car's Wi-Fi is joined to the phone's hotspot first. After this, the car reconnects on its own.</div>
</div>
<script>
  (function(){
    try{ if(window.PirateNative&&PirateNative.gateway){ var g=PirateNative.gateway(); if(g&&!document.getElementById('ip').value) document.getElementById('ip').value=g; } }catch(e){}
    window.psError=function(m){ document.getElementById('err').textContent=m||''; var b=document.getElementById('go'); if(b){b.disabled=false;b.textContent='Connect';} };
    document.getElementById('go').onclick=function(){
      var ip=document.getElementById('ip').value, code=document.getElementById('code').value;
      document.getElementById('err').textContent='';
      this.disabled=true; this.textContent='Connecting…';
      try{ PirateNative.connect(ip, code); }catch(e){ window.psError('Setup error'); }
    };
  })();
</script>
</body></html>
"""
    }
}
