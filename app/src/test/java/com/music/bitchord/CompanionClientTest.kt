package com.music.bitchord

import com.music.bitchord.data.companion.AlbumDownload
import com.music.bitchord.data.companion.AlbumTrack
import com.music.bitchord.data.companion.CompanionClient
import com.music.bitchord.data.companion.CompanionConfig
import com.music.bitchord.data.companion.CompanionException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CompanionClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CompanionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CompanionClient(
            CompanionConfig(server.url("/").toString().trimEnd('/'), "secret-token"),
            http = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `health sends the token and reads the answer`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"version":"0.1.0","premiumCookies":true,"importMode":"host","defaultFolder":"","navidromeRescan":false,"extra":1}"""))
        val health = client.health()
        val request = server.takeRequest()
        assertEquals("/api/health", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertTrue(health.premiumCookies)
        assertTrue(health.importsWithHostBeets)
    }

    @Test
    fun `queue posts the album`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"abc123abc123","state":"queued","title":"Blue","finished":false}"""))
        val job = client.queue(
            AlbumDownload(
                title = "Blue",
                artist = "Joni Mitchell",
                year = 1971,
                browseId = "MPREb_x",
                tracks = listOf(AlbumTrack("abcdefghijk", "All I Want", "Joni Mitchell")),
            ),
        )
        assertEquals("abc123abc123", job.id)
        assertFalse(job.finished)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("Blue", body["title"]!!.jsonPrimitive.content)
        assertEquals(1971, body["year"]!!.jsonPrimitive.content.toInt())
        assertEquals("abcdefghijk", body["tracks"]!!.jsonArray[0].jsonObject["videoId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `errors carry the companion's reason`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Wrong or missing token"}"""))
        try {
            client.health()
            fail("expected an exception")
        } catch (e: CompanionException) {
            assertTrue(e.isAuthError)
            assertEquals("Wrong or missing token", e.message)
        }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Only YouTube / YouTube Music links are accepted"}"""))
        try {
            client.health()
            fail("expected an exception")
        } catch (e: CompanionException) {
            assertFalse(e.isAuthError)
            assertTrue(e.message.startsWith("Only YouTube"))
        }
    }

    @Test
    fun `artist and year come out of a release subtitle`() {
        assertEquals("Joni Mitchell" to 1971, CompanionClient.artistAndYear("Album • Joni Mitchell • 1971"))
        assertEquals("The Strokes" to 2020, CompanionClient.artistAndYear("The Strokes • 2020"))
        assertEquals("Burial" to null, CompanionClient.artistAndYear("EP · Burial"))
        assertEquals("", CompanionClient.artistAndYear("").first)
        assertNull(CompanionClient.artistAndYear("Single • Artist").second)
    }

    @Test
    fun `addresses get a scheme`() {
        assertEquals("http://192.168.1.10:8765", CompanionConfig.normalizeUrl(" 192.168.1.10:8765/ "))
        assertEquals("https://companion.example", CompanionConfig.normalizeUrl("https://companion.example/"))
    }
}
