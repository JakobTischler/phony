package com.hughhowey.phony

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * What goes on the fold-out J-card: the album's songs and credits and a photo of the
 * band (Spotify), the story of the record and the band (Wikipedia), and the words to
 * each song (LRCLIB, an open lyrics library).
 *
 * Everything found is kept on the phone (files/jcards), so a J-card opened once opens
 * again with no signal. On Wi-Fi, PHONY also works through every album in the tape box
 * in the background, a little at a time, so their J-cards are ready before you need them.
 *
 * The page asks by id; it's told when the answer is ready and reads it with take().
 */
class LinerNotes(private val ctx: Context, private val box: SpotifyBox, private val ready: (String) -> Unit) {

    private val results = ConcurrentHashMap<String, String>()
    private val dir = File(ctx.filesDir, "jcards").apply { mkdirs() }
    private val imgDir = File(dir, "img").apply { mkdirs() }
    /** Set when a request failed for lack of signal, so a half-fetched card isn't saved. */
    private val netFailed = ThreadLocal.withInitial { false }

    fun take(id: String): String = results.remove(id) ?: ""

    // ---------- the album ----------

    fun album(id: String, title: String, artist: String, albumUri: String) {
        Thread {
            results[id] = albumNow(title, artist, albumUri, onDemand = true)?.let { withImages(it) } ?: JSONObject().put("title", title).put("artist", artist).toString()
            ready(id)
        }.start()
    }

    /** The saved card, or fetch (and save) it. Null only when there's nothing and no signal. */
    private fun albumNow(title: String, artist: String, albumUri: String, onDemand: Boolean): String? {
        val keys = albumKeys(title, artist, albumUri)
        keys.firstNotNullOfOrNull { read("n", it) }?.let { return it }
        if (!online()) return null
        netFailed.set(false)
        val out = JSONObject().put("title", title).put("artist", artist)
        val found = try { spotifyPart(out, title, artist, albumUri) } catch (e: IOException) { netFailed.set(true); null } catch (e: Exception) { null }
        val t = out.optString("title", title); val a = firstArtist(out.optString("artist", artist))
        try { wikiAlbum(t, a)?.let { out.put("about", it.first).put("aboutUrl", it.second) } } catch (e: Exception) { }
        try { wikiBand(a)?.let { out.put("band", it.first).put("bandUrl", it.second) } } catch (e: Exception) { }
        val s = out.toString()
        // only keep a card that was fetched whole
        if (!netFailed.get()) (keys + listOfNotNull(found?.let { "uri:spotify:album:$it" })).distinct().forEach { write("n", it, s) }
        return s
    }

