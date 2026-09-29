package com.hughhowey.phony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The tape box: the albums saved in your Spotify library, each with its cover.
 *
 * - Reading the library uses Spotify's Web API. You sign in once in the browser;
 *   after that PHONY keeps a refresh key and never asks again.
 * - Playing an album uses Spotify's App Remote, which tells the Spotify app on
 *   the phone what to play. The first time, Spotify asks you to allow it.
 */
class SpotifyBox(private val ctx: Context, private val remoteWatcher: RemoteWatcher, private val onChange: () -> Unit) {

    companion object {
        const val CLIENT_ID = "e46b23f44fee4ddfb565d5a78ded8345"
        const val REDIRECT = "phony://callback"
        private const val SCOPES = "user-library-read user-modify-playback-state user-read-playback-state user-read-currently-playing"
        private const val PLAY_SCOPE = "user-modify-playback-state"
    }

    private val prefs = ctx.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val boxFile = File(ctx.filesDir, "box.json")
    private val coverDir = File(ctx.filesDir, "covers").apply { mkdirs() }

    @Volatile var state = "idle"; private set      // idle · loading · ready · error
    @Volatile var message = ""; private set
    @Volatile private var syncing = false
    @Volatile private var access: String? = null
    @Volatile private var accessUntil = 0L
    private var remote: SpotifyAppRemote? = null

    val signedIn get() = prefs.getString("refresh", null) != null
    /** Signed in with permission to change what Spotify plays (added after the first version). */
    val canPlay get() = signedIn && (prefs.getString("scope", "") ?: "").contains(PLAY_SCOPE)

    fun statusJson(): String = JSONObject()
        .put("signedIn", signedIn)
        .put("state", state)
        .put("message", message)
        .put("updated", if (boxFile.exists()) boxFile.lastModified() else 0L)
        .put("spotify", SpotifyAppRemote.isSpotifyInstalled(ctx))
        .put("canPlay", canPlay)
        .toString()

    fun boxJson(): String = if (boxFile.exists()) boxFile.readText() else "[]"

    // ---------- signing in (PKCE, in the browser) ----------

