package com.hughhowey.phony

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Songs saved on the phone, read from Android's media library. */
class Library(private val ctx: Context) {

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val durMs: Long,
        val trackNo: Int
    ) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

        fun toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()
    }

    @Volatile private var cache: List<Track>? = null

    fun invalidate() { cache = null }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun all(): List<Track> {
        cache?.let { return it }
        if (!hasPermission()) return emptyList()
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                while (c.moveToNext()) {
                    val artist = c.getString(iArtist)
                    out += Track(
                        id = c.getLong(iId),
                        title = c.getString(iTitle) ?: "Untitled",
                        artist = if (artist == null || artist == "<unknown>") "" else artist,
                        album = c.getString(iAlbum) ?: "",
                        albumId = c.getLong(iAlbumId),
                        durMs = c.getLong(iDur),
                        trackNo = c.getInt(iTrack)
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        cache = out
        return out
    }

    /** Song count plus albums (two or more songs) for the music chooser. */
    fun summaryJson(): String {
        val tracks = all()
        val albums = JSONArray()
        tracks.groupBy { it.albumId }
            .filter { it.value.size >= 2 }
            .values
            .sortedBy { it.first().album.lowercase() }
            .forEach { list ->
                val first = list.first()
                val artists = list.map { it.artist }.filter { it.isNotEmpty() }.distinct()
                albums.put(
                    JSONObject()
                        .put("id", first.albumId)
                        .put("title", first.album.ifEmpty { "Untitled album" })
                        .put("artist", if (artists.size == 1) artists[0] else if (artists.isEmpty()) "" else "Various artists")
                        .put("count", list.size)
                )
            }
        return JSONObject().put("count", tracks.size).put("albums", albums).toString()
    }

    fun tracksFor(type: String, id: String): List<Track> = when (type) {
        "album" -> {
            val albumId = id.toLongOrNull()
            all().filter { it.albumId == albumId }.sortedWith(compareBy<Track>({ it.trackNo }, { it.title.lowercase() }))
        }
        else -> all().shuffled()
    }

    /** Cover art for an album as a data URL, or "" when there is none. */
    fun albumArt(albumId: Long): String {
        val candidates = mutableListOf(ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId))
        all().firstOrNull { it.albumId == albumId }?.let { candidates += it.uri }
        for (uri in candidates) {
            try {
                val bmp = ctx.contentResolver.loadThumbnail(uri, Size(640, 640), null)
                return toDataUrl(bmp)
            } catch (e: Exception) {
                // try the next source
            }
        }
        return ""
    }

    companion object {
        fun audioPermission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(audioPermission())

        fun tracksJson(tracks: List<Track>): String {
            val arr = JSONArray()
            tracks.forEach {
                arr.put(
                    JSONObject()
                        .put("title", it.title)
                        .put("artist", it.artist)
                        .put("album", it.album)
                        .put("dur", it.durMs)
                )
            }
            return arr.toString()
        }

        fun toDataUrl(src: Bitmap): String {
            val max = 640
            val bmp = if (src.width > max || src.height > max) {
                val k = max.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
            } else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
