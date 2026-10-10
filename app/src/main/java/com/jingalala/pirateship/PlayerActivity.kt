package com.jingalala.pirateship

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The car's video player — bundled libVLC, so it plays virtually anything the phone streams
 * (MKV/HEVC/AV1 + odd codecs) with HW decode where the head unit supports it, SW decode otherwise.
 * The URL is already resolved to the phone's /media proxy by MainActivity.onCarPlay. Minimal,
 * steering-friendly controls: play/pause (tap or media key), seek (D-pad / media FF-REW), back exits.
 */
class PlayerActivity : Activity() {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var videoLayout: VLCVideoLayout
    private var url: String = ""
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra(EXTRA_URL) ?: ""
        if (url.isBlank()) { finish(); return }

        videoLayout = VLCVideoLayout(this)
        setContentView(videoLayout)
        goImmersive()
        videoLayout.setOnClickListener { togglePause() }

        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--network-caching=1500",   // ~1.5s buffer for Wi-Fi streaming
            "--audio-time-stretch"
        )
        val vlc = LibVLC(this, args)
        val mp = MediaPlayer(vlc)
        libVlc = vlc
        player = mp

        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)   // try hardware, fall back to software
        media.addOption(":network-caching=1500")
        mp.media = media
        media.release()

        mp.setEventListener { ev ->
            if (ev.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread { Toast.makeText(this, "Can't play this one", Toast.LENGTH_SHORT).show(); finish() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val mp = player ?: return
        mp.attachViews(videoLayout, null, false, false)
        if (!started) { mp.play(); started = true }
    }

    override fun onStop() {
        super.onStop()
        player?.detachViews()
    }

    override fun onDestroy() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        try { libVlc?.release() } catch (_: Exception) {}
        player = null; libVlc = null
        super.onDestroy()
    }

    private fun togglePause() {
        val mp = player ?: return
        if (mp.isPlaying) mp.pause() else mp.play()
    }

    private fun seekBy(ms: Long) {
        val mp = player ?: return
        val len = mp.length
        if (len > 0) {
            val t = (mp.time + ms).coerceIn(0, len)
            mp.time = t
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
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
        videoLayout.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
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
