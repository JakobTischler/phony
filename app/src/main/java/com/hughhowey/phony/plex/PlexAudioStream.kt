package com.hughhowey.phony.plex

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP transport, independently testable without Android or audio hardware. */
internal class PlexAudioStream(endpoint: PlexEndpoint, part: String, position: Long, length: Long) : AutoCloseable {
    private val connection = URL(PlexMusic.url(endpoint.url, part)).openConnection() as HttpURLConnection
    private var input: InputStream? = null
    var remaining: Long = -1
        private set

    init {
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-Plex-Token", endpoint.token)
            connection.setRequestProperty("Accept-Encoding", "identity")
            val end = if (length >= 0) (position + length - 1).toString() else ""
            if (position > 0 || end.isNotEmpty()) connection.setRequestProperty("Range", "bytes=$position-$end")
            val code = connection.responseCode
            if (code == 416 && connection.getHeaderField("Content-Range") == "bytes */$position") {
                remaining = 0 // Extractors may probe exactly the end of the file.
            } else {
                if (code !in 200..299) throw IOException("Plex stream returned HTTP $code")
                if (code == 206) {
                    val start = connection.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != position) throw IOException("Plex returned an unexpected audio range")
                }
                val stream = connection.inputStream; input = stream
                // Some servers ignore Range. Discard preceding bytes rather than playing the wrong position.
                var skip = if (code == 200) position else 0L
                while (skip > 0) {
                    val n = stream.skip(skip)
                    if (n > 0) skip -= n else { if (stream.read() == -1) throw EOFException(); skip-- }
                }
                val available = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
                remaining = if (length >= 0) length else if (available >= 0) (available - if (code == 200) position else 0).coerceAtLeast(0) else -1
            }
        } catch (e: Exception) { close(); throw IOException("Could not open Plex audio", e) }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val n = input?.read(buffer, offset, count) ?: throw IOException("Plex stream is closed")
        if (n < 0) {
            if (remaining > 0) throw EOFException("Plex audio ended early")
            return -1
        }
        if (remaining >= 0) remaining -= n
        return n
    }
    override fun close() { try { input?.close() } finally { input = null; connection.disconnect() } }
}
