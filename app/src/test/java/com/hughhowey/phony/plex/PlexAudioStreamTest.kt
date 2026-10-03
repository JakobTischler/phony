package com.hughhowey.phony.plex

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlexAudioStreamTest {
    // Android's compile classpath excludes the JDK HTTP server; use a tiny socket fixture.
    private fun server(vararg responses: String, test: (PlexEndpoint, LinkedBlockingQueue<String>) -> Unit) {
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val queue = LinkedBlockingQueue(responses.toList())
        val requests = LinkedBlockingQueue<String>()
        val thread = Thread {
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 2000
                    val reader = client.getInputStream().bufferedReader()
                    val headers = StringBuilder()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.append(line).append('\n') }
                    requests.put(headers.toString())
                    val response = queue.poll() ?: "HTTP/1.1 500 Error\r\nContent-Length: 0\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray())
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        try { test(PlexEndpoint("http://127.0.0.1:${socket.localPort}", "test-token"), requests) }
        finally { socket.close(); thread.join(2000) }
    }
    @Test fun `authenticated range request reads exactly the requested audio bytes`() {
        server("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 3-5/10\r\nContent-Length: 3\r\n\r\n345") { endpoint, requests ->
            PlexAudioStream(endpoint, "/audio", 3, 3).use { stream ->
                val out = ByteArray(20)
                assertEquals(3, stream.read(out, 0, out.size)); assertEquals("345", String(out, 0, 3))
                assertEquals(-1, stream.read(out, 0, out.size))
            }
            val request = requests.poll(2, TimeUnit.SECONDS)!!
            assertTrue(request.contains("X-Plex-Token: test-token", ignoreCase = true))
            assertTrue(request.contains("Range: bytes=3-5", ignoreCase = true))
            assertEquals("GET /audio HTTP/1.1", request.lineSequence().first())
        }
    }
    @Test fun `server ignoring Range still seeks to the requested offset`() {
        server("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789") { endpoint, _ ->
            PlexAudioStream(endpoint, "/audio", 7, -1).use { stream ->
                val out = ByteArray(20)
                assertEquals(3, stream.read(out, 0, out.size)); assertEquals("789", String(out, 0, 3))
            }
        }
    }
    @Test fun `redirect does not forward credentials or load its destination`() {
        server("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n") { endpoint, requests ->
            assertTrue(runCatching { PlexAudioStream(endpoint, "/audio", 0, -1).close() }.isFailure)
            assertEquals(1, requests.size)
        }
    }
    @Test fun `EOF probe handles 416 while wrong byte ranges fail`() {
        server("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */10\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-2/10\r\nContent-Length: 3\r\n\r\n012") { endpoint, _ ->
            PlexAudioStream(endpoint, "/eof", 10, -1).use { assertEquals(-1, it.read(ByteArray(1), 0, 1)) }
            assertTrue(runCatching { PlexAudioStream(endpoint, "/wrong", 3, 3).close() }.isFailure)
        }
    }
    @Test fun `truncated audio is an error instead of a successful track ending`() {
        server("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n012") { endpoint, _ ->
            assertTrue(runCatching {
                PlexAudioStream(endpoint, "/audio", 0, -1).use { stream ->
                    val out = ByteArray(20)
                    while (stream.read(out, 0, out.size) >= 0) { }
                }
            }.isFailure)
        }
    }
}
