package com.hughhowey.phony

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * What goes on the fold-out J-card: the album's songs and credits and a photo of the
 * band (Spotify), the story of the record and the band (Wikipedia), and the words to
 * the song that's playing (LRCLIB, an open lyrics library). Everything is fetched in
 * the background; the page is told by id when it's ready and reads it with take().
 */
class LinerNotes(private val box: SpotifyBox, private val ready: (String) -> Unit) {

    private val results = ConcurrentHashMap<String, String>()

    fun take(id: String): String = results.remove(id) ?: ""

    // ---------- the album ----------

    fun album(id: String, title: String, artist: String, albumUri: String) {
        Thread {
            val out = JSONObject().put("title", title).put("artist", artist)
            try { spotifyPart(out, title, artist, albumUri) } catch (e: Exception) { }
            val t = out.optString("title", title); val a = firstArtist(out.optString("artist", artist))
            try { wikiAlbum(t, a)?.let { out.put("about", it.first).put("aboutUrl", it.second) } } catch (e: Exception) { }
            try { wikiBand(a)?.let { out.put("band", it.first).put("bandUrl", it.second) } } catch (e: Exception) { }
            results[id] = out.toString()
            ready(id)
        }.start()
    }

    private fun spotifyPart(out: JSONObject, title: String, artist: String, albumUri: String) {
        var albumId = albumUri.takeIf { it.startsWith("spotify:album:") }?.substringAfterLast(':')
        if (albumId == null && title.isNotBlank()) {
            val q = enc("album:${clean(title)} artist:${firstArtist(artist)}")
            val items = box.webGet("https://api.spotify.com/v1/search?type=album&limit=5&q=$q")
                ?.optJSONObject("albums")?.optJSONArray("items") ?: JSONArray()
            var pick: JSONObject? = null
            for (i in 0 until items.length()) { val cand = items.getJSONObject(i); if (same(cand.optString("name"), title)) { pick = cand; break } }
            albumId = (pick ?: items.optJSONObject(0))?.optString("id")
        }
        if (albumId.isNullOrEmpty()) return
        val al = box.webGet("https://api.spotify.com/v1/albums/$albumId") ?: return
        val artists = al.optJSONArray("artists") ?: JSONArray()
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
        biggest(al.optJSONArray("images"), 640)?.let { u -> image(u)?.let { out.put("cover", it) } }
        val artistId = artists.optJSONObject(0)?.optString("id")
        if (!artistId.isNullOrEmpty()) box.webGet("https://api.spotify.com/v1/artists/$artistId")?.let { ar ->
            val g = ar.optJSONArray("genres") ?: JSONArray()
            out.put("genres", JSONArray((0 until minOf(3, g.length())).map { g.optString(it) }))
            biggest(ar.optJSONArray("images"), 640)?.let { u -> image(u)?.let { out.put("photo", it) } }
        }
    }

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

    // ---------- the words ----------

    fun lyrics(id: String, track: String, artist: String, album: String, durSec: Int) {
        Thread {
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
            } catch (e: Exception) { }
            results[id] = out.toString()
            ready(id)
        }.start()
    }

    // ---------- bits ----------

    private fun http(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        // Wikipedia and LRCLIB both ask apps to say who they are
        c.setRequestProperty("User-Agent", "PHONY/1.0 (personal cassette-player app; https://github.com/hughhowey/phony)")
        return try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } catch (e: Exception) { null } finally { c.disconnect() }
    }

    private fun image(u: String): String? = try {
        val bytes = URL(u).openStream().use { it.readBytes() }
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) { null }

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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun firstArtist(a: String) = a.split(",", " & ", " feat.", " ft.", " featuring ", " x ").first().trim()
    /** "Song - Remastered 2011" and "Album (Deluxe Edition)" → the plain name. */
    private fun clean(s: String) = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "").trim().ifEmpty { s }
    private fun same(a: String, b: String) = clean(a).equals(clean(b), ignoreCase = true)
}
