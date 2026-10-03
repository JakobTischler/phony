package com.hughhowey.phony.plex

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Authenticated range requests. Synthetic media URIs keep credentials out of media sessions. */
internal class PlexStreamDataSource : BaseDataSource(true) {
    private var input: PlexAudioStream? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (endpoint, part) = PlexPlayback.stream(dataSpec.uri.toString()) ?: throw IOException("Plex queue is no longer available")
        val stream = PlexAudioStream(endpoint, part, dataSpec.position, dataSpec.length)
        input = stream; uri = dataSpec.uri
        opened = true; transferStarted(dataSpec)
        return stream.remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = input?.read(buffer, offset, length) ?: throw IOException("Plex stream is closed")
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        try { input?.close() } finally {
            input = null; uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
