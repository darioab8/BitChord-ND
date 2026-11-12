package com.music.bitchord

import com.music.bitchord.data.CatalogRepository
import com.music.bitchord.data.CompositeCatalog
import com.music.bitchord.data.SearchPage
import com.music.bitchord.data.SongPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.subsonic.SubsonicRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeCatalogTest {

    /** Everything a signed-out server says, except search and pages, which are scripted. */
    private class Fake(
        val pages: MutableMap<String, Result<SearchPage>> = mutableMapOf(),
        val continuations: MutableMap<String, Result<SearchPage>> = mutableMapOf(),
    ) : CatalogRepository by SubsonicRepository({ null }) {
        val browsed = mutableListOf<String>()
        override suspend fun searchPage(query: String, filter: SearchFilter, anonymous: Boolean) =
            pages[query] ?: Result.success(SearchPage(emptyList(), null))
        override suspend fun searchContinuation(token: String, filter: SearchFilter) =
            continuations[token] ?: Result.failure(IllegalStateException("no page $token"))
        override suspend fun browseSongs(browseId: String): Result<SongPage> {
            browsed += browseId
            return Result.success(SongPage(emptyList(), null))
        }
    }

    private fun song(id: String, title: String, artist: String = "Bjork") = Song(id, title, artist, null, null)
    private fun track(id: String, title: String, artist: String = "Bjork") = SearchResult.Track(song(id, title, artist))
    private fun top(id: String, title: String) = SearchResult.TopTrack(song(id, title))
    private fun album(id: String, title: String) =
        SearchResult.Browse(BrowseItem(id, title, "Album", null, BrowseType.ALBUM))

    private fun ids(rows: List<SearchResult>) = rows.map {
        when (it) {
            is SearchResult.TopTrack -> "top:" + it.song.videoId
            is SearchResult.Track -> it.song.videoId
            is SearchResult.Browse -> it.item.browseId
        }
    }

    @Test
    fun `server rows first, then YouTube, without duplicates or a second top result`() = runTest {
        val server = Fake(pages = mutableMapOf("joga" to Result.success(SearchPage(
            listOf(top("nd:song:1", "Joga"), album("nd:album:9", "Homogenic")), null,
        ))))
        val yt = Fake(pages = mutableMapOf("joga" to Result.success(SearchPage(
            listOf(
                top("ytA", "Jóga (Remastered 2011)"),
                track("ytB", "Bachelorette"),
                album("MPREb_x", "homogenic"),
            ),
            "next",
        ))))
        val page = CompositeCatalog(server, yt).searchPage("joga", SearchFilter.ALL, true).getOrThrow()
        assertEquals(listOf("top:nd:song:1", "nd:album:9", "ytB"), ids(page.rows))
        assertTrue(page.continuation!!.startsWith("yt"))
    }

    @Test
    fun `nothing on the server keeps YouTube's top result`() = runTest {
        val yt = Fake(pages = mutableMapOf("x" to Result.success(SearchPage(listOf(top("ytA", "X")), null))))
        val page = CompositeCatalog(Fake(), yt).searchPage("x", SearchFilter.ALL, true).getOrThrow()
        assertEquals(listOf("top:ytA"), ids(page.rows))
    }

    @Test
    fun `server pages come before any YouTube page`() = runTest {
        val server = Fake(
            pages = mutableMapOf("q" to Result.success(SearchPage(listOf(track("nd:song:1", "A")), "p2"))),
            continuations = mutableMapOf("p2" to Result.success(SearchPage(listOf(track("nd:song:2", "B")), null))),
        )
        val yt = Fake(
            pages = mutableMapOf("q" to Result.success(SearchPage(listOf(top("ytA", "C")), "y2"))),
            continuations = mutableMapOf("y2" to Result.success(SearchPage(listOf(track("ytB", "D")), null))),
        )
        val catalog = CompositeCatalog(server, yt)
        val first = catalog.searchPage("q", SearchFilter.SONGS, true).getOrThrow()
        assertEquals(listOf("nd:song:1"), ids(first.rows))
        val second = catalog.searchContinuation(first.continuation!!, SearchFilter.SONGS).getOrThrow()
        // The server's last page, then YouTube's first — its top result demoted.
        assertEquals(listOf("nd:song:2", "ytA"), ids(second.rows))
        val third = catalog.searchContinuation(second.continuation!!, SearchFilter.SONGS).getOrThrow()
        assertEquals(listOf("ytB"), ids(third.rows))
        assertNull(third.continuation)
    }

    @Test
    fun `a failing YouTube only costs its rows`() = runTest {
        val server = Fake(pages = mutableMapOf("q" to Result.success(SearchPage(listOf(track("nd:song:1", "A")), null))))
        val yt = Fake(pages = mutableMapOf("q" to Result.failure(java.io.IOException("offline"))))
        val page = CompositeCatalog(server, yt).searchPage("q", SearchFilter.ALL, true).getOrThrow()
        assertEquals(listOf("nd:song:1"), ids(page.rows))
    }

    @Test
    fun `YouTube can be left out of search`() = runTest {
        val yt = Fake(pages = mutableMapOf("q" to Result.success(SearchPage(listOf(track("ytA", "A")), null))))
        val page = CompositeCatalog(Fake(), yt, youtubeInSearch = { false }).searchPage("q", SearchFilter.ALL, true).getOrThrow()
        assertTrue(page.rows.isEmpty())
    }

    @Test
    fun `pages and writes go where the id came from`() = runTest {
        val server = Fake()
        val yt = Fake()
        val catalog = CompositeCatalog(server, yt)
        catalog.browseSongs("nd:album:1")
        catalog.browseSongs("MPREb_abc")
        catalog.browseSongs("VLnd:playlist:p")
        assertEquals(listOf("nd:album:1", "VLnd:playlist:p"), server.browsed)
        assertEquals(listOf("MPREb_abc"), yt.browsed)
        assertTrue(catalog.rate("ytVideoId", LikeStatus.LIKE).exceptionOrNull() is CompositeCatalog.YouTubeReadOnly)
    }

    @Test
    fun `titles match across sources despite accents and remaster tails`() {
        assertEquals(CompositeCatalog.norm("Jóga"), CompositeCatalog.norm("Joga (Remastered 2011)"))
        assertEquals(CompositeCatalog.norm("Hey, Jude!"), CompositeCatalog.norm("hey jude"))
    }
}
