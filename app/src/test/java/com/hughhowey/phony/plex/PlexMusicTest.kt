package com.hughhowey.phony.plex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexMusicTest {
    private val album = PlexAlbum("7", "A record", "Album artist", "2020", "")

    @Test fun `pagination advances over returned items even when a record is not an album`() {
        val page = PlexMusic.albums(JSONObject("""{"MediaContainer":{"offset":50,"totalSize":53,"Metadata":[
          {"type":"album","ratingKey":"1","title":"First","parentTitle":"Artist","year":1990},
          {"type":"artist","ratingKey":"2"}
        ]}}"""), 50)
        assertEquals(52, page.next)
        assertTrue(page.more)
        assertEquals(listOf("First"), page.items.map { it.title })
        val empty = PlexMusic.albums(JSONObject("""{"MediaContainer":{"offset":52,"totalSize":53}}"""), 52)
        assertFalse(empty.more)
    }

    @Test(expected = IllegalArgumentException::class) fun `off-server stream addresses are rejected`() {
        PlexMusic.url("https://server.example", "//attacker.example/library/parts/1")
    }

    @Test fun `only relative paths stay on the selected server`() {
        assertEquals("https://server.example/library/parts/1/file.flac?download=1", PlexMusic.url("https://server.example", "/library/parts/1/file.flac?download=1"))
        for (path in listOf("https://attacker.example/audio", "/a/../admin", "/audio#fragment", "/\\attacker/audio")) {
            assertTrue(runCatching { PlexMusic.url("https://server.example", path) }.isFailure)
        }
    }

    @Test fun `album tracks sort by disc and track and retain compilation artists`() {
        val result = PlexMusic.tracks(JSONObject("""{"MediaContainer":{"Metadata":[
          {"type":"track","ratingKey":"3","title":"Encore","parentIndex":2,"index":1,"duration":180000,
           "Media":[{"Part":[{"key":"/library/parts/3/file.flac"}]}]},
          {"type":"track","ratingKey":"2","title":"Second","parentIndex":1,"index":2,
           "Media":[{"Part":[{"key":"/library/parts/2/file.flac"}]}]},
          {"type":"track","ratingKey":"1","title":"Opening","parentIndex":1,"index":1,"originalTitle":"Guest artist",
           "Media":[{"Part":[{"key":"/library/parts/1/file.flac"}]}]}
        ]}}"""), album)
        assertEquals(listOf("1", "2", "3"), result.map { it.id })
        assertEquals("Guest artist", result.first().artist)
        assertEquals("Album artist", result.last().artist)
        assertEquals(180.0, result.last().json().getDouble("dur"), 0.001)
        assertFalse(result.last().json().has("part"))
    }

    @Test(expected = IllegalArgumentException::class) fun `missing audio fails the album rather than silently dropping a track`() {
        PlexMusic.tracks(JSONObject("""{"MediaContainer":{"Metadata":[{"type":"track","ratingKey":"1","Media":[]}]}}"""), album)
    }

    @Test fun `side A ends after the first half including odd-length albums`() {
        assertTrue(PlexMusic.pauseAtSideEnd(5, 2, false))
        assertTrue(PlexMusic.pauseAtSideEnd(4, 1, false))
        assertFalse(PlexMusic.pauseAtSideEnd(5, 1, false))
        assertFalse(PlexMusic.pauseAtSideEnd(5, 4, false))
        assertFalse(PlexMusic.pauseAtSideEnd(1, 0, false))
        assertFalse(PlexMusic.pauseAtSideEnd(4, 1, true))
    }
}