    fun startLogin() {
        val verifier = randomString(64)
        prefs.edit().putString("verifier", verifier).apply()
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.spotify.com/authorize" +
            "?response_type=code&client_id=$CLIENT_ID" +
            "&scope=" + enc(SCOPES) +
            "&redirect_uri=" + enc(REDIRECT) +
            "&code_challenge_method=S256&code_challenge=$challenge"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The browser hands back phony://callback?code=… (or ?error=…). */
    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != "phony" || uri.host != "callback") return false
        val code = uri.getQueryParameter("code")
        if (code == null) {
            // App Remote's own sign-in can come back here too, without a code; ignore that.
            uri.getQueryParameter("error")?.let { state = "error"; message = "Spotify said no ($it)."; onChange() }
            return true
        }
        val verifier = prefs.getString("verifier", null) ?: return true
        state = "loading"; message = ""; onChange()
        Thread {
            try {
                val j = token("grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&client_id=$CLIENT_ID&code_verifier=${enc(verifier)}")
                saveTokens(j)
                syncNow()
            } catch (e: Exception) {
                state = "error"; message = "Couldn't sign in to Spotify."; onChange()
            }
        }.start()
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply(); access = null; accessUntil = 0
        boxFile.delete(); state = "idle"; onChange()
    }

    private fun saveTokens(j: JSONObject) {
        access = j.getString("access_token")
        accessUntil = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("refresh", it).apply() }
        j.optString("scope").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("scope", it).apply() }
    }

    private fun accessToken(): String {
        access?.let { if (System.currentTimeMillis() < accessUntil) return it }
        val refresh = prefs.getString("refresh", null) ?: throw IllegalStateException("signed out")
        val j = try {
            token("grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=$CLIENT_ID")
        } catch (e: HttpError) {
            if (e.code == 400 || e.code == 401) prefs.edit().remove("refresh").apply()
            throw e
        }
        saveTokens(j)
        return access!!
    }

    private fun token(body: String): JSONObject {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw HttpError(code)
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    // ---------- the library ----------

    /** Refresh the box in the background, unless it was refreshed in the last minute. */
    fun sync(force: Boolean) {
        if (!signedIn || syncing) return
        if (!force && boxFile.exists() && System.currentTimeMillis() - boxFile.lastModified() < 60_000) return
        Thread { syncNow() }.start()
    }

    private fun syncNow() {
        if (syncing) return
        syncing = true
        if (!boxFile.exists()) { state = "loading"; onChange() }
        try {
            val out = JSONArray()
            var next: String? = "https://api.spotify.com/v1/me/albums?limit=50"
            var pages = 0
            while (next != null && pages < 20) {
                val j = JSONObject(get(next)); pages++
                val items = j.getJSONArray("items")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val a = item.getJSONObject("album")
                    val artists = a.optJSONArray("artists")
                    val artist = (0 until (artists?.length() ?: 0)).joinToString(", ") { k -> artists!!.getJSONObject(k).optString("name") }
                    val tracks = JSONArray()
                    a.optJSONObject("tracks")?.optJSONArray("items")?.let { ts ->
                        for (k in 0 until ts.length()) {
                            val t = ts.getJSONObject(k)
                            tracks.put(JSONObject().put("t", t.optString("name")).put("d", t.optLong("duration_ms") / 1000))
                        }
                    }
                    val id = a.getString("id")
                    out.put(JSONObject()
                        .put("id", id)
                        .put("uri", a.optString("uri", "spotify:album:$id"))
                        .put("title", a.optString("name"))
                        .put("artist", artist)
                        .put("year", a.optString("release_date").take(4))
                        .put("added", item.optString("added_at"))
                        .put("tracks", tracks)
                        .put("cover", pickImage(a.optJSONArray("images"))))
                }
                next = j.optString("next").takeIf { it.isNotEmpty() && it != "null" }
            }
            boxFile.writeText(out.toString())
            state = "ready"; message = ""
            onChange()
            // covers, after the box is up
            for (i in 0 until out.length()) {
                val a = out.getJSONObject(i)
                val f = File(coverDir, a.getString("id") + ".jpg")
                val u = a.optString("cover")
                if (f.exists() || u.isEmpty()) continue
                try { f.writeBytes(URL(u).openStream().use { s -> s.readBytes() }) } catch (e: Exception) { }
                if (i % 6 == 5) onChange()
            }
            onChange()
        } catch (e: Exception) {
            state = if (boxFile.exists()) "ready" else "error"
            message = if (!signedIn) "Sign in to Spotify again." else "Couldn't reach Spotify."
            onChange()
        } finally { syncing = false }
    }

    private fun pickImage(imgs: JSONArray?): String {
        if (imgs == null || imgs.length() == 0) return ""
        var best = imgs.getJSONObject(0)
        for (i in 0 until imgs.length()) {
            val im = imgs.getJSONObject(i)
            val w = im.optInt("width"); val bw = best.optInt("width")
            // the smallest one that's still at least 600 px, else the biggest
            if ((w >= 600 && (bw < 600 || w < bw)) || (bw < 600 && w > bw)) best = im
        }
        return best.optString("url")
    }

    fun coverDataUrl(id: String): String {
        val f = File(coverDir, id.filter { it.isLetterOrDigit() } + ".jpg")
        if (!f.exists()) return ""
        return "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer " + accessToken())
        try {
            val code = c.responseCode
            if (code == 401) { access = null; throw HttpError(code) }
            if (code != 200) throw HttpError(code)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // ---------- playing an album ----------

    /**
     * Start an album. Three ways, in order, stopping at the first that works:
     * 1. Spotify's Web API, on this phone's Spotify (needs the play permission).
     * 2. Android's media controls: ask Spotify's player to play the album's link.
     * 3. Spotify's App Remote.
     * done() runs on the main thread; on failure the message says what each way answered.
     */
    fun play(uri: String, title: String, done: (Boolean, String) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val notes = mutableListOf<String>()
            var web: String? = "web: not allowed yet"
            if (canPlay) {
                web = try { playWeb(uri); null } catch (e: Exception) { "web: " + (e.message ?: e.javaClass.simpleName) }
            }
            if (web == null) { main.post { done(true, "") }; return@Thread }
            notes += web
            main.post {
                if (remoteWatcher.playFromUri(uri)) {
                    // give Spotify a moment, then check it actually switched
                    main.postDelayed({
                        if (remoteWatcher.playingAlbum(title)) done(true, "")
                        else { notes += "media controls: no change"; viaAppRemote(uri, notes, done) }
                    }, 2500)
                } else { notes += "media controls: Spotify isn't open"; viaAppRemote(uri, notes, done) }
            }
        }.start()
    }

    private fun playWeb(uri: String) {
        val devices = JSONObject(api("GET", "https://api.spotify.com/v1/me/player/devices", null)).optJSONArray("devices") ?: JSONArray()
        var id: String? = null
        for (i in 0 until devices.length()) { val d = devices.getJSONObject(i); if (d.optBoolean("is_active")) { id = d.optString("id"); break } }
        if (id == null) for (i in 0 until devices.length()) { val d = devices.getJSONObject(i); if (d.optString("type").equals("Smartphone", true)) { id = d.optString("id"); break } }
        val q = if (id.isNullOrEmpty()) "" else "?device_id=" + enc(id)
        api("PUT", "https://api.spotify.com/v1/me/player/play$q", JSONObject().put("context_uri", uri).toString())
    }

    private fun viaAppRemote(uri: String, notes: MutableList<String>, done: (Boolean, String) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var answered = false
        val finish = { ok: Boolean, note: String ->
            if (!answered) {
                answered = true
                if (ok) done(true, "") else { notes += "app remote: $note"; done(false, "Spotify didn't switch albums (" + notes.joinToString("; ") + ")") }
            }
        }
        main.postDelayed({ finish(false, "no answer") }, 10_000)
        val go = { r: SpotifyAppRemote ->
            r.playerApi.play(uri).setResultCallback { finish(true, "") }.setErrorCallback { t -> finish(false, t.javaClass.simpleName + " " + (t.message ?: "")) }
        }
        remote?.let { r -> if (r.isConnected) { go(r); return } }
        if (!SpotifyAppRemote.isSpotifyInstalled(ctx)) { finish(false, "Spotify isn't installed"); return }
        val params = ConnectionParams.Builder(CLIENT_ID).setRedirectUri(REDIRECT).showAuthView(true).build()
        SpotifyAppRemote.connect(ctx, params, object : Connector.ConnectionListener {
            override fun onConnected(r: SpotifyAppRemote) { remote = r; go(r) }
            override fun onFailure(t: Throwable) { finish(false, t.javaClass.simpleName + " " + (t.message ?: "")) }
        })
    }

    // ---------- what Spotify is playing (is it an album?) ----------

    @Volatile var contextJson = ""; private set
    @Volatile private var watching = false

    /** While PHONY is on screen, ask Spotify every few seconds whether it's playing an album. */
    fun watch(on: Boolean) {
        if (on == watching) return
        watching = on
        if (!on) return
        Thread {
            while (watching) {
                if (canPlay && remoteWatcher.enabled) {
                    contextJson = try {
                        val body = api("GET", "https://api.spotify.com/v1/me/player/currently-playing", null)
                        if (body.isBlank()) "" else {
                            val j = JSONObject(body)
                            val ctxObj = j.optJSONObject("context")
                            val alb = j.optJSONObject("item")?.optJSONObject("album")
                            JSONObject()
                                .put("type", ctxObj?.optString("type") ?: "")
                                .put("uri", ctxObj?.optString("uri") ?: "")
                                .put("album", alb?.optString("name") ?: "")
                                .put("albumUri", alb?.optString("uri") ?: "")
                                .toString()
                        }
                    } catch (e: Exception) { contextJson }
                }
                try { Thread.sleep(4000) } catch (e: InterruptedException) { }
            }
        }.start()
    }

    private fun api(method: String, u: String, body: String?): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer " + accessToken())
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        } else if (method == "PUT") c.setFixedLengthStreamingMode(0)
        try {
            val code = c.responseCode
            if (code == 401) access = null
            if (code == 204) return ""
            if (code !in 200..299) {
                val msg = try { JSONObject(c.errorStream.bufferedReader().use { it.readText() }).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
                throw IllegalStateException("$code" + if (msg.isNullOrEmpty()) "" else " $msg")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun release() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null }

    // ---------- bits ----------

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun randomString(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

}
