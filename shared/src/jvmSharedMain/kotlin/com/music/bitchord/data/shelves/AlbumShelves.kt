package com.music.bitchord.data.shelves

import com.music.bitchord.data.Catalog
import com.music.bitchord.data.companion.AlbumShelf
import com.music.bitchord.data.companion.CompanionClient
import com.music.bitchord.data.companion.ShelfAlbum
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.subsonic.SubsonicIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * BitChord ND: shelves — the owner's own lists of albums, kept by the
 * companion (see `companion/bitchord_companion/shelves.py`).
 *
 * A shelf is shown wherever playlists are (Home and Library) as a card with
 * the browse id `nd:shelf:<id>`, and opens as a grid of album cards. Its
 * albums can be the server's or YouTube Music's: each keeps its own browse id
 * and opens where that id belongs.
 *
 * The app wires [client] (the companion, when one is set up) and [serverCover]
 * (a Subsonic cover URL for a cover-art id) at startup.
 */
object AlbumShelves {

    const val PREFIX = "nd:shelf:"

    /** The Library row's title, and how the Library screen recognises it. */
    const val LIBRARY_TITLE = "Shelves"
    const val HOME_TITLE = "Your shelves"

    private const val COVER_PX = 600

    /** The companion, or null when none is set up. */
    @Volatile
    var client: () -> CompanionClient? = { null }

    /** A server album's artwork URL, from its Subsonic cover-art id. */
    @Volatile
    var serverCover: (coverArt: String, size: Int) -> String? = { _, _ -> null }

    private val _shelves = MutableStateFlow<List<AlbumShelf>?>(null)

    /**
     * Every shelf as last read, without albums (but with their ids). Null until
     * read once, or when no companion keeps shelves.
     */
    val shelves: StateFlow<List<AlbumShelf>?> = _shelves.asStateFlow()

    fun id(shelfId: String) = PREFIX + shelfId

    fun isShelf(browseId: String?): Boolean = browseId?.removePrefix("VL")?.startsWith(PREFIX) == true

    fun shelfId(browseId: String?): String? =
        browseId?.removePrefix("VL")?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    val available: Boolean get() = client() != null

    // ---- reading -------------------------------------------------------------

    /**
     * Reads the shelves again. A companion that doesn't keep shelves (older than
     * 0.3.0) or can't be reached leaves the last answer in place.
     */
    suspend fun refresh(): Result<List<AlbumShelf>> {
        val c = client() ?: run {
            _shelves.value = null
            return Result.success(emptyList())
        }
        return runCatching { c.shelves() }
            .onSuccess { _shelves.value = it }
            .onFailure { if (it is CancellationException) throw it }
    }

    suspend fun shelf(shelfId: String): Result<AlbumShelf> {
        val c = client() ?: return Result.failure(IllegalStateException("Set up the companion first"))
        return runCatching { c.shelf(shelfId) }.onFailure { if (it is CancellationException) throw it }
    }

    /** The Home or Library row: every shelf as a card. */
    fun row(title: String, shelves: List<AlbumShelf>) = HomeShelf(title = title, items = shelves.map(::card))

    /**
     * [rows] with the shelves row put in, or brought up to date: right after the
     * row titled [after] (else at [fallbackIndex]). With [live] null (no
     * companion keeping shelves) the row goes; an empty [live] keeps it only
     * when [keepEmpty] — the Library's row, for its "New shelf" tile.
     */
    fun place(
        rows: List<HomeShelf>,
        live: List<AlbumShelf>?,
        title: String,
        after: String,
        keepEmpty: Boolean,
        fallbackIndex: Int = 0,
    ): List<HomeShelf> {
        val without = rows.filterNot { it.title == title }
        if (live == null || (live.isEmpty() && !keepEmpty)) return without
        val existing = rows.indexOfFirst { it.title == title }
        val at = if (existing >= 0) {
            existing
        } else {
            without.indexOfFirst { it.title == after }.let { if (it >= 0) it + 1 else minOf(fallbackIndex, without.size) }
        }
        return without.toMutableList().apply { add(at.coerceAtMost(size), row(title, live)) }
    }

    fun placeOnHome(rows: List<HomeShelf>, live: List<AlbumShelf>?) =
        place(rows, live, HOME_TITLE, after = "Your playlists", keepEmpty = false, fallbackIndex = 2)

