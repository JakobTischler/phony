package com.hughhowey.phony.plex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexApiTest {
    @Test fun `discovery excludes players and resources without usable credentials`() {
        val list = PlexApi.parseServers(JSONArray("""[
          {"clientIdentifier":"player","provides":"player","accessToken":"player-token"},
          {"clientIdentifier":"missing","provides":"server","accessToken":null},
          {"clientIdentifier":"owned","name":"My server","provides":"server","accessToken":"owned-token"},
          {"clientIdentifier":"shared","name":"Friend's server","provides":"player, server","accessToken":"shared-token"}
        ]"""))
        assertEquals(listOf("shared", "owned"), list.map { it.id })
        assertEquals("shared-token", list.first().token)
    }

    @Test fun `secure local addresses precede remote and relay without accepting insecure URLs`() {
        val list = PlexApi.parseServers(JSONArray("""[{
          "clientIdentifier":"server", "provides":"server", "accessToken":"token",
          "connections":[
            {"uri":"https://relay.example","relay":true},
            {"uri":"https://remote.example/"},
            {"uri":"http://192.168.1.2:32400","local":true},
            {"uri":"https://local.example:32400","local":true},
            {"uri":"https://user:password@remote.example"},
            {"uri":"https://remote.example?X-Plex-Token=unsafe"},
            {"uri":"file:///etc/passwd"},
            {"uri":"invalid"},
            {"uri":"https://remote.example/"}
          ]
        }]"""))
        assertEquals(listOf("https://local.example:32400", "https://remote.example", "https://relay.example"), list.single().connections)
    }

    @Test fun `music libraries use artist sections and preserve server keys`() {
        val libraries = PlexApi.parseLibraries(JSONObject("""{"MediaContainer":{"Directory":[
          {"key":"1","type":"movie","title":"Movies"},
          {"key":"2","type":"artist","title":"Music"},
          {"key":"3","type":"show","title":"TV"},
          {"key":"4","type":"artist","title":"Concerts & Classics"},
          {"key":"","type":"artist","title":"Invalid"}
        ]}}"""))
        assertEquals(listOf(PlexLibrary("4", "Concerts & Classics"), PlexLibrary("2", "Music")), libraries)
        assertTrue(PlexApi.parseLibraries(JSONObject("""{"MediaContainer":{"size":0}}""")).isEmpty())
    }

    @Test fun `auth URL encodes client and pin rather than injecting fragment parameters`() {
        val url = PlexApi("client&other=value").authUrl("code+with&symbols")
        assertTrue(url.startsWith("https://app.plex.tv/auth#?"))
        assertTrue(url.contains("clientID=client%26other%3Dvalue"))
        assertTrue(url.contains("code=code%2Bwith%26symbols"))
        assertFalse(url.contains("forwardUrl"))
    }
}
