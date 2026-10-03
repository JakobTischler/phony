package com.hughhowey.phony.plex

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Main-thread state machine. Only sanitized snapshots cross the native bridge. */
class PlexConnection(context: Context, private val changed: () -> Unit, private val openBrowser: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = PlexStore(context.applicationContext)
    private val api = PlexApi(store.clientId)
    private var message = ""
    private var saved = try { store.read() } catch (_: Exception) {
        message = "Plex sign-in could not be restored. Please sign in again."
        JSONObject()
    }
    private var token = saved.optString("token")
    private var pending = saved.optJSONObject("pending")
    private var selection = saved.optJSONObject("selection")
    private var servers = emptyList<PlexServer>()
    private var libraries = emptyList<PlexLibrary>()
    private var candidate: PlexServer? = null
    private var connection = ""
    private var state = when {
        pending != null -> "waiting"
        token.isEmpty() -> "signed_out"
        selection != null -> "ready"
        else -> "servers"
    }
    private var busy = false
    private var generation = 0
    private var active = false
    private var closed = false
    @Volatile private var snapshot = "{}"
    private val poll = Runnable { checkPin() }

    init { publish() }

    fun status(): String = snapshot

    internal fun musicSelection(): PlexSelection? = selection?.takeIf { token.isNotEmpty() }?.let {
        PlexSelection(token, it.getString("serverId"), it.getString("libraryId"))
    }

    private fun publish() {
        snapshot = JSONObject().put("state", state).put("busy", busy).put("message", message)
            .put("signedIn", token.isNotEmpty())
            .put("servers", JSONArray(servers.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("libraries", JSONArray(libraries.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("serverName", candidate?.name.orEmpty())
            .put("serverId", candidate?.id.orEmpty())
            .put("selection", selection?.let {
                JSONObject().put("serverName", it.optString("serverName")).put("libraryName", it.optString("libraryName"))
            } ?: JSONObject.NULL).toString()
        if (!closed) changed()
    }

    private fun persist() {
        store.write(JSONObject().put("token", token).put("pending", pending).put("selection", selection))
    }

    // A generation invalidates results after cancellation, sign-out, or activity destruction.
    private fun <T> work(task: () -> T, done: (T) -> Unit, failed: (Exception) -> Unit = { message = "Could not reach Plex. Check your connection and try again." }) {
        if (busy || closed) return
        busy = true
        message = ""
        val version = generation
        publish()
        worker.execute {
            val result = runCatching(task)
            main.post {
                if (closed || version != generation) return@post
                busy = false
                try { result.fold(onSuccess = done, onFailure = { failed(it as? Exception ?: Exception(it)) }) }
                catch (_: Exception) { message = "Could not save Plex setup. Please try again." }
                publish()
            }
        }
    }

    fun resume() {
        active = true
        if (pending != null) checkPin()
    }

    fun pause() { active = false; main.removeCallbacks(poll) }

    fun open() {
        if (pending != null) checkPin()
        else if (token.isNotEmpty() && state == "servers" && servers.isEmpty()) refreshServers()
    }

    fun login() {
        if (busy || token.isNotEmpty()) return
        main.removeCallbacks(poll)
        state = "waiting"
        work({ api.pin() }, { pin ->
            pending = JSONObject().put("id", pin.getLong("id")).put("code", pin.getString("code"))
                .put("expires", System.currentTimeMillis() + pin.optLong("expiresIn", 300).coerceIn(1, 1800) * 1000)
            persist()
            try { openBrowser(api.authUrl(pin.getString("code"))) }
            catch (_: Exception) {
                pending = null; state = "signed_out"; persist()
                message = "No browser could open Plex sign-in. Install or enable a browser, then try again."
            }
            schedulePoll()
        }, {
            state = "signed_out"
            message = "Could not start Plex sign-in. Check your connection and try again."
        })
    }

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        if (active && pending != null) main.postDelayed(poll, 2000)
    }

    private fun checkPin() {
        val pin = pending ?: return
        if (busy) return
        if (System.currentTimeMillis() >= pin.optLong("expires")) { expirePin(); publish(); return }
        val id = pin.getLong("id")
        val code = pin.getString("code")
        work({ api.checkPin(id, code) }, { response ->
            val auth = if (response.isNull("authToken")) "" else response.optString("authToken")
            if (auth.isNotBlank()) {
                token = auth; pending = null; state = "servers"; persist()
                main.post { refreshServers() }
            } else schedulePoll()
        }, { error ->
            if (error is PlexHttpError && error.status in listOf(404, 410)) expirePin()
            else {
                message = "Could not check sign-in. Return here after signing in, then tap Check sign-in."
                // Explicit retry avoids an endless retry loop while offline.
            }
        })
    }

    private fun expirePin() {
        pending = null; state = "signed_out"
        try { persist() } catch (_: Exception) { }
        message = "Plex sign-in expired. Please sign in again."
    }

    fun cancelLogin() {
        if (state != "waiting") return
        generation++; busy = false; main.removeCallbacks(poll)
        pending = null; state = "signed_out"; message = ""
        try { persist() } catch (_: Exception) { message = "Could not save cancellation. Please try again." }
        publish()
    }

    fun refreshServers() {
        if (token.isEmpty() || busy) return
        state = "servers"; candidate = null; libraries = emptyList(); connection = ""
        val auth = token
        work({ api.servers(auth) }, { list ->
            servers = list
            if (list.isEmpty()) message = "No Plex servers are available to this account. Check your server is signed in to Plex."
        }, { error ->
            servers = emptyList()
            if (error is PlexHttpError && error.status == 401) {
                token = ""; selection = null; state = "signed_out"; persist()
                message = "Your Plex sign-in is no longer valid. Please sign in again."
            } else message = "Could not load your servers. Check your connection and try again."
        })
    }

    fun selectServer(id: String) {
        if (busy) return
        val server = servers.find { it.id == id } ?: return
        candidate = server; libraries = emptyList(); connection = ""; state = "libraries"
        work({
            var found: Pair<String, List<PlexLibrary>>? = null
            for (url in server.connections) {
                try { found = url to api.libraries(url, server.token); break } catch (_: Exception) { }
            }
            found ?: throw java.io.IOException("No reachable connection")
        }, { (url, list) ->
            connection = url; libraries = list
            if (list.isEmpty()) message = "This server has no music libraries available to your account. Choose another server."
        }, {
            message = "Could not connect securely to this server. Check it is running, secure connections are enabled, and your phone can reach it."
        })
    }

    fun selectLibrary(id: String) {
        if (busy || connection.isEmpty()) return
        val server = candidate ?: return
        val library = libraries.find { it.id == id } ?: return
        val chosen = JSONObject().put("serverId", server.id).put("serverName", server.name)
            .put("serverToken", server.token).put("connection", connection)
            .put("libraryId", library.id).put("libraryName", library.name)
        try {
            store.write(JSONObject().put("token", token).put("selection", chosen))
            selection = chosen; state = "ready"; message = ""
        } catch (_: Exception) { message = "Could not save your library. Please try again." }
        publish()
    }

    fun signOut() {
        generation++; busy = false; main.removeCallbacks(poll)
        try {
            store.clear()
            token = ""; pending = null; selection = null; servers = emptyList(); libraries = emptyList()
            candidate = null; connection = ""; state = "signed_out"; message = ""
        } catch (_: Exception) { message = "Could not remove saved Plex sign-in. Please try again." }
        publish()
    }

    fun close() { closed = true; generation++; pause(); worker.shutdownNow() }
}
