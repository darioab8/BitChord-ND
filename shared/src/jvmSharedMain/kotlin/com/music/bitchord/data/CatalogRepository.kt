package com.music.bitchord.data

import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.WatchTarget

/**
 * BitChord ND: everything the UI asks of a music catalogue.
 *
 * Extracted from [YtMusicRepository]'s public surface so a Subsonic/Navidrome
 * backend can stand behind the same screens. Calls a backend has no answer for
 * (music-video versions, YouTube account channels, …) are free to return an
 * empty result or a failure; the screens already handle both.
 */
interface CatalogRepository {
    fun cachedAudioVersion(videoId: String): Song?

    fun cachedVideoVersion(videoId: String): Song?

    fun rememberVideoVersion(audioId: String, video: Song): Unit

    fun versionTimeMap(fromId: String, toId: String): VersionTimeMap?

    suspend fun home(): Result<HomeFeed>

    suspend fun homeRecentlyPlayed(): Result<HomeShelf?>

    suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>>

    suspend fun moreHome(token: String): Result<HomeFeed>

    suspend fun history(): Result<List<Song>>

    suspend fun recents(): Result<List<Song>>

    suspend fun removeFromHistory(token: String): Result<Unit>

    fun invalidateHistoryCache(): Unit

    suspend fun quickPicks(excludeSongIds: Set<String> = emptySet()): Result<List<Song>>

    suspend fun moodAndGenres(): Result<List<MoodGenreSection>>

    suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>>

    suspend fun moodGenreArtwork(browseId: String, params: String?): Result<String?>

    suspend fun searchPage(query: String, filter: SearchFilter, anonymous: Boolean = true): Result<SearchPage>

    suspend fun searchContinuation(token: String, filter: SearchFilter): Result<SearchPage>

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>>

    suspend fun searchSuggestions(input: String): Result<List<String>>

    suspend fun searchTypeahead(input: String): Result<SearchPage>

    suspend fun resolveAudio(song: Song): Song

    suspend fun resolveVideo(song: Song): Song?

    suspend fun account(): Result<Account>

    suspend fun accountChannels(): Result<List<AccountChannel>>

    suspend fun library(): Result<LibraryPage>

    suspend fun syncLikedMusic(
        firstToken: String?,
        loadNext: suspend (String) -> SongPage? = { moreSongs(it).getOrNull() },
    ): Unit

    suspend fun radio(videoId: String, preferVideo: Boolean = false): Result<List<Song>>

    suspend fun findArtistPageId(name: String): Result<String?>

    suspend fun trackLinks(videoId: String): Result<Song>

    suspend fun browseSongs(browseId: String): Result<SongPage>

    suspend fun moreSongs(token: String): Result<SongPage>

    suspend fun playlistOwned(browseId: String): Result<Boolean?>

    suspend fun allSongs(browseId: String): Result<List<Song>>

    suspend fun songMenu(videoId: String): Result<SongMenu>

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit>

    suspend fun setLibraryStatus(token: String): Result<Unit>

    suspend fun releaseLibraryState(browseId: String): Result<LibraryState?>

    suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit>

    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit>

    suspend fun userPlaylists(): Result<List<UserPlaylist>>

    suspend fun libraryPlaylists(): Result<List<ShelfItem>>

    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String> = emptyList(),
    ): Result<String>

    suspend fun addToPlaylist(
        playlistId: String,
        videoIds: List<String>,
    ): Result<Map<String, String>>

    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit>

    suspend fun playlistEntries(browseId: String): Result<List<Song>>

    suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit>

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit>

    suspend fun deletePlaylist(playlistId: String): Result<Unit>

    suspend fun artistPage(browseId: String): Result<ArtistPage>

    suspend fun artistLanding(browseId: String): Result<ArtistPage>

    suspend fun watchQueue(target: WatchTarget): Result<List<Song>>

    suspend fun playlistAsAudio(playlistId: String): Result<List<Song>>

    suspend fun mixedForYou(): Result<List<ShelfItem>>

    suspend fun recentHistory(): Result<List<Song>>
}

/** The catalogue the app is currently talking to. */
object Catalog {
    @Volatile
    var current: CatalogRepository = YtMusicRepository
}