    /** Returns the Spotify album id it found. Images are saved as files; the card names them. */
    private fun spotifyPart(out: JSONObject, title: String, artist: String, albumUri: String): String? {
        var albumId = albumUri.takeIf { it.startsWith("spotify:album:") }?.substringAfterLast(':')
        if (albumId == null && title.isNotBlank()) {
            val q = enc("album:${clean(title)} artist:${firstArtist(artist)}")
            val items = sget("https://api.spotify.com/v1/search?type=album&limit=5&q=$q")
                ?.optJSONObject("albums")?.optJSONArray("items") ?: JSONArray()
            var pick: JSONObject? = null
            for (i in 0 until items.length()) { val cand = items.getJSONObject(i); if (same(cand.optString("name"), title)) { pick = cand; break } }
            albumId = (pick ?: items.optJSONObject(0))?.optString("id")
        }
        if (albumId.isNullOrEmpty()) return null
        val al = sget("https://api.spotify.com/v1/albums/$albumId") ?: return null
        val artists = al.optJSONArray("artists") ?: JSONArray()
        out.put("uri", "spotify:album:$albumId")
        out.put("title", al.optString("name", title))
            .put("artist", (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") })
            .put("date", al.optString("release_date"))
            .put("type", al.optString("album_type"))
        val cr = JSONArray()
        al.optJSONArray("copyrights")?.let { c -> for (i in 0 until c.length()) cr.put(c.getJSONObject(i).optString("text")) }
        out.put("copyrights", cr)
        val tracks = JSONArray()
        al.optJSONObject("tracks")?.optJSONArray("items")?.let { ts ->
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                tracks.put(JSONObject().put("t", t.optString("name")).put("d", t.optLong("duration_ms") / 1000).put("disc", t.optInt("disc_number", 1)))
            }
        }
        out.put("tracks", tracks)
        biggest(al.optJSONArray("images"), 640)?.let { u -> saveImage(u)?.let { out.put("cover", it) } }
        val artistId = artists.optJSONObject(0)?.optString("id")
        if (!artistId.isNullOrEmpty()) sget("https://api.spotify.com/v1/artists/$artistId")?.let { ar ->
            val g = ar.optJSONArray("genres") ?: JSONArray()
            out.put("genres", JSONArray((0 until minOf(3, g.length())).map { g.optString(it) }))
            biggest(ar.optJSONArray("images"), 640)?.let { u -> saveImage(u)?.let { out.put("photo", it) } }
        }
        return albumId
    }

    private fun sget(u: String): JSONObject? = try { box.webGet(u) } catch (e: IOException) { netFailed.set(true); null }

    // ---------- Wikipedia ----------

    private val bandWords = listOf("band", "singer", "musician", "rapper", "group", "duo", "trio", "songwriter", "composer", "producer", "dj", "vocalist", "guitarist", "pianist", "orchestra", "ensemble", "artist")

    private fun wikiAlbum(title: String, artist: String): Pair<String, String>? {
        if (title.isBlank()) return null
        val t = clean(title)
        for (page in wikiSearch("\"$t\" $artist album")) {
            if (!page.lowercase().contains(t.lowercase().take(24))) continue
            val (text, url) = wikiIntro(page) ?: continue
            val low = text.lowercase()
            if (artist.isNotBlank() && !low.contains(artist.lowercase().take(20))) continue
            if (listOf("album", " ep ", "soundtrack", "record", "mixtape").none { low.contains(it) }) continue
            return trim(text) to url
        }
        return null
    }

    private fun wikiBand(artist: String): Pair<String, String>? {
        if (artist.isBlank()) return null
        for (page in wikiSearch("$artist band musician")) {
            if (!page.lowercase().startsWith(artist.lowercase().take(20))) continue
            val (text, url) = wikiIntro(page) ?: continue
            val first = text.take(300).lowercase()
            if (bandWords.none { first.contains(it) }) continue
            return trim(text) to url
        }
        return null
    }

    private fun wikiSearch(q: String): List<String> {
        val j = JSONObject(http("https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + enc(q)) ?: return emptyList())
        val s = j.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until s.length()).map { s.getJSONObject(it).optString("title") }
    }

    private fun wikiIntro(page: String): Pair<String, String>? {
        val j = JSONObject(http("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json&titles=" + enc(page)) ?: return null)
        val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val p = pages.optJSONObject(pages.keys().asSequence().firstOrNull() ?: return null) ?: return null
        val text = p.optString("extract").trim()
        if (text.length < 80) return null
        return text to "https://en.wikipedia.org/wiki/" + enc(p.optString("title", page).replace(' ', '_'))
    }

    /** The first few paragraphs, cut at a sentence. */
    private fun trim(text: String, max: Int = 1800): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf(". ", max)
        return if (cut > max / 2) text.substring(0, cut + 1) else text.substring(0, max) + "…"
    }

    // ---------- a few lines about an artist (for the songs on a mixtape) ----------

    fun artist(id: String, name: String) {
        Thread {
            val key = "ar:" + norm(firstArtist(name))
            var s = read("a", key)
            if (s == null && online()) {
                netFailed.set(false)
                val out = JSONObject().put("artist", name)
                try { wikiBand(firstArtist(name))?.let { out.put("about", trim(it.first, 460)) } } catch (e: Exception) { }
                s = out.toString()
                if (!netFailed.get()) write("a", key, s)
            }
            results[id] = s ?: JSONObject().put("artist", name).toString()
            ready(id)
        }.start()
    }

    // ---------- the words ----------

    fun lyrics(id: String, track: String, artist: String, album: String, durSec: Int) {
        Thread {
            results[id] = lyricsNow(track, artist, album, durSec) ?: JSONObject().put("track", track).toString()
            ready(id)
        }.start()
    }

    private fun lyricsNow(track: String, artist: String, album: String, durSec: Int): String? {
        val key = lyricKey(track, artist)
        read("l", key)?.let { saved ->
            // "no words on file" is asked again after a month; LRCLIB keeps growing
            val j = try { JSONObject(saved) } catch (e: Exception) { null }
            if (j == null || !j.optBoolean("none") || System.currentTimeMillis() - j.optLong("at") < 30L * 86_400_000) return saved
        }
        if (!online()) return null
        netFailed.set(false)
        val out = JSONObject().put("track", track)
        try {
            val a = firstArtist(artist)
            var j: JSONObject? = null
            for (t in listOf(track, clean(track)).distinct()) {
                val q = "track_name=" + enc(t) + "&artist_name=" + enc(a) + (if (album.isNotBlank()) "&album_name=" + enc(album) else "") + (if (durSec > 0) "&duration=$durSec" else "")
                j = http("https://lrclib.net/api/get?$q")?.let { JSONObject(it) }
                if (j != null) break
            }
            if (j == null) {
                val arr = http("https://lrclib.net/api/search?track_name=" + enc(clean(track)) + "&artist_name=" + enc(a))?.let { JSONArray(it) }
                if (arr != null) for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); if (!c.optString("plainLyrics").isNullOrBlank() || c.optBoolean("instrumental")) { j = c; break } }
            }
            if (j != null) out.put("plain", j.optString("plainLyrics").takeIf { it != "null" } ?: "")
                .put("synced", j.optString("syncedLyrics").takeIf { it != "null" } ?: "")
                .put("instrumental", j.optBoolean("instrumental"))
            else out.put("none", true).put("at", System.currentTimeMillis())
        } catch (e: Exception) { }
        val s = out.toString()
        if (!netFailed.get()) write("l", key, s)
        return s
    }

    // ---------- the whole box, in the background, on Wi-Fi ----------

    @Volatile private var fetching = false
    @Volatile var stopped = false

    /** Work through every album in the box, skipping what's already saved. Safe to call often. */
    fun prefetch() {
        if (fetching || stopped) return
        fetching = true
        Thread {
            try {
                val albums = try { JSONArray(box.boxJson()) } catch (e: Exception) { JSONArray() }
                for (i in 0 until albums.length()) {
                    if (stopped || !wifi()) break
                    val a = albums.getJSONObject(i)
                    val title = a.optString("title"); val artist = a.optString("artist"); val uri = a.optString("uri")
                    if (albumKeys(title, artist, uri).none { has("n", it) }) { albumNow(title, artist, uri, onDemand = false); rest(1200) }
                    val ts = a.optJSONArray("tracks") ?: continue
                    for (k in 0 until ts.length()) {
                        if (stopped || !wifi()) break
                        val t = ts.getJSONObject(k)
                        if (has("l", lyricKey(t.optString("t"), artist))) continue
                        lyricsNow(t.optString("t"), artist, title, t.optInt("d"))
                        rest(700)
                    }
                }
            } finally { fetching = false }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun rest(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }

    private fun caps(): NetworkCapabilities? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getNetworkCapabilities(cm.activeNetwork ?: return null)
    }
    private fun online() = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    /** Unmetered and actually connected: Wi-Fi, the marina's or the boat's. */
    private fun wifi() = caps()?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } == true

    // ---------- saving ----------

    private fun albumKeys(title: String, artist: String, uri: String) = listOfNotNull(
        uri.takeIf { it.startsWith("spotify:album:") }?.let { "uri:$it" },
        if (title.isNotBlank()) "ta:" + norm(clean(title)) + "|" + norm(firstArtist(artist)) else null,
    )
    private fun lyricKey(track: String, artist: String) = norm(clean(track)) + "|" + norm(firstArtist(artist))
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    private fun file(kind: String, key: String) = File(dir, kind + "-" + sha1(key) + ".json")
    private fun has(kind: String, key: String) = file(kind, key).exists()
    private fun read(kind: String, key: String): String? = file(kind, key).takeIf { it.exists() }?.let { try { it.readText() } catch (e: Exception) { null } }
    // written beside and swapped in, so a card being read on another thread is never half there
    private fun write(kind: String, key: String, s: String) {
        try { val f = file(kind, key); val tmp = File(dir, f.name + ".tmp"); tmp.writeText(s); if (!tmp.renameTo(f)) { f.writeText(s); tmp.delete() } } catch (e: Exception) { }
    }

    private fun saveImage(u: String): String? {
        val name = sha1(u) + ".jpg"
        val f = File(imgDir, name)
        if (f.exists()) return "img:$name"
        return try { f.writeBytes(URL(u).openStream().use { it.readBytes() }); "img:$name" }
        catch (e: IOException) { netFailed.set(true); f.delete(); null }
        catch (e: Exception) { f.delete(); null }
    }

    /** The page gets pictures inline. */
    private fun withImages(s: String): String {
        val j = try { JSONObject(s) } catch (e: Exception) { return s }
        for (k in listOf("cover", "photo")) {
            val v = j.optString(k)
            if (v.startsWith("img:")) {
                val f = File(imgDir, v.removePrefix("img:"))
                if (f.exists()) j.put(k, "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) else j.remove(k)
            }
        }
        return j.toString()
    }

    // ---------- bits ----------

    /** A page's text; null when it isn't there. Losing the signal counts as a failure (not saved). */
    private fun http(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        // Wikipedia and LRCLIB both ask apps to say who they are
        c.setRequestProperty("User-Agent", "PHONY/1.0 (personal cassette-player app; https://github.com/hughhowey/phony)")
        return try {
            val code = c.responseCode
            if (code == 200) c.inputStream.bufferedReader().use { it.readText() }
            else { if (code >= 500 || code == 429) netFailed.set(true); null }
        } catch (e: Exception) { netFailed.set(true); null } finally { c.disconnect() }
    }

    private fun biggest(imgs: JSONArray?, cap: Int): String? {
        if (imgs == null || imgs.length() == 0) return null
        // the biggest one no wider than cap (else the smallest there is)
        var best = imgs.getJSONObject(0)
        for (i in 1 until imgs.length()) {
            val im = imgs.getJSONObject(i); val w = im.optInt("width"); val bw = best.optInt("width")
            if ((w <= cap && (w > bw || bw > cap)) || (bw > cap && w < bw)) best = im
        }
        return best.optString("url")
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun firstArtist(a: String) = a.split(",", " & ", " feat.", " ft.", " featuring ", " x ").first().trim()
    /** "Song - Remastered 2011" and "Album (Deluxe Edition)" → the plain name. */
    private fun clean(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "").trim().ifEmpty { s }
    private fun same(a: String, b: String) = clean(a).equals(clean(b), ignoreCase = true)
}
