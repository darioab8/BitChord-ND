package com.music.bitchord.data

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song

// BitChord ND: moved out of YtMusicRepository so every CatalogRepository can
// return them, not just the YouTube one.

/** One page of a search response, with the token for its next page if present. */
data class SearchPage(
    val rows: List<SearchResult>,
    val continuation: String?,
)

/**
 * One page of a browse feed's tracks, and the token for the page after
 * it — null once there is nothing more. [suggested] is only ever
 * non-empty for a playlist page — see [InnertubeParser.parsePlaylistShelf].
 */
data class SongPage(
    val songs: List<Song>,
    val continuation: String?,
    val suggested: List<Song> = emptyList(),
    /**
     * Whether the release this page describes is in the library. Only the
     * first page can answer — a continuation carries rows and nothing else
     * — so it is null from [moreSongs] and must not overwrite what
     * [browseSongs] already established.
     */
    val library: LibraryState? = null,
    /**
     * Whether this page's playlist is one the account made rather than one
     * it saved — see [InnertubeParser.parsePlaylistOwned]. Null for an
     * album, a continuation, or a page that doesn't say.
     */
    val owned: Boolean? = null,
    /**
     * What the page calls itself — only needed by callers that opened it
     * with nothing but a browse id, i.e. a tapped link. Null on a
     * continuation, which carries rows and no header.
     */
    val header: InnertubeParser.BrowseHeader? = null,
    /**
     * The editorial blurb YouTube Music writes for the release, when it
     * has one — see [InnertubeParser.parseDescription]. Null from a
     * continuation, same as [header].
     */
    val description: String? = null,
)
