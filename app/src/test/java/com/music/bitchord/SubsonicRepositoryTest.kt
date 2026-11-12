package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.subsonic.SubsonicClient
import com.music.bitchord.data.subsonic.SubsonicCredentials
import com.music.bitchord.data.subsonic.SubsonicIds
import com.music.bitchord.data.subsonic.SubsonicRepository
import com.music.bitchord.data.subsonic.SubsonicSong
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubsonicRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SubsonicRepository
    private val routes = HashMap<String, String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = request.requestUrl!!.pathSegments.last()
                val body = routes[method] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
        val client = SubsonicClient(
            SubsonicCredentials(server.url("/").toString().trimEnd('/'), "dario", "pw"),
            http = OkHttpClient(),
        )
        repo = SubsonicRepository { client }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(inner: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1",$inner}}"""

    @Test
    fun `ids round trip and accept YouTube's VL prefix`() {
        assertEquals("abc", SubsonicIds.songId(SubsonicIds.song("abc")))
        assertEquals("p1", SubsonicIds.playlistId("VL" + SubsonicIds.playlist("p1")))
        assertEquals("Hip-Hop/Rap", SubsonicIds.genreName(SubsonicIds.genre("Hip-Hop/Rap")))
        assertEquals(7, SubsonicIds.entryIndex(SubsonicIds.entry(7)))
        assertNull(SubsonicIds.albumId(SubsonicIds.song("abc")))
        assertNull(SubsonicIds.songId("dQw4w9WgXcQ"))
        assertTrue(SubsonicIds.isSubsonic("VLnd:playlist:p1"))
    }

    @Test
    fun `duration and quality labels`() {
        assertEquals("3:05", SubsonicRepository.formatDuration(185))
        assertEquals("1:01:01", SubsonicRepository.formatDuration(3661))
        assertEquals(
            "FLAC 24/96",
            SubsonicRepository.qualityLabel(SubsonicSong("1", "t", "a", suffix = "flac", bitDepth = 24, samplingRate = 96000)),
        )
        assertEquals(
            "FLAC 16/44.1",
            SubsonicRepository.qualityLabel(SubsonicSong("1", "t", "a", suffix = "flac", bitDepth = 16, samplingRate = 44100)),
        )
        assertEquals("MP3 320", SubsonicRepository.qualityLabel(SubsonicSong("1", "t", "a", suffix = "mp3", bitRate = 320)))
    }

    @Test
    fun `album page maps songs and header`() = runTest {
        routes["getAlbum"] = ok(
            """"album":{"id":"al1","name":"Blue","artist":"Joni Mitchell","year":1971,"coverArt":"al-al1",
            "song":[{"id":"s1","title":"All I Want","artist":"Joni Mitchell","artistId":"ar1","albumId":"al1",
            "album":"Blue","duration":214,"track":1,"coverArt":"al-al1","suffix":"flac","bitDepth":16,"samplingRate":44100}]}""",
        )
        val page = repo.browseSongs(SubsonicIds.album("al1")).getOrThrow()
        val song = page.songs.single()
        assertEquals("nd:song:s1", song.videoId)
        assertEquals("nd:album:al1", song.albumId)
        assertEquals("nd:artist:ar1", song.artistId)
        assertEquals("3:34", song.durationText)
        assertEquals("Blue", page.header?.title)
        assertEquals("Album • Joni Mitchell • 1971", page.header?.subtitle)
        assertTrue(song.thumbnailUrl!!.contains("/rest/getCoverArt"))
    }

    @Test
    fun `free-text release types never reach the credit line`() = runTest {
        routes["getAlbum"] = ok(
            """"album":{"id":"al2","name":"The New Abnormal","artist":"The Strokes","year":2020,
            "releaseTypes":["a"],"song":[{"id":"s1","title":"Selfless","artist":"The Strokes"}]}""",
        )
        val page = repo.browseSongs(SubsonicIds.album("al2")).getOrThrow()
        assertEquals("Album • The Strokes • 2020", page.header?.subtitle)
        assertEquals("Single", SubsonicRepository.releaseKind(com.music.bitchord.data.subsonic.SubsonicAlbum("x", "x", "x", releaseTypes = listOf("single"))))
        assertEquals("EP", SubsonicRepository.releaseKind(com.music.bitchord.data.subsonic.SubsonicAlbum("x", "x", "x", releaseTypes = listOf("Album;EP"))))
    }

    @Test
    fun `playlist rows get index entry ids`() = runTest {
        routes["getPlaylist"] = ok(
            """"playlist":{"id":"p1","name":"Mix","owner":"dario","songCount":2,
            "entry":[{"id":"a","title":"A","artist":"X"},{"id":"b","title":"B","artist":"Y"}]}""",
        )
        val page = repo.browseSongs("VL" + SubsonicIds.playlist("p1")).getOrThrow()
        assertEquals(listOf("nd:idx:0", "nd:idx:1"), page.songs.map { it.setVideoId })
        assertEquals(true, page.owned)
    }

    @Test
    fun `search all puts top track first then artists and albums`() = runTest {
        routes["search3"] = ok(
            """"searchResult3":{"artist":[{"id":"ar1","name":"Bjork"}],"album":[{"id":"al1","name":"Homogenic","artist":"Bjork"}],
            "song":[{"id":"s1","title":"Joga","artist":"Bjork"},{"id":"s2","title":"Bachelorette","artist":"Bjork"}]}""",
        )
        val rows = repo.search("bjork", SearchFilter.ALL).getOrThrow()
        assertTrue(rows[0] is SearchResult.TopTrack)
        assertEquals(BrowseType.ARTIST, (rows[1] as SearchResult.Browse).item.type)
        assertEquals(BrowseType.ALBUM, (rows[2] as SearchResult.Browse).item.type)
        assertEquals("nd:song:s2", (rows[3] as SearchResult.Track).song.videoId)
    }

    @Test
    fun `radio falls back when the server has no similar songs`() = runTest {
        routes["getSimilarSongs2"] = ok(""""similarSongs2":{}""")
        routes["getSong"] = ok(""""song":{"id":"s1","title":"Joga","artist":"Bjork"}""")
        routes["getTopSongs"] = ok(""""topSongs":{"song":[{"id":"s1","title":"Joga","artist":"Bjork"},{"id":"s9","title":"Hyperballad","artist":"Bjork"}]}""")
        val radio = repo.radio(SubsonicIds.song("s1")).getOrThrow()
        assertEquals(listOf("nd:song:s9"), radio.map { it.videoId })
    }

    @Test
    fun `no client means a clear failure`() = runTest {
        val signedOut = SubsonicRepository { null }
        assertTrue(signedOut.home().exceptionOrNull() is SubsonicRepository.NotSignedIn)
    }
}
