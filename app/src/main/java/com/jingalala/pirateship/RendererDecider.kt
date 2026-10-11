package com.jingalala.pirateship

import android.content.Context
import android.webkit.WebView

/**
 * The phone always serves the same modern Cap UI; the only question is whether THIS head unit's
 * WebView can render it well. We detect the system WebView's Chrome version and, if it's too old,
 * nudge the user to update "Android System WebView" (an updatable app on Android 7+). This gives a
 * real modern Chromium engine with NO changes to our bridge/interception, and the app still
 * proceeds best-effort if they skip it.
 *
 * (GeckoView was evaluated and rejected: as of 2025 it requires Android 8.0+, so it can't run on
 * the old Android 5/6/7 units this was meant to rescue, and it would force an async bridge rewrite
 * + ~150 MB of binary. The update-nudge reaches more devices for far less.)
 *
 * Must be queried on the main thread (it briefly constructs a WebView to read its UA string).
 */
object RendererDecider {
    // Chrome major the modern Cap UI needs to render well. app.js only PARSES from Chrome 80
    // (optional chaining); its newest CSS effects want ~111. 90 is a pragmatic "runs and looks
    // right" floor — below it (on a real but stale WebView) we nudge an update. Tunable.
    const val MIN_CHROME = 90

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

    /** True when the device HAS a WebView but it's too old for the modern UI → nudge an update.
     *  Unknown (0) is treated as fine, so we never nag when we can't tell. */
    fun webViewOutdated(ctx: Context): Boolean = systemChromeMajor(ctx) in 1 until MIN_CHROME
}
