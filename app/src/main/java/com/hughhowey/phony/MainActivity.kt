package com.hughhowey.phony

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.webkit.WebViewAssetLoader
import com.google.common.util.concurrent.ListenableFuture

/**
 * The whole player UI is a web page bundled in the app (assets/index.html).
 * This activity shows it full screen and gives it a small bridge ("PhonyNative")
 * to the phone's music library, the playback service, and other apps' players.
 * The page picks the closed or open look from the screen's shape, so folding
 * and unfolding never reloads it.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var library: Library
    private lateinit var remote: RemoteWatcher
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager

    @Volatile private var itemCount = 0
    @Volatile private var stateJson = """{"playing":false,"index":0,"pos":0,"dur":0,"ended":false}"""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            library.invalidate()
            refreshPage()
        }

    private val tick = object : Runnable {
        override fun run() {
            updateState()
            remote.poll()
            main.postDelayed(this, 50)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setBackgroundDrawable(null)

        library = Library(this)
        remote = RemoteWatcher(this)
        audio = getSystemService(AudioManager::class.java)
        lockOrientation()

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.textZoom = 100
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "PhonyNative")
        }
        setContentView(web)
        hideSystemBars()
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = try { future.get() } catch (e: Exception) { null }
        }, ContextCompat.getMainExecutor(this))

        main.post(tick)
    }

    /**
     * Cover screen: always upright (the closed player). Inner screen: always wide, so
     * turning the open phone never swaps the cassette bay for the closed view.
     * Both still flip 180° with the sensor.
     */
    private fun lockOrientation() {
        val inner = resources.configuration.smallestScreenWidthDp >= 600
        val want = if (inner) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lockOrientation()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        library.invalidate()
        refreshPage()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        web.destroy()
        super.onDestroy()
    }

    /** Lets the page redraw its music chooser after a permission prompt or a trip to settings. */
    private fun refreshPage() {
        if (::web.isInitialized) web.evaluateJavascript("window.phonyRefresh && window.phonyRefresh()", null)
    }

    private fun updateState() {
        val c = controller ?: return
        itemCount = c.mediaItemCount
        val dur = c.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        val playing = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING)
        val ended = c.playbackState == Player.STATE_ENDED
        stateJson = """{"playing":$playing,"index":${c.currentMediaItemIndex},"pos":${c.currentPosition.coerceAtLeast(0)},"dur":$dur,"ended":$ended}"""
    }

    private fun onMain(block: () -> Unit) { main.post(block) }

    /**
     * Everything the page can ask the phone to do. These run on the page's own thread,
     * so anything touching the player is handed to the main thread.
     */
    inner class Bridge {

        // ----- songs saved on the phone -----
        @JavascriptInterface fun hasAudioPermission(): Boolean = library.hasPermission()

        @JavascriptInterface fun requestAudioPermission() = onMain { permissionLauncher.launch(Library.permissions()) }

        @JavascriptInterface fun getLibrary(): String = library.summaryJson()

        @JavascriptInterface
        fun loadSource(type: String, id: String): String {
            val key = "$type:$id"
            // Reopening the app shouldn't restart what's already playing.
            NowLoaded.tracks?.let { if (NowLoaded.key == key && itemCount > 0) return Library.tracksJson(it) }
            val tracks = library.tracksFor(type, id)
            NowLoaded.key = key
            NowLoaded.tracks = tracks
            val items = tracks.map { it.toMediaItem() }
            onMain {
                controller?.run {
                    setMediaItems(items)
                    prepare()
                }
            }
            return Library.tracksJson(tracks)
        }

        @JavascriptInterface fun getAlbumArt(id: String): String = library.albumArt(id.toLongOrNull() ?: -1L)

        @JavascriptInterface fun getState(): String = stateJson

        @JavascriptInterface fun play() = onMain {
            controller?.run {
                if (playbackState == Player.STATE_ENDED) seekTo(0, 0L)
                if (playbackState == Player.STATE_IDLE) prepare()
                play()
            }
        }

        @JavascriptInterface fun pause() = onMain { controller?.pause() }

        @JavascriptInterface fun seekTo(ms: Long) = onMain { controller?.seekTo(ms.coerceAtLeast(0L)) }

        @JavascriptInterface fun skipTo(index: Int) = onMain {
            controller?.run { if (index in 0 until mediaItemCount) seekTo(index, 0L) }
        }

        /** Tape speed wobble: pitch moves with speed, like a real motor. */
        @JavascriptInterface fun setSpeed(rate: Float) = onMain {
            val r = rate.coerceIn(0.8f, 1.2f)
            controller?.setPlaybackParameters(PlaybackParameters(r, r))
        }

        /** The wheel works the phone's media volume, same as the side buttons (Bluetooth included). */
        @JavascriptInterface fun setVolume(v: Float) = onMain {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = Math.round(v.coerceIn(0f, 1f) * max)
            if (index != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }

        @JavascriptInterface fun getVolume(): Float {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            return if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else -1f
        }

        // ----- whatever another app is playing -----
        @JavascriptInterface fun hasListenerAccess(): Boolean = remote.hasAccess()

        @JavascriptInterface fun openListenerSettings() = onMain {
            val detail = if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this@MainActivity, PhonyNotificationListener::class.java).flattenToString()
                )
            } else null
            try {
                startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        @JavascriptInterface fun useRemote(on: Boolean) {
            remote.enabled = on
            if (on) onMain { controller?.pause() }
        }

        @JavascriptInterface fun getRemote(): String = remote.json

        @JavascriptInterface fun getRemoteArt(): String = remote.artDataUrl()

        @JavascriptInterface fun remoteCmd(cmd: String, arg: String) = onMain { remote.command(cmd, arg) }

        // ----- feel -----
        @JavascriptInterface
        fun haptic(level: Int) {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            v.vibrate(
                VibrationEffect.createPredefined(
                    if (level >= 2) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
                )
            )
        }
    }
}

/** What's loaded into the player, kept for the life of the app process. */
object NowLoaded {
    @Volatile var key: String = ""
    @Volatile var tracks: List<Library.Track>? = null
}
