package com.hughhowey.phony.plex

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Native-only playback heartbeat. It lets Plex show this stream as an active session. */
internal object PlexTimeline {
    internal data class Event(
        val endpoint: PlexEndpoint, val track: PlexTrack, val state: String,
        val positionMs: Long, val durationMs: Long, val sessionId: String
    )

    fun send(event: Event) {
        val params = linkedMapOf(
            "ratingKey" to event.track.id,
            "key" to "/library/metadata/${event.track.id}",
            "state" to event.state,
            "time" to event.positionMs.coerceAtLeast(0).toString(),
            "duration" to event.durationMs.coerceAtLeast(0).toString()
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = URL(event.endpoint.url.trimEnd('/') + "/:/timeline?$params").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Plex-Token", event.endpoint.token)
            connection.setRequestProperty("X-Plex-Product", "PHONY")
            connection.setRequestProperty("X-Plex-Platform", "Android")
            connection.setRequestProperty("X-Plex-Device-Name", "PHONY")
            connection.setRequestProperty("X-Plex-Client-Identifier", event.endpoint.clientId)
            connection.setRequestProperty("X-Plex-Session-Identifier", event.sessionId)
            connection.responseCode // Timeline reporting is best effort; playback must never wait on it.
        } catch (_: Exception) {
        } finally { connection.disconnect() }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
