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
    fun `job details carry the tracks, and retry and remove reach the right calls`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"abc123abc123","state":"failed","title":"S&M","message":"7. Master of Puppets didn't download (1 of 21 failed). Retry it, or import the album without it.\n7. Master of Puppets: ERROR: Video unavailable",
                "finished":true,"failedTracks":1,"retryable":true,
                "tracks":[{"number":6,"title":"The Thing That Should Not Be","state":"done","error":""},
                          {"number":7,"title":"Master of Puppets","state":"failed","error":"ERROR: Video unavailable"}]}""",
            ),
        )
        val job = client.job("abc123abc123")
        assertEquals("/api/jobs/abc123abc123", server.takeRequest().path)
        assertEquals(1, job.failedTracks)
        assertTrue(job.retryable)
        assertEquals("failed", job.tracks[1].state)
        assertEquals("ERROR: Video unavailable", job.tracks[1].error)
        assertTrue(job.summary.startsWith("7. Master of Puppets didn't download"))
        assertFalse(job.summary.contains("\n"))

        server.enqueue(MockResponse().setBody("""{"id":"abc123abc123","state":"queued"}"""))
        client.retry("abc123abc123", skipFailed = true)
        val retry = server.takeRequest()
        assertEquals("POST", retry.method)
        assertEquals("/api/jobs/abc123abc123/retry", retry.path)
        assertEquals("true", Json.parseToJsonElement(retry.body.readUtf8()).jsonObject["skipFailed"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        client.remove("abc123abc123")
        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("/api/jobs/abc123abc123", remove.path)
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

    @Test
    fun `shelves round trip`() = runTest {
        server.enqueue(MockResponse().setBody("""{"shelves":[{"id":"a1b2c3d4e5f6","name":"No letters","count":1,"preview":[{"id":"nd:album:1","title":"1989","coverArt":"al-1"}],"albumIds":["nd:album:1"]}]}"""))
        val shelves = client.shelves()
        assertEquals("No letters", shelves.single().name)
        assertEquals(listOf("nd:album:1"), shelves.single().albumIds)
        assertEquals("al-1", shelves.single().preview.single().coverArt)
        assertEquals("/api/shelves", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"id":"a1b2c3d4e5f6","name":"No letters","albums":[]}"""))
        client.addToShelf("a1b2c3d4e5f6", com.music.bitchord.data.companion.ShelfAlbum("MPREb_x", "Album", "Band", 1999))
        val add = server.takeRequest()
        assertEquals("/api/shelves/a1b2c3d4e5f6/albums", add.path)
        val album = Json.parseToJsonElement(add.body.readUtf8()).jsonObject["album"]!!.jsonObject
        assertEquals("MPREb_x", album["id"]!!.jsonPrimitive.content)
        assertEquals("1999", album["year"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setBody("""{"id":"a1b2c3d4e5f6","name":"Renamed"}"""))
        client.editShelf("a1b2c3d4e5f6", name = "Renamed", order = listOf("b", "a"))
        val edit = server.takeRequest()
        assertEquals("PUT", edit.method)
        val body = Json.parseToJsonElement(edit.body.readUtf8()).jsonObject
        assertEquals("Renamed", body["name"]!!.jsonPrimitive.content)
        assertFalse("description" in body)
        assertEquals(2, body["order"]!!.jsonArray.size)
    }
}