    fun placeInLibrary(rows: List<HomeShelf>, live: List<AlbumShelf>?, playlistsTitle: String) =
        place(rows, live, LIBRARY_TITLE, after = playlistsTitle, keepEmpty = true)

    fun card(shelf: AlbumShelf): ShelfItem {
        val covers = shelf.preview.mapNotNull { coverOf(it, COVER_PX / 2) }
        return ShelfItem(
            title = shelf.name,
            subtitle = if (shelf.count == 1) "Shelf • 1 album" else "Shelf • ${shelf.count} albums",
            thumbnailUrl = shelf.preview.firstOrNull()?.let { coverOf(it, COVER_PX) },
            videoId = null,
            browseId = id(shelf.id),
            collage = covers.takeIf { it.size >= 4 }.orEmpty(),
        )
    }

    /** One album on a shelf, as a card that opens the album. */
    fun albumCard(album: ShelfAlbum) = ShelfItem(
        title = album.title,
        subtitle = listOfNotNull(album.artist.ifBlank { null }, album.year?.toString()).joinToString(" • "),
        thumbnailUrl = coverOf(album, COVER_PX),
        videoId = null,
        browseId = album.id,
    )

    fun coverOf(album: ShelfAlbum, size: Int): String? =
        album.coverArt?.let { serverCover(it, size) } ?: album.cover

    /**
     * Every song on the shelf, album after album, for Play, Shuffle and the
     * queue rows. An album that can't be read is left out rather than failing
     * the rest.
     */
    suspend fun songs(shelfId: String): List<Song> {
        val shelf = shelf(shelfId).getOrThrow()
        val gate = Semaphore(4)
        return coroutineScope {
            shelf.albums.map { album ->
                async {
                    gate.withPermit {
                        Catalog.current.allSongs(album.id).getOrNull().orEmpty()
                            .map { it.copy(albumName = it.albumName ?: album.title) }
                    }
                }
            }.awaitAll().flatten()
        }
    }

    // ---- writing -------------------------------------------------------------

    /**
     * An album card or page as a shelf entry. A server album keeps only its
     * cover-art id (read back out of the cover URL); a YouTube one its artwork URL.
     */
    fun albumFrom(browseId: String, title: String, subtitle: String, thumbnailUrl: String?): ShelfAlbum {
        val (artist, year) = CompanionClient.artistAndYear(subtitle)
        val server = SubsonicIds.isSubsonic(browseId)
        val coverArt = if (server) {
            thumbnailUrl?.toHttpUrlOrNull()?.takeIf { it.encodedPath.endsWith("getCoverArt") || it.encodedPath.endsWith("getCoverArt.view") }
                ?.queryParameter("id")
                ?: SubsonicIds.albumId(browseId)
        } else {
            null
        }
        return ShelfAlbum(
            id = browseId,
            title = title,
            artist = artist,
            year = year,
            cover = thumbnailUrl.takeUnless { server },
            coverArt = coverArt,
        )
    }

    suspend fun create(name: String, description: String = "", first: ShelfAlbum? = null): Result<AlbumShelf> = write {
        it.createShelf(name.trim(), description.trim(), albums = listOfNotNull(first))
    }

    suspend fun edit(shelfId: String, name: String? = null, description: String? = null, order: List<String>? = null) =
        write { it.editShelf(shelfId, name?.trim(), description?.trim(), order) }

    suspend fun delete(shelfId: String): Result<Unit> = write { it.deleteShelf(shelfId) }

    suspend fun add(shelfId: String, album: ShelfAlbum): Result<AlbumShelf> = write { it.addToShelf(shelfId, album) }

    suspend fun remove(shelfId: String, albumId: String): Result<AlbumShelf> =
        write { it.removeFromShelf(shelfId, albumId) }

    /** Runs a change, then reads the list again so every row showing shelves catches up. */
    private suspend fun <T> write(block: suspend (CompanionClient) -> T): Result<T> {
        val c = client() ?: return Result.failure(IllegalStateException("Set up the companion first"))
        val result = runCatching { block(c) }.onFailure { if (it is CancellationException) throw it }
        if (result.isSuccess) refresh()
        return result
    }
}
