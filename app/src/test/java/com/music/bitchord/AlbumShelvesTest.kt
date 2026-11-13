package com.music.bitchord

import com.music.bitchord.data.companion.AlbumShelf
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
}
