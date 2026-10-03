package com.hughhowey.phony.plex

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal data class PlexSelection(val accountToken: String, val serverId: String, val libraryId: String) {
    val key get() = "$serverId/$libraryId"
}
internal data class PlexEndpoint(val url: String, val token: String)
internal data class PlexAlbum(val id: String, val title: String, val artist: String, val year: String, val thumb: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist).put("year", year).put("provider", "plex")
        .put("cover", if (thumb.isBlank()) "" else "https://appassets.androidplatform.net/plex-art/${PlexMusic.artKey(id, thumb)}")
}
internal data class PlexTrack(val id: String, val title: String, val artist: String, val duration: Long, val disc: Int, val number: Int, val part: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("artist", artist).put("dur", duration / 1000.0).put("disc", disc).put("number", number)
}
internal data class PlexPage<T>(val items: List<T>, val next: Int, val more: Boolean)

internal object PlexMusic {
    const val PAGE_SIZE = 50
    fun artKey(id: String, thumb: String) = java.security.MessageDigest.getInstance("SHA-256").digest((id + thumb).toByteArray()).joinToString("") { "%02x".format(it) }
    fun albums(data: JSONObject, offset: Int): PlexPage<PlexAlbum> {
        val container = data.getJSONObject("MediaContainer")
        val items = container.optJSONArray("Metadata") ?: JSONArray()
        val albums = (0 until items.length()).mapNotNull { i ->
            val a = items.getJSONObject(i)
            if (a.optString("type") != "album" || a.optString("ratingKey").isBlank()) null
            else PlexAlbum(a.getString("ratingKey"), a.optString("title", "Untitled album"), a.optString("parentTitle"),
                if (a.optInt("year") > 0) a.optInt("year").toString() else "", a.optString("thumb"))
        }
        val next = offset + items.length()
        if (container.has("offset") && container.getInt("offset") != offset) error("Unexpected Plex page")
        val more = items.length() > 0 && if (container.has("totalSize")) next < container.getInt("totalSize") else items.length() >= PAGE_SIZE
        return PlexPage(albums, next, more)
    }

    fun tracks(data: JSONObject, album: PlexAlbum): List<PlexTrack> {
        val items = data.getJSONObject("MediaContainer").optJSONArray("Metadata") ?: JSONArray()
        return (0 until items.length()).map { i ->
            val t = items.getJSONObject(i)
            require(t.optString("type") == "track") { "Unexpected album item" }
            val media = t.optJSONArray("Media") ?: JSONArray()
            var part = ""
            for (m in 0 until media.length()) {
                val parts = media.getJSONObject(m).optJSONArray("Part") ?: continue
                if (parts.length() != 1) continue // Multipart audio needs a different queue mapping.
                val p = parts.getJSONObject(0)
                if (!p.optBoolean("exists", true) || !p.optBoolean("accessible", true)) continue
                val key = p.optString("key")
                if (key.startsWith("/library/parts/")) { part = key; break }
            }
            require(part.isNotEmpty()) { "An album track is unavailable for direct playback" }
            PlexTrack(t.getString("ratingKey"), t.optString("title", "Untitled track"),
                t.optString("originalTitle").ifBlank { t.optString("grandparentTitle", album.artist) },
                t.optLong("duration"), t.optInt("parentIndex", 1), t.optInt("index", i + 1), part)
        }.sortedWith(compareBy({ it.disc }, { it.number }))
    }

    /** Only relative server paths: metadata cannot redirect credential-bearing requests. */
    fun url(base: String, path: String): String {
        val relative = URI(path)
        require(path.startsWith('/') && !path.startsWith("//") && relative.host == null && relative.scheme == null && relative.rawFragment == null)
        require(!path.contains('\\') && relative.normalize().rawPath == relative.rawPath)
        return base.trimEnd('/') + path
    }

    fun pauseAtSideEnd(count: Int, index: Int, autoReverse: Boolean) = !autoReverse && count >= 2 && index == (count + 1) / 2 - 1
}
