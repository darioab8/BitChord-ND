package com.music.bitchord.data.subsonic

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * BitChord ND: how Subsonic things are named inside the app.
 *
 * The app keys everything on two strings — a track's `videoId` and a page's
 * `browseId` — that were YouTube's own ids upstream. Subsonic ids go in behind
 * an `nd:<kind>:` prefix so they can never be mistaken for YouTube's (or a
 * download's, or a local file's), and so YouTube can be mixed back in later
 * without the two colliding.
 *
 * [UserPlaylist.browseId][com.music.bitchord.data.model.UserPlaylist.browseId]
 * puts YouTube's `VL` in front of whatever playlist id it is given, so every
 * parser here accepts a leading `VL` too.
 */
object SubsonicIds {
    const val PREFIX = "nd:"
    const val SONG = "nd:song:"
    const val ALBUM = "nd:album:"
    const val ARTIST = "nd:artist:"
    const val PLAYLIST = "nd:playlist:"
    const val GENRE = "nd:genre:"
    /** An album list by Subsonic's `getAlbumList2` type, e.g. `nd:list:newest`. */
    const val LIST = "nd:list:"
    /** Starred songs, as a page. */
    const val STARRED_SONGS = "nd:starred:songs"
    /** A playlist row's position, standing in for YouTube's setVideoId. */
    const val ENTRY = "nd:idx:"

    fun song(id: String) = SONG + id
    fun album(id: String) = ALBUM + id
    fun artist(id: String) = ARTIST + id
    fun playlist(id: String) = PLAYLIST + id
    fun genre(name: String) = GENRE + URLEncoder.encode(name, Charsets.UTF_8)
    fun list(type: String) = LIST + type
    fun entry(index: Int) = ENTRY + index

    fun isSubsonic(id: String?): Boolean = id != null && strip(id).startsWith(PREFIX)

    fun songId(id: String?): String? = after(id, SONG)
    fun albumId(id: String?): String? = after(id, ALBUM)
    fun artistId(id: String?): String? = after(id, ARTIST)
    fun playlistId(id: String?): String? = after(id, PLAYLIST)
    fun genreName(id: String?): String? = after(id, GENRE)?.let { URLDecoder.decode(it, Charsets.UTF_8) }
    fun listType(id: String?): String? = after(id, LIST)
    fun entryIndex(id: String?): Int? = after(id, ENTRY)?.toIntOrNull()

    private fun strip(id: String) = if (id.startsWith("VL$PREFIX")) id.substring(2) else id

    private fun after(id: String?, prefix: String): String? {
        val s = strip(id ?: return null)
        return if (s.startsWith(prefix)) s.substring(prefix.length).takeIf { it.isNotEmpty() } else null
    }
}
