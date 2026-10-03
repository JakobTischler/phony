package com.hughhowey.phony.plex

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject

/** Process-local queue owned by the media service, not the Activity or WebView. */
internal object PlexPlayback {
    private var player: ExoPlayer? = null
    private var album: PlexAlbum? = null
    private var tracks = emptyList<PlexTrack>()
    private var autoReverse = false
    private var sideEnd = false
    private var flipped = false
    @Volatile private var now = ""
    @Volatile private var streams = emptyMap<String, Pair<PlexEndpoint, String>>()

    fun stream(uri: String) = streams[uri]
    fun nowJson() = now
    fun belongsTo(key: String) = album?.id?.startsWith("plex:$key:") == true
    private fun active() = album != null && player?.currentMediaItem?.mediaId?.startsWith("plex:") == true

    fun attach(value: ExoPlayer) {
        player = value
        value.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateBoundary() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { sideEnd = false; flipped = false }
                updateBoundary()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!active()) return
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) sideEnd = true
                if (playWhenReady) { sideEnd = false; flipped = false }
            }
        })
    }

    fun start(value: PlexAlbum, songs: List<PlexTrack>, address: PlexEndpoint, reverse: Boolean): String {
        val p = player ?: error("Player is not ready")
        p.pause()
        album = value; tracks = songs; autoReverse = reverse; sideEnd = false; flipped = false
        val items = songs.map { song ->
            val id = "${value.id}:${song.id}"
            val uri = Uri.Builder().scheme("phony-plex").authority("track").appendPath(id).build()
            MediaItem.Builder().setMediaId(id).setUri(uri)
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(value.title).build()).build()
        }
        streams = items.zip(songs).associate { (item, song) -> item.localConfiguration!!.uri.toString() to (address to song.part) }
        now = value.json().put("tracks", JSONArray(songs.map { it.json() })).toString()
        p.setMediaItems(items); updateBoundary(); p.prepare(); p.play()
        return now
    }

    private fun updateBoundary() {
        val p = player ?: return
        p.pauseAtEndOfMediaItems = active() && PlexMusic.pauseAtSideEnd(tracks.size, p.currentMediaItemIndex, autoReverse)
    }
    fun setAutoReverse(value: Boolean) { autoReverse = value; updateBoundary() }
    fun finishSide() {
        val p = player ?: return
        if (!active() || autoReverse || tracks.size < 2) return
        val last = (tracks.size + 1) / 2 - 1
        val end = if (p.currentMediaItemIndex == last && p.duration > 0) p.duration else tracks[last].duration
        p.pause(); p.seekTo(last, end.coerceAtLeast(0)); sideEnd = true; flipped = false
    }
    fun flip() {
        val p = player ?: return
        if (!active() || !sideEnd) return
        p.pause(); p.seekTo((tracks.size + 1) / 2, 0); sideEnd = false; flipped = true
    }

    fun state(): String? {
        if (!active()) return null
        val p = player ?: return null
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val error = p.playerError?.let {
            when (it.errorCode) {
                2001, 2002, 2004 -> "Plex playback lost its connection. Check your server and press Play to retry."
                else -> "This track could not play directly. Check its format and server availability, then press Play to retry."
            }
        }.orEmpty()
        return JSONObject().put("plexId", album!!.id).put("playing", p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
            .put("buffering", p.playbackState == Player.STATE_BUFFERING).put("index", p.currentMediaItemIndex)
            .put("pos", p.currentPosition.coerceAtLeast(0)).put("dur", duration).put("ended", p.playbackState == Player.STATE_ENDED)
            .put("sideEnd", sideEnd).put("flipped", flipped).put("error", error).toString()
    }

    fun clear() {
        if (active()) { player?.stop(); player?.clearMediaItems() }
        album = null; tracks = emptyList(); streams = emptyMap(); now = ""; sideEnd = false; flipped = false
        player?.pauseAtEndOfMediaItems = false
    }
    fun detach() { clear(); player = null }
}
