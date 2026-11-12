package com.music.bitchord

import com.music.bitchord.data.subsonic.SubsonicClient
import com.music.bitchord.data.subsonic.SubsonicCredentials
import com.music.bitchord.data.subsonic.SubsonicException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SubsonicClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SubsonicClient(
            SubsonicCredentials(server.url("/").toString().trimEnd('/'), "dario", "sesame"),
            http = OkHttpClient(),
            salt = { "c19b2d" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun `token auth uses md5 of password plus salt`() = runTest {
        respond("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.58.0","openSubsonic":true}}""")
        val info = client.ping()
        val url = server.takeRequest().requestUrl!!
        assertEquals("/rest/ping", url.encodedPath)
        assertEquals("dario", url.queryParameter("u"))
        assertEquals("c19b2d", url.queryParameter("s"))
        // Example from the Subsonic API docs: md5("sesamec19b2d")
        assertEquals("26719a1196d2a940705a59634eb18eab", url.queryParameter("t"))
        assertEquals("json", url.queryParameter("f"))
        assertNull(url.queryParameter("p"))
        assertEquals("navidrome", info.type)
        assertTrue(info.openSubsonic)
    }

    @Test
    fun `failed status becomes SubsonicException with auth flag`() = runTest {
        respond("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
        try {
            client.ping()
            fail("expected an exception")
        } catch (e: SubsonicException) {
            assertEquals(40, e.code)
            assertTrue(e.isAuthError)
            assertEquals("Wrong username or password", e.message)
        }
    }

    @Test
    fun `html page is reported as not a subsonic server`() = runTest {
        respond("<html><body>hello</body></html>")
        try {
            client.ping()
            fail("expected an exception")
        } catch (e: SubsonicException) {
            assertFalse(e.isAuthError)
            assertTrue(e.message.startsWith("Not a Subsonic server"))
        }
    }

    @Test
    fun `album parses songs and lenient shapes`() = runTest {
        // A single song as an object rather than an array, numbers as strings.
        respond(
            """{"subsonic-response":{"status":"ok","album":{"id":"al1","name":"Kind of Blue",
            "artist":"Miles Davis","artistId":"ar1","coverArt":"al-al1","songCount":"1","year":1959,
            "song":{"id":"s1","title":"So What","artist":"Miles Davis","albumId":"al1","album":"Kind of Blue",
            "duration":"562","track":1,"suffix":"flac","bitDepth":24,"samplingRate":96000,"starred":"2026-01-01T00:00:00Z"}}}}""",
        )
        val (album, songs) = client.album("al1")
        assertEquals("/rest/getAlbum", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("Kind of Blue", album.name)
        assertEquals(1, album.songCount)
        assertEquals(1, songs.size)
        val s = songs.single()
        assertEquals("So What", s.title)
        assertEquals(562, s.durationSec)
        assertTrue(s.starred)
        assertTrue(s.isLossless)
    }

    @Test
    fun `missing lists read as empty`() = runTest {
        respond("""{"subsonic-response":{"status":"ok","searchResult3":{}}}""")
        val r = client.search("nothing")
        assertTrue(r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty())
    }

    @Test
    fun `stream url carries auth and bitrate`() {
        val url = client.streamUrl("s1", maxBitRateKbps = 320).toHttpUrl()
        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("s1", url.queryParameter("id"))
        assertEquals("320", url.queryParameter("maxBitRate"))
        assertEquals("dario", url.queryParameter("u"))
        assertNull(client.streamUrl("s1").toHttpUrl().queryParameter("maxBitRate"))
        assertEquals("raw", client.streamUrl("s1", format = "raw").toHttpUrl().queryParameter("format"))
    }

    @Test
    fun `cover urls are stable so caches hit`() {
        assertEquals(client.coverArtUrl("al-1", 300), client.coverArtUrl("al-1", 300))
        assertEquals(client.streamUrl("s1"), client.streamUrl("s1"))
    }

    @Test
    fun `normalizeUrl picks a sensible scheme`() {
        assertEquals("http://192.168.1.5:4533", SubsonicCredentials.normalizeUrl("192.168.1.5:4533"))
        assertEquals("http://100.101.102.103:4533", SubsonicCredentials.normalizeUrl("100.101.102.103:4533/"))
        assertEquals("https://music.example.com", SubsonicCredentials.normalizeUrl("music.example.com"))
        assertEquals("http://nas.local:4533", SubsonicCredentials.normalizeUrl("http://nas.local:4533/rest"))
        assertEquals("https://music.example.com", SubsonicCredentials.normalizeUrl("https://music.example.com/app"))
        assertEquals("https://8.8.8.8", SubsonicCredentials.normalizeUrl("8.8.8.8"))
    }
}
