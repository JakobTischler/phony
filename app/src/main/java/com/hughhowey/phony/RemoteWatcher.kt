package com.hughhowey.phony

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android only lets an app see other apps' now-playing info once the user turns on
 * notification access for it. PHONY never reads notifications; this class exists so
 * that switch can be turned on.
 */
class PhonyNotificationListener : NotificationListenerService()

/** Follows whatever another app (Spotify, YouTube Music, …) is playing and drives it. */
class RemoteWatcher(private val ctx: Context) {

    @Volatile var enabled = false
    @Volatile var json: String = ""
        private set

    private var controller: MediaController? = null
    private var lastPick = 0L
    private var trackKey = ""
    @Volatile private var art: Bitmap? = null
    private var artKey = ""
    private var artCacheKey = ""
    private var artCache = ""

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun pick(): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(ComponentName(ctx, PhonyNotificationListener::class.java))
            .filter { it.packageName != ctx.packageName }
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.packageName == controller?.packageName }
            ?: sessions.firstOrNull()
    } catch (e: SecurityException) {
        null
    }

    /** Called on the main thread about 20 times a second. */
    fun poll() {
        if (!enabled) { json = ""; return }
        val now = SystemClock.elapsedRealtime()
        if (controller == null || now - lastPick > 1500) {
            lastPick = now
            controller = pick()
        }
        val c = controller ?: run { json = ""; return }
        val md = try { c.metadata } catch (e: Exception) { null } ?: run { json = ""; return }
        val st = try { c.playbackState } catch (e: Exception) { null }

        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.description?.title?.toString() ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.description?.subtitle?.toString() ?: ""
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val albumArtist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val key = "$title|$album"
        if (key != trackKey) { trackKey = key; art = null }
        if (art == null) {
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        artKey = key + if (art != null) "#art" else ""

        val queue = JSONArray()
        try {
            c.queue?.take(20)?.forEach { q ->
                queue.put(
                    JSONObject()
                        .put("id", q.queueId)
                        .put("title", q.description.title?.toString() ?: "")
                        .put("artist", q.description.subtitle?.toString() ?: "")
                )
            }
        } catch (e: Exception) { /* some apps don't share a queue */ }

        json = JSONObject()
            .put("app", appName(c.packageName))
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("albumArtist", albumArtist)
            .put("dur", if (dur > 0) dur else 0)
            .put("pos", pos.coerceAtLeast(0))
            .put("playing", playing)
            .put("queueTitle", try { c.queueTitle?.toString() ?: "" } catch (e: Exception) { "" })
            .put("artKey", artKey)
            .put("hasArt", art != null)
            .put("queue", queue)
            .toString()
    }

    @Synchronized
    fun artDataUrl(): String {
        val bmp = art ?: return ""
        if (artCacheKey != artKey) {
            artCache = try { Library.toDataUrl(bmp) } catch (e: Exception) { "" }
            artCacheKey = artKey
        }
        return artCache
    }

    private fun spotify(): MediaController? = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(ctx, PhonyNotificationListener::class.java))
            .firstOrNull { it.packageName.startsWith("com.spotify.music") }
    } catch (e: SecurityException) { null }

    /** Ask Spotify's player to play a spotify: link. False if Spotify has no player open. */
    fun playFromUri(uri: String): Boolean {
        val c = spotify() ?: return false
        return try { c.transportControls.playFromUri(android.net.Uri.parse(uri), android.os.Bundle()); true } catch (e: Exception) { false }
    }

    /** Whether Spotify's player now shows a song from this album. */
    fun playingAlbum(title: String): Boolean {
        val c = spotify() ?: return false
        val md = try { c.metadata } catch (e: Exception) { null } ?: return false
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: return false
        return album.trim().equals(title.trim(), ignoreCase = true)
    }

    /** Whether Spotify's player now names its queue after this playlist. */
    fun playingQueue(title: String): Boolean {
        val c = spotify() ?: return false
        val q = try { c.queueTitle?.toString() } catch (e: Exception) { null } ?: return false
        return q.trim().equals(title.trim(), ignoreCase = true)
    }

    /** Called on the main thread. */
    fun command(cmd: String, arg: String) {
        val tc = controller?.transportControls ?: return
        try {
            when (cmd) {
                "play" -> tc.play()
                "pause" -> tc.pause()
                "next" -> tc.skipToNext()
                "prev" -> tc.skipToPrevious()
                "seek" -> arg.toLongOrNull()?.let { tc.seekTo(it) }
                "queue" -> arg.toLongOrNull()?.let { tc.skipToQueueItem(it) }
            }
        } catch (e: Exception) { /* the other app went away */ }
    }

    private val names = HashMap<String, String>()
    private fun appName(pkg: String): String = names.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
