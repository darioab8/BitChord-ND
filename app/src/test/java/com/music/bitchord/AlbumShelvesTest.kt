package com.music.bitchord

import com.music.bitchord.data.companion.AlbumShelf
import com.music.bitchord.data.companion.CompanionClient
import com.music.bitchord.data.companion.CompanionConfig
import com.music.bitchord.data.companion.ShelfAlbum
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.shelves.AlbumShelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumShelvesTest {

    private fun row(title: String) = HomeShelf(title, emptyList())
    private val one = AlbumShelf(
        id = "a1b2c3d4e5f6",
        name = "No letters",
        count = 5,
        preview = (1..4).map { ShelfAlbum("MPREb_$it", "A$it", cover = "https://lh3.googleusercontent.com/$it") },
    )

    @Test
    fun `library row sits after playlists and stays when empty`() {
        val rows = listOf(row("Playlists"), row("Albums"))
        val placed = AlbumShelves.placeInLibrary(rows, emptyList(), "Playlists")
        assertEquals(listOf("Playlists", "Shelves", "Albums"), placed.map { it.title })
        // No companion keeping shelves: no row at all.
        assertEquals(listOf("Playlists", "Albums"), AlbumShelves.placeInLibrary(placed, null, "Playlists").map { it.title })
    }

    @Test
    fun `home row only when there are shelves and is replaced in place`() {
        val rows = listOf(row("Recently added"), row("Recently played"), row("Your playlists"), row("Discover"))
        assertEquals(rows, AlbumShelves.placeOnHome(rows, emptyList()))
        val placed = AlbumShelves.placeOnHome(rows, listOf(one))
        assertEquals(
            listOf("Recently added", "Recently played", "Your playlists", "Your shelves", "Discover"),
            placed.map { it.title },
        )
        val again = AlbumShelves.placeOnHome(placed, listOf(one, one.copy(id = "000000000000")))
        assertEquals(placed.map { it.title }, again.map { it.title })
        assertEquals(2, again[3].items.size)
    }

    @Test
    fun `a shelf card opens the shelf and has a collage`() {
        val card = AlbumShelves.card(one)
        assertEquals("nd:shelf:a1b2c3d4e5f6", card.browseId)
        assertEquals("a1b2c3d4e5f6", AlbumShelves.shelfId(card.browseId))
        assertEquals(4, card.collage.size)
        assertEquals("Shelf • 5 albums", card.subtitle)
    }

    @Test
    fun `one album is its cover, two or three fill the grid by repeating`() {
        val single = AlbumShelves.card(one.copy(count = 1, preview = one.preview.take(1)))
        assertEquals("https://lh3.googleusercontent.com/1", single.thumbnailUrl)
        assertTrue(single.collage.isEmpty())
        assertEquals(
            listOf("https://lh3.googleusercontent.com/1", "https://lh3.googleusercontent.com/2",
                "https://lh3.googleusercontent.com/2", "https://lh3.googleusercontent.com/1"),
            AlbumShelves.card(one.copy(count = 2, preview = one.preview.take(2))).collage,
        )
        assertEquals(listOf(1, 2, 3, 1), AlbumShelves.gridOf(listOf(1, 2, 3)))
        assertNull(AlbumShelves.card(one.copy(count = 0, preview = emptyList())).thumbnailUrl)
    }

    @Test
    fun `an uploaded cover wins over the albums`() {
        val before = AlbumShelves.client
        AlbumShelves.client = { CompanionClient(CompanionConfig("http://nas:8765", "secret")) }
        try {
            val card = AlbumShelves.card(one.copy(cover = "v1"))
            assertTrue(card.collage.isEmpty())
            val sig = CompanionClient.coverSignature("secret", one.id, "v1")
            assertEquals("http://nas:8765/covers/${one.id}?v=v1&sig=$sig", card.thumbnailUrl)
            // What the companion's Python computes for the same input.
            assertEquals("8258730e8cf34d510524fe88300b1e5e", sig)
        } finally {
            AlbumShelves.client = before
        }
    }

    @Test
    fun `server albums keep only the cover id, youtube albums the url`() {
        val server = AlbumShelves.albumFrom(
            "nd:album:42",
            "Lateralus",
            "Tool • 2001",
            "http://nd.local/rest/getCoverArt?id=al-42&size=600&u=me&t=tok&s=salt",
        )
        assertEquals("al-42", server.coverArt)
        assertNull(server.cover)
        assertEquals("Tool", server.artist)
        assertEquals(2001, server.year)
        val yt = AlbumShelves.albumFrom("MPREb_x", "Blue", "Album • Joni Mitchell • 1971", "https://lh3.googleusercontent.com/x")
        assertEquals("https://lh3.googleusercontent.com/x", yt.cover)
        assertNull(yt.coverArt)
        assertTrue(AlbumShelves.isShelf("nd:shelf:x"))
    }

    @Test
    fun `shelf sorts`() {
        val a = ShelfAlbum("a", "beta", year = 1999, added = 30.0)
        val b = ShelfAlbum("b", "Alpha", year = null, added = null)
        val c = ShelfAlbum("c", "gamma", year = 1972, added = 10.0)
        val d = ShelfAlbum("d", "delta", year = 2010, added = 20.0)
        val shelf = listOf(a, b, c, d)
        fun ids(sort: com.music.bitchord.data.shelves.ShelfSort) =
            AlbumShelves.sorted(shelf, sort).joinToString("") { it.id }
        assertEquals("abcd", ids(com.music.bitchord.data.shelves.ShelfSort.CUSTOM))
        assertEquals("badc", ids(com.music.bitchord.data.shelves.ShelfSort.TITLE_ASC))
        assertEquals("cdab", ids(com.music.bitchord.data.shelves.ShelfSort.TITLE_DESC))
        // No year: last, both ways.
        assertEquals("cadb", ids(com.music.bitchord.data.shelves.ShelfSort.RELEASE_OLDEST))
        assertEquals("dacb", ids(com.music.bitchord.data.shelves.ShelfSort.RELEASE_NEWEST))
        // No date (from before dates were kept): earliest.
        assertEquals("bcda", ids(com.music.bitchord.data.shelves.ShelfSort.ADDED_OLDEST))
        assertEquals("adcb", ids(com.music.bitchord.data.shelves.ShelfSort.ADDED_NEWEST))
    }
}
