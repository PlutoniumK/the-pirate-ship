package com.jingalala.pirateship

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.VideoView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The car's video player.
 *
 * PRIMARY = Android's framework VideoView / MediaPlayer — the SAME system media pipeline the head
 * unit uses for YouTube. It demuxes MKV, hardware-decodes H.264 / HEVC / VP9 (and software-decodes
 * the rest where the OS has a decoder) AND, crucially, renders to the surface the normal way.
 *
 * FALLBACK = bundled libVLC, only when the system player reports it can't handle a stream. libVLC
 * carries its own decoders (so it covers odd codecs the OS lacks), BUT on many head units its video
 * output never attaches to the surface — audio plays while the screen stays black. That libVLC
 * render failure is the "audio but no video" bug; trying the system player FIRST fixes it, because
 * the system player renders through the exact path that already shows YouTube on the unit.
 *
 * The URL is already resolved to the phone's /media proxy by MainActivity.onCarPlay. Minimal,
 * steering-friendly controls: play/pause (tap or media key), seek (D-pad / media FF-REW), back exits.
 */
class PlayerActivity : Activity() {

    private var url: String = ""
    private var title: String = ""
    private lateinit var root: FrameLayout

    // Primary: framework player.
    private var videoView: VideoView? = null

    // Fallback: libVLC.
    private var libVlc: LibVLC? = null
    private var vlcPlayer: org.videolan.libvlc.MediaPlayer? = null
    private var usingVlc = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: ""
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (url.isBlank()) { finish(); return }

        root = FrameLayout(this)
        setContentView(root)
        goImmersive()
        root.setOnClickListener { togglePause() }

        startSystemPlayer()
    }

    /** PRIMARY path: the framework VideoView (system MediaPlayer + SurfaceView). Renders through the
     *  head unit's own pipeline — the one that already plays YouTube — so video actually shows. */
    private fun startSystemPlayer() {
        try {
            val vv = VideoView(this)
            vv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
            root.addView(vv)
            videoView = vv
            vv.setOnPreparedListener { player ->
                try { player.isLooping = false } catch (_: Exception) {}
                diag("sys_ok")
                vv.start()
            }
            vv.setOnErrorListener { _, _, _ ->
                // The system player can't handle this stream → hand off to libVLC's bundled decoders.
                try { root.removeView(vv) } catch (_: Exception) {}
                videoView = null
                diag("sys_fail")
                startVlcFallback()
                true   // we handled it (no system error dialog)
            }
            vv.setVideoURI(Uri.parse(url))
            vv.requestFocus()
        } catch (e: Exception) {
            diag("sys_exc")
            startVlcFallback()
        }
    }

    /** FALLBACK path: libVLC (bundled universal decoders). Used only when the system player failed. */
    private fun startVlcFallback() {
        if (usingVlc) return
        usingVlc = true
        try {
            val layout = VLCVideoLayout(this)
            layout.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            root.addView(layout)

            val args = arrayListOf(
                "--no-drop-late-frames", "--no-skip-frames",
                "--network-caching=1500", "--audio-time-stretch"
            )
            val vlc = LibVLC(this, args)
            val mp = org.videolan.libvlc.MediaPlayer(vlc)
            libVlc = vlc; vlcPlayer = mp

            val media = Media(vlc, Uri.parse(url))
            media.setHWDecoderEnabled(true, false)   // try hardware, fall back to software
            media.addOption(":network-caching=1500")
            mp.media = media
            media.release()

            mp.setEventListener { ev ->
                if (ev.type == org.videolan.libvlc.MediaPlayer.Event.EncounteredError) {
                    runOnUiThread { Toast.makeText(this, "Can't play this one", Toast.LENGTH_SHORT).show(); finish() }
                }
            }
            mp.attachViews(layout, null, false, false)
            mp.play()
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Can't play this one", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    /** Best-effort diagnostic ping back to the phone (POST /call → car_diag, same channel the car's
     *  web layer uses), so whether the system player rendered is visible in the owner's events.
     *  Fire-and-forget; never blocks playback. */
    private fun diag(label: String) {
        val u = url
        Thread {
            try {
                val base = u.substringBefore("/media")
                if (!base.startsWith("http")) return@Thread
                val token = Regex("[?&]token=([^&]+)").find(u)?.groupValues?.get(1) ?: ""
                val meta = org.json.JSONObject().put("title", title).toString()
                val body = org.json.JSONObject()
                    .put("name", "car_diag")
                    .put("args", org.json.JSONArray().put("player_$label").put(meta).put(0))
                    .toString()
                val c = java.net.URL("$base/call").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 2000; c.readTimeout = 2000; c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) c.setRequestProperty("X-Cap-Token", token)
                c.outputStream.use { it.write(body.toByteArray()) }
                c.inputStream.use { it.read() }
                c.disconnect()
            } catch (_: Throwable) {}
        }.start()
    }

    override fun onDestroy() {
        try { videoView?.stopPlayback() } catch (_: Exception) {}
        try { vlcPlayer?.stop() } catch (_: Exception) {}
        try { vlcPlayer?.release() } catch (_: Exception) {}
        try { libVlc?.release() } catch (_: Exception) {}
        videoView = null; vlcPlayer = null; libVlc = null
        super.onDestroy()
    }

    private fun togglePause() {
        if (usingVlc) { val p = vlcPlayer ?: return; if (p.isPlaying) p.pause() else p.play(); return }
        val vv = videoView ?: return
        if (vv.isPlaying) vv.pause() else vv.start()
    }

    private fun seekBy(ms: Int) {
        if (usingVlc) {
            val p = vlcPlayer ?: return
            val len = p.length
            if (len > 0) p.time = (p.time + ms).coerceIn(0, len)
            return
        }
        val vv = videoView ?: return
        val dur = vv.duration
        if (dur > 0) vv.seekTo((vv.currentPosition + ms).coerceIn(0, dur))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (usingVlc) vlcPlayer?.play() else videoView?.start(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (usingVlc) vlcPlayer?.pause() else videoView?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { seekBy(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        root.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun start(ctx: Context, url: String, title: String) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
