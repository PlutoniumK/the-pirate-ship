package com.jingalala.pirateship

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * The Pirate Ship — car head-unit companion to The Cap.
 *
 * Skeleton (Phase 2a): a full-screen, landscape, immersive WebView. From 2d on it loads the Cap UI
 * served by the paired phone (http://<phone>:8099/ui/?t=<token>), injects the networked bridge, and
 * hands car_play off to a bundled libVLC player. For now it shows a themed "connecting" placeholder
 * so the app installs, launches full-screen, and is ready to wire up.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        // On-device remote debugging (chrome://inspect) — invaluable on weird head units.
        WebView.setWebContentsDebuggingEnabled(true)

        setContentView(web)
        goImmersive()
        web.loadDataWithBaseURL(null, PLACEHOLDER, "text/html", "utf-8", null)
    }

    private fun goImmersive() {
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

    override fun onDestroy() {
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        // Themed to match The Cap (twilight + gold). Deliberately ES5/flex-only + no CSS vars so it
        // renders even on a KitKat WebView.
        private const val PLACEHOLDER = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  html,body{height:100%;margin:0}
  body{background:#160d2c;color:#eae3ff;font-family:'Segoe UI',Roboto,sans-serif;
       display:flex;align-items:center;justify-content:center;text-align:center}
  .wrap{display:flex;flex-direction:column;align-items:center}
  .hat{font-size:64px;line-height:1}
  h1{color:#f6c651;font-size:26px;font-weight:800;letter-spacing:1px;margin:14px 0 6px}
  p{color:#a99fd0;font-size:14px;margin:0}
  .dot{width:8px;height:8px;border-radius:50%;background:#f6c651;display:inline-block;margin:0 3px;
       opacity:.3;animation:b 1.2s infinite}
  .dot:nth-child(2){animation-delay:.2s}.dot:nth-child(3){animation-delay:.4s}
  @keyframes b{0%,100%{opacity:.3}50%{opacity:1}}
  .dots{margin-top:16px}
</style></head>
<body><div class="wrap">
  <div class="hat">&#9875;</div>
  <h1>The Pirate Ship</h1>
  <p>Looking for your phone&hellip;</p>
  <div class="dots"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>
</div></body></html>
"""
    }
}
