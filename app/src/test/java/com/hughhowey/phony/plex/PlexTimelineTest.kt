package com.hughhowey.phony.plex

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlexTimelineTest {
    @Test fun `timeline report identifies the playing track and native client`() {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requests = LinkedBlockingQueue<String>()
        val server = Thread {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(); val request = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                requests.put(request)
                client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }.apply { isDaemon = true; start() }
        try {
            val endpoint = PlexEndpoint("http://127.0.0.1:${socket.localPort}", "token", "client")
            val track = PlexTrack("42", "Track", "Artist", 180000, 1, 1, "/library/parts/42")
            PlexTimeline.send(PlexTimeline.Event(endpoint, track, "playing", 1234, 180000, "session"))
            val request = requests.poll(2, TimeUnit.SECONDS)!!
            assertTrue(request.lineSequence().first().contains("/:/timeline?"))
            assertTrue(request.contains("ratingKey=42")); assertTrue(request.contains("state=playing")); assertTrue(request.contains("time=1234"))
            assertTrue(request.contains("X-Plex-Token: token", ignoreCase = true)); assertTrue(request.contains("X-Plex-Session-Identifier: session", ignoreCase = true))
        } finally { socket.close(); server.join(2000) }
    }
}
