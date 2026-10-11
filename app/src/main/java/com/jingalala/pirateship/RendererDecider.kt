package com.jingalala.pirateship

import android.content.Context
import android.os.Build
import android.webkit.WebView

/**
 * Picks the best renderer for THIS head unit. The phone always serves the same modern Cap UI; the
 * only question is which engine on the car can render it well:
 *   - SYSTEM_WEBVIEW:  the device's own WebView is modern enough (Chrome >= MIN_CHROME) → use it (cheap, no bundle).
 *   - GECKOVIEW:       WebView too old/stale but device is Android 5.0+ → bundled modern engine (full premium UI).
 *   - SYSTEM_FALLBACK: last resort (very old, no GeckoView available) → best-effort system WebView.
 *
 * Must be queried on the main thread (it briefly constructs a WebView to read its UA string).
 */
object RendererDecider {
    // Chrome major the modern Cap UI needs to render well. app.js only PARSES from Chrome 80
    // (optional chaining), and its newest CSS effects want ~111; 90 is a pragmatic "runs and looks
    // right" floor. Below it on a stale WebView we switch to the bundled GeckoView, which is always
    // modern. Tunable.
    const val MIN_CHROME = 90

    enum class Renderer { SYSTEM_WEBVIEW, GECKOVIEW, SYSTEM_FALLBACK }

    @Volatile private var cachedChrome = -1

    /** The system WebView's Chrome major version (0 if unknown). Reads the UA string (all APIs). */
    fun systemChromeMajor(ctx: Context): Int {
        if (cachedChrome >= 0) return cachedChrome
        cachedChrome = try {
            val ua = WebView(ctx).settings.userAgentString ?: ""
            Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 }
        return cachedChrome
    }

    fun pick(ctx: Context): Renderer {
        val chrome = systemChromeMajor(ctx)
        if (chrome >= MIN_CHROME) return Renderer.SYSTEM_WEBVIEW
        if (Build.VERSION.SDK_INT >= 21) return Renderer.GECKOVIEW
        return Renderer.SYSTEM_FALLBACK
    }
}
