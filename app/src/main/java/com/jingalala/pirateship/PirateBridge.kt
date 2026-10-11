package com.jingalala.pirateship

import android.webkit.JavascriptInterface

/**
 * window.PirateNative — the car's native layer, injected into the WebView. car-bridge.js (served
 * by the phone) calls these for the setup page and for car_* push commands the phone emits.
 * Everything hops to the UI thread before touching the Activity.
 */
class PirateBridge(private val act: MainActivity) {

    /** Setup page: prefill the phone IP with our DHCP gateway (the phone, on its hotspot). */
    @JavascriptInterface
    fun gateway(): String = CarLink.gatewayIp(act)

    /** Setup page: one-tap — auto-find the phone + grab the token during its pairing window. */
    @JavascriptInterface
    fun findAndConnect() { act.runOnUiThread { act.onFindAndConnect() } }

    /** Setup page: the user confirmed phone IP + pairing code (manual fallback). */
    @JavascriptInterface
    fun connect(host: String, token: String) { act.runOnUiThread { act.onConnectRequested(host, token) } }

    /** car_play — play this item in the car's own player. */
    @JavascriptInterface
    fun play(json: String) { act.runOnUiThread { act.onCarPlay(json) } }

    /** car_immersive — keep the car screen edge-to-edge. */
    @JavascriptInterface
    fun immersive(on: Boolean) { act.runOnUiThread { act.goImmersive() } }

    /** car_openurl — open a real URL (e.g. a key sign-up) in the car's browser. */
    @JavascriptInterface
    fun openUrl(url: String) { act.runOnUiThread { act.openExternal(url) } }

    @JavascriptInterface fun onConnected() { act.runOnUiThread { act.onLinkUp() } }
    @JavascriptInterface fun onDisconnected() { act.runOnUiThread { act.onLinkDown() } }
    @JavascriptInterface fun command(event: String, payload: String) { /* reserved for future car_* */ }
}
