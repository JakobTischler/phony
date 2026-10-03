package com.hughhowey.phony.plex

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Plex wire models stay native: access tokens and connection URLs never enter the WebView. */
internal data class PlexServer(val id: String, val name: String, val token: String, val connections: List<String>)
internal data class PlexLibrary(val id: String, val name: String)
internal class PlexHttpError(val status: Int) : IOException("Plex HTTP $status")

internal class PlexApi(private val clientId: String) {
    fun pin(): JSONObject = JSONObject(request("https://plex.tv/api/v2/pins", body = "strong=true"))
    fun checkPin(id: Long, code: String): JSONObject = JSONObject(request("https://plex.tv/api/v2/pins/$id?code=${encode(code)}"))
    fun authUrl(code: String) = "https://app.plex.tv/auth#?clientID=${encode(clientId)}&code=${encode(code)}&context%5Bdevice%5D%5Bproduct%5D=PHONY"
    fun servers(token: String): List<PlexServer> = parseServers(JSONArray(request(
        "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token)))
    fun libraries(url: String, token: String): List<PlexLibrary> = parseLibraries(JSONObject(request("$url/library/sections", token)))
    fun albums(endpoint: PlexEndpoint, libraryId: String, offset: Int): PlexPage<PlexAlbum> = PlexMusic.albums(JSONObject(request(
        "${endpoint.url}/library/sections/${encode(libraryId)}/all?type=9&sort=artist.titleSort:asc,album.titleSort:asc&X-Plex-Container-Start=$offset&X-Plex-Container-Size=${PlexMusic.PAGE_SIZE}", endpoint.token)), offset)

    fun tracks(endpoint: PlexEndpoint, album: PlexAlbum): List<PlexTrack> {
        val all = mutableListOf<PlexTrack>()
        var offset = 0
        do {
            val data = JSONObject(request("${endpoint.url}/library/metadata/${encode(album.id)}/children?X-Plex-Container-Start=$offset&X-Plex-Container-Size=200", endpoint.token))
            val container = data.getJSONObject("MediaContainer")
            if (container.has("offset")) require(container.getInt("offset") == offset)
            val tracks = PlexMusic.tracks(data, album)
            all.addAll(tracks)
            offset += tracks.size
        } while (tracks.isNotEmpty() && offset < container.optInt("totalSize", offset))
        return all.sortedWith(compareBy({ it.disc }, { it.number }))
    }

    fun image(endpoint: PlexEndpoint, path: String): ByteArray {
        val c = URL(PlexMusic.url(endpoint.url, path)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("X-Plex-Token", endpoint.token)
            if (c.responseCode !in 200..299) throw PlexHttpError(c.responseCode)
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 8 * 1024 * 1024); out.write(buffer, 0, n) }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun request(url: String, token: String = "", body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 10000
            // Never forward credentials to a redirect target.
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-Plex-Product", "PHONY")
            c.setRequestProperty("X-Plex-Version", "1.0")
            c.setRequestProperty("X-Plex-Platform", "Android")
            c.setRequestProperty("X-Plex-Client-Identifier", clientId)
            if (token.isNotEmpty()) c.setRequestProperty("X-Plex-Token", token)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (c.responseCode !in 200..299) throw PlexHttpError(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    companion object {
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
        fun parseServers(data: JSONArray): List<PlexServer> = (0 until data.length()).mapNotNull { i ->
            val s = data.getJSONObject(i)
            if ("server" !in s.optString("provides").split(',').map { it.trim() }) return@mapNotNull null
            val id = s.optString("clientIdentifier")
            val token = s.optString("accessToken").takeUnless { s.isNull("accessToken") }.orEmpty()
            if (id.isBlank() || token.isBlank()) return@mapNotNull null
            val connections = s.optJSONArray("connections") ?: JSONArray()
            val urls = (0 until connections.length()).map { connections.getJSONObject(it) }
                .sortedBy { if (it.optBoolean("local")) 0 else if (!it.optBoolean("relay")) 1 else 2 }
                .mapNotNull { secureUrl(it.optString("uri")) }.distinct()
            PlexServer(id, s.optString("name", "Plex server"), token, urls)
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }

        fun parseLibraries(data: JSONObject): List<PlexLibrary> {
            val sections = data.getJSONObject("MediaContainer").optJSONArray("Directory") ?: JSONArray()
            return (0 until sections.length()).mapNotNull { i ->
                val s = sections.getJSONObject(i)
                if (s.optString("type") != "artist" || s.optString("key").isBlank()) null
                else PlexLibrary(s.getString("key"), s.optString("title", "Music"))
            }.sortedBy { it.name.lowercase() }
        }

        private fun secureUrl(value: String): String? = try {
            val uri = URI(value)
            if (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) value.trimEnd('/') else null
        } catch (_: Exception) { null }
    }
}
