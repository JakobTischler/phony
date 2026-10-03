package com.hughhowey.phony.plex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/** Paged album catalog, separate from authentication UI and Spotify's box. */
internal class PlexCatalog(context: Context, private val changed: () -> Unit) {
    private val api = PlexApi(PlexStore(context).clientId)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val cache = File(context.cacheDir, "plex-art").apply { mkdirs() }
    @Volatile private var selected: PlexSelection? = null
    @Volatile private var endpoint: PlexEndpoint? = null
    @Volatile private var albums = emptyList<PlexAlbum>()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var playVersion = 0
    private var offset = 0
    private var query = ""
    private var more = false
    private var loading = false
    private var error = ""
    @Volatile private var snapshot = "{}"

    fun status() = snapshot
    fun configure(selection: PlexSelection?) {
        if (selection == selected) return
        generation++; cancelPlay(); selected = selection; endpoint = null; albums = emptyList()
        offset = 0; query = ""; more = false; loading = false; error = ""
        if (selection == null || !PlexPlayback.belongsTo(selection.key)) PlexPlayback.clear()
        publish()
    }
    fun cancelPlay() { playVersion++ }
    private fun publish() {
        val selection = selected
        snapshot = JSONObject().put("key", selection?.key.orEmpty()).put("query", query).put("loading", loading).put("more", more)
            .put("error", error).put("albums", JSONArray(albums.map { album ->
                album.copy(id = identity(selection!!, album.id)).json().put("ratingKey", album.id)
            })).toString()
        if (!closed) changed()
    }

    private fun discover(selection: PlexSelection): PlexEndpoint {
        val server = api.servers(selection.accountToken).find { it.id == selection.serverId }
            ?: error("Server is no longer available")
        for (url in server.connections) {
            if (closed || Thread.currentThread().isInterrupted) throw InterruptedException()
            try {
                if (api.libraries(url, server.token).any { it.id == selection.libraryId }) return PlexEndpoint(url, server.token)
            } catch (_: Exception) { }
        }
        error("Music library is not reachable")
    }

    private fun <T> request(selection: PlexSelection, task: (PlexEndpoint) -> T): Pair<PlexEndpoint, T> {
        val cached = endpoint
        if (cached != null) try { return cached to task(cached) } catch (_: Exception) { }
        val fresh = discover(selection)
        return fresh to task(fresh)
    }

    fun load(reset: Boolean) {
        val selection = selected ?: return
        if (loading || closed || (!reset && albums.isNotEmpty() && !more)) return
        val version = generation
        val start = if (reset) 0 else offset
        loading = true; error = ""; publish()
        worker.execute {
            val search = query
            val result = runCatching { request(selection) { api.albums(it, selection.libraryId, start, search) } }
            main.post {
                if (closed || version != generation) return@post
                loading = false
                result.onSuccess { (address, page) ->
                    endpoint = address
                    val before = if (reset) emptyList() else albums
                    val combined = (before + page.items).distinctBy { it.id }
                    // A proxy that ignores the requested page would otherwise make the box appear
                    // to loop through its first albums forever.
                    val advanced = page.next > start
                    val added = combined.size > before.size
                    albums = combined
                    offset = page.next
                    more = page.more && advanced && (reset || added)
                    if (!reset && page.items.isNotEmpty() && !added) error = "Plex returned the same album page. Refresh the tape box to try again."
                }.onFailure { error = "Could not load Plex albums. Check your server and connection, then retry." }
                publish()
            }
        }
    }

    fun search(value: String) {
        val normalized = PlexMusic.searchQuery(value)
        if (normalized == query || selected == null || closed) return
        generation++; cancelPlay(); query = normalized; albums = emptyList(); offset = 0; more = false; loading = false; error = ""
        publish()
        load(true)
    }

    fun play(id: String, done: (PlexAlbum?, List<PlexTrack>, PlexEndpoint?, String) -> Unit) {
        val selection = selected
        val album = selection?.let { s -> albums.find { identity(s, it.id) == id } }
        if (selection == null || album == null) { done(null, emptyList(), null, "This library has changed. Refresh the tape box and try again."); return }
        val version = generation
        val play = ++playVersion
        worker.execute {
            val result = runCatching { request(selection) { api.tracks(it, album).also { tracks -> require(tracks.isNotEmpty()) } } }
            main.post {
                if (closed || version != generation || play != playVersion) return@post
                result.onSuccess { (address, tracks) ->
                    endpoint = address
                    done(album.copy(id = identity(selection, album.id)), tracks, address, "")
                }.onFailure { done(null, emptyList(), null, "Could not load this album. Check the server and that all its tracks are available for direct playback.") }
            }
        }
    }

    /** Called on a WebView request thread; image URLs contain only an opaque cache key. */
    fun art(key: String): WebResourceResponse {
        fun empty() = WebResourceResponse("image/jpeg", null, 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val selection = selected ?: return empty()
        if (!key.matches(Regex("[a-f0-9]{64}"))) return empty()
        return try {
            val file = File(cache, "$key.jpg")
            val bytes = if (file.exists()) file.readBytes() else {
                val address = endpoint ?: return empty()
                val album = albums.find { PlexMusic.artKey(identity(selection, it.id), it.thumb) == key } ?: return empty()
                if (album.thumb.isBlank()) return empty()
                val original = api.image(address, album.thumb)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 640).coerceAtLeast(1) }
                val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size, options) ?: return empty()
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out); bitmap.recycle()
                out.toByteArray().also { data ->
                    // Atomic replacement: two WebViews may request the same cover together.
                    val temp = File.createTempFile("cover", ".tmp", cache)
                    try { temp.writeBytes(data); temp.renameTo(file) } finally { temp.delete() }
                }
            }
            if (selection != selected) empty() else WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes))
        } catch (_: Exception) { empty() }
    }

    fun close() { closed = true; generation++; cancelPlay(); worker.shutdownNow() }
    private fun identity(s: PlexSelection, id: String) = "plex:${s.key}:$id"
}
