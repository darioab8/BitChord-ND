package com.music.bitchord.data

import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
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
import com.music.bitchord.data.shelves.AlbumShelves
import com.music.bitchord.data.subsonic.SubsonicIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import java.util.Locale

/**
 * BitChord ND: the user's server first, YouTube Music after it.
 *
 * Everything that is the user's own — home, library, playlists, account,
 * history — is the server's. Anything keyed by an id goes where the id came
 * from: `nd:` ids to the server, every other id to YouTube Music, so a YouTube
 * album or artist opened from search is a working YouTube page. Search asks
 * both and lists the server's results first (see [searchPage]).
 *
 * Writing to YouTube needs a YouTube account, which this app doesn't sign in
 * to: likes, follows and library saves on YouTube items fail with
 * [YouTubeReadOnly], which the screens show as an error.
 *
 * @param youtubeInSearch whether search adds YouTube results at all.
 */
class CompositeCatalog(
    private val server: CatalogRepository,
    private val youtube: CatalogRepository = YtMusicRepository,
    private val youtubeInSearch: () -> Boolean = { true },
) : CatalogRepository {

    class YouTubeReadOnly : Exception("YouTube Music items can be played, not saved, liked or followed")

    private suspend fun <T> runShelf(block: suspend () -> T): Result<T> =
        runCatching { block() }.onFailure { if (it is CancellationException) throw it }

    private fun forId(id: String?): CatalogRepository = if (SubsonicIds.isSubsonic(id)) server else youtube

    // ---- the user's own: always the server ------------------------------------

    override suspend fun home(): Result<HomeFeed> = coroutineScope {
        val shelves = async { currentShelves() }
        server.home().map { feed -> feed.copy(shelves = AlbumShelves.placeOnHome(feed.shelves, shelves.await())) }
    }
    override suspend fun homeRecentlyPlayed() = server.homeRecentlyPlayed()
    override suspend fun homeSupplement(browseId: String) = server.homeSupplement(browseId)
    override suspend fun moreHome(token: String) = server.moreHome(token)
    override suspend fun history() = server.history()
    override suspend fun recents() = server.recents()
    override suspend fun recentHistory() = server.recentHistory()
    override suspend fun removeFromHistory(token: String) = server.removeFromHistory(token)
    override fun invalidateHistoryCache() = server.invalidateHistoryCache()
    override suspend fun quickPicks(excludeSongIds: Set<String>) = server.quickPicks(excludeSongIds)
    override suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = server.moodAndGenres()
    override suspend fun account(): Result<Account> = server.account()
    override suspend fun accountChannels(): Result<List<AccountChannel>> = server.accountChannels()
    override suspend fun library(): Result<LibraryPage> = coroutineScope {
        val shelves = async { currentShelves() }
        server.library().map { page ->
            page.copy(
                shelves = AlbumShelves.placeInLibrary(page.shelves, shelves.await(), YtMusicRepository.PLAYLISTS_SHELF),
            )
        }
    }

    /** The shelves read fresh, or the last answer if the companion can't be reached; null without shelves. */
    private suspend fun currentShelves(): List<com.music.bitchord.data.companion.AlbumShelf>? {
        AlbumShelves.refresh()
        return AlbumShelves.shelves.value
    }
    override suspend fun syncLikedMusic(firstToken: String?, loadNext: suspend (String) -> SongPage?) =
        server.syncLikedMusic(firstToken, loadNext)
    override suspend fun setLibraryStatus(token: String) = server.setLibraryStatus(token)
    override suspend fun userPlaylists(): Result<List<UserPlaylist>> = server.userPlaylists()
    override suspend fun libraryPlaylists(): Result<List<ShelfItem>> = server.libraryPlaylists()
    override suspend fun mixedForYou(): Result<List<ShelfItem>> = server.mixedForYou()

    // Playlists are the server's. A YouTube song can't be added to one (the
    // server only holds its own files), so only server songs are passed on.
    override suspend fun createPlaylist(title: String, privacy: PlaylistPrivacy, videoIds: List<String>) =
        server.createPlaylist(title, privacy, videoIds.filter(SubsonicIds::isSubsonic))
    override suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Result<Map<String, String>> {
        val own = videoIds.filter(SubsonicIds::isSubsonic)
        if (own.isEmpty() && videoIds.isNotEmpty()) return Result.failure(YouTubeReadOnly())
        return server.addToPlaylist(playlistId, own)
    }
    override suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) =
        server.removeFromPlaylist(playlistId, entries)
    override suspend fun reorderPlaylist(playlistId: String, current: List<String>, target: List<String>) =
        server.reorderPlaylist(playlistId, current, target)
    override suspend fun renamePlaylist(playlistId: String, title: String) = server.renamePlaylist(playlistId, title)
    override suspend fun deletePlaylist(playlistId: String) = server.deletePlaylist(playlistId)

    // ---- by id ------------------------------------------------------------------

    override fun cachedAudioVersion(videoId: String) = forId(videoId).cachedAudioVersion(videoId)
    override fun cachedVideoVersion(videoId: String) = forId(videoId).cachedVideoVersion(videoId)
    override fun rememberVideoVersion(audioId: String, video: Song) = forId(audioId).rememberVideoVersion(audioId, video)
    override fun versionTimeMap(fromId: String, toId: String): VersionTimeMap? =
        if (SubsonicIds.isSubsonic(fromId) || SubsonicIds.isSubsonic(toId)) null
        else youtube.versionTimeMap(fromId, toId)
    override suspend fun resolveAudio(song: Song) = forId(song.videoId).resolveAudio(song)
    override suspend fun resolveVideo(song: Song) = forId(song.videoId).resolveVideo(song)
    override suspend fun moodGenreShelves(browseId: String, params: String?) = forId(browseId).moodGenreShelves(browseId, params)
    override suspend fun moodGenreArtwork(browseId: String, params: String?) = forId(browseId).moodGenreArtwork(browseId, params)
    override suspend fun radio(videoId: String, preferVideo: Boolean) = forId(videoId).radio(videoId, preferVideo)
    override suspend fun trackLinks(videoId: String) = forId(videoId).trackLinks(videoId)
    override suspend fun browseSongs(browseId: String): Result<SongPage> =
        AlbumShelves.shelfId(browseId)?.let { id -> runShelf { SongPage(AlbumShelves.songs(id), null) } }
            ?: forId(browseId).browseSongs(browseId)
    /** The server's pages come whole, so any continuation token is YouTube's. */
    override suspend fun moreSongs(token: String) = youtube.moreSongs(token)
    override suspend fun playlistOwned(browseId: String): Result<Boolean?> =
        if (SubsonicIds.isSubsonic(browseId)) server.playlistOwned(browseId) else Result.success(false)
    override suspend fun allSongs(browseId: String): Result<List<Song>> =
        AlbumShelves.shelfId(browseId)?.let { id -> runShelf { AlbumShelves.songs(id) } }
            ?: forId(browseId).allSongs(browseId)
    override suspend fun playlistEntries(browseId: String): Result<List<Song>> =
        AlbumShelves.shelfId(browseId)?.let { id -> runShelf { AlbumShelves.songs(id) } }
            ?: forId(browseId).playlistEntries(browseId)
    override suspend fun playlistAsAudio(playlistId: String): Result<List<Song>> =
        AlbumShelves.shelfId(playlistId)?.let { id -> runShelf { AlbumShelves.songs(id) } }
            ?: forId(playlistId).playlistAsAudio(playlistId)
    override suspend fun artistPage(browseId: String) = forId(browseId).artistPage(browseId)
    override suspend fun artistLanding(browseId: String) = forId(browseId).artistLanding(browseId)
    override suspend fun watchQueue(target: WatchTarget) = forId(target.videoId ?: target.playlistId).watchQueue(target)

    override suspend fun songMenu(videoId: String): Result<SongMenu> =
        if (SubsonicIds.isSubsonic(videoId)) server.songMenu(videoId) else Result.failure(YouTubeReadOnly())

    override suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> =
        if (SubsonicIds.isSubsonic(videoId)) server.rate(videoId, status) else Result.failure(YouTubeReadOnly())

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> =
        if (SubsonicIds.isSubsonic(channelId)) server.setSubscribed(channelId, subscribed) else Result.failure(YouTubeReadOnly())

    override suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit> =
        if (SubsonicIds.isSubsonic(playlistId)) server.setSaved(playlistId, saved) else Result.failure(YouTubeReadOnly())

    override suspend fun releaseLibraryState(browseId: String): Result<com.music.bitchord.data.model.LibraryState?> =
        if (SubsonicIds.isSubsonic(browseId)) server.releaseLibraryState(browseId) else Result.success(null)

    /** The server's artist if it has one by that name, else YouTube's. */
    override suspend fun findArtistPageId(name: String): Result<String?> {
        server.findArtistPageId(name).getOrNull()?.let { return Result.success(it) }
        return youtube.findArtistPageId(name)
    }

    // ---- search: the server first, YouTube after ----------------------------------

    /**
     * The server's results, then YouTube Music's.
     *
     * Both are asked at once. While the server has more pages, only its rows
     * are returned and the continuation walks the server; YouTube's first
     * page is added once the server has nothing more, then YouTube's own
     * continuations follow. YouTube rows the server already has (same title
     * and artist) are dropped, and only one top result is kept — the server's
     * when it has one.
     *
     * A failing YouTube only costs its rows; a failing server fails the search
     * unless YouTube found something.
     */
    override suspend fun searchPage(query: String, filter: SearchFilter, anonymous: Boolean): Result<SearchPage> =
        coroutineScope {
            val yt = if (youtubeInSearch()) async { safely { youtube.searchPage(query, filter, true) } } else null
            val own = server.searchPage(query, filter, anonymous)
            val ownPage = own.getOrNull()
            if (ownPage?.continuation != null) {
                yt?.cancel()
                return@coroutineScope Result.success(SearchPage(ownPage.rows, serverToken(ownPage.continuation, query)))
            }
            val ytPage = yt?.await()?.getOrNull()
            when {
                ownPage == null && ytPage == null -> own
                else -> Result.success(merge(ownPage?.rows.orEmpty(), ytPage, serverHadResults = !ownPage?.rows.isNullOrEmpty()))
            }
        }

    override suspend fun searchContinuation(token: String, filter: SearchFilter): Result<SearchPage> {
        parseServerToken(token)?.let { (serverToken, query) ->
            val page = server.searchContinuation(serverToken, filter).getOrElse { return Result.failure(it) }
            if (page.continuation != null) {
                return Result.success(SearchPage(page.rows, serverToken(page.continuation, query)))
            }
            // The server is done: YouTube's results follow its last page.
            val ytPage = if (youtubeInSearch()) safely { youtube.searchPage(query, filter, true) }.getOrNull() else null
            return Result.success(merge(page.rows, ytPage, serverHadResults = true))
        }
        val ytToken = token.removePrefix(YT_TOKEN)
        return youtube.searchContinuation(ytToken, filter).map { page ->
            SearchPage(page.rows.map(::demoteTopTrack), page.continuation?.let { YT_TOKEN + it })
        }
    }

    override suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        searchPage(query, filter, true).map { it.rows }

    override suspend fun searchSuggestions(input: String): Result<List<String>> = coroutineScope {
        val yt = if (youtubeInSearch()) async { safely { youtube.searchSuggestions(input) } } else null
        val own = server.searchSuggestions(input).getOrDefault(emptyList())
        val theirs = yt?.await()?.getOrNull().orEmpty()
        Result.success((own + theirs).distinctBy { it.trim().lowercase(Locale.ROOT) }.take(MAX_SUGGESTIONS))
    }

    override suspend fun searchTypeahead(input: String): Result<SearchPage> = coroutineScope {
        val yt = if (youtubeInSearch()) async { safely { youtube.searchTypeahead(input) } } else null
        val own = server.searchTypeahead(input).getOrNull()
        val theirs = yt?.await()?.getOrNull()
        if (own == null && theirs == null) return@coroutineScope server.searchTypeahead(input)
        Result.success(SearchPage(merge(own?.rows.orEmpty(), theirs, serverHadResults = !own?.rows.isNullOrEmpty()).rows, null))
    }

    /**
     * @param own the server rows on this page (YouTube duplicates of these are dropped)
     * @param serverHadResults whether the server found anything for this search,
     *   on this page or an earlier one: if so the top result is the server's
     *   (or none), never YouTube's.
     */
    private fun merge(own: List<SearchResult>, yt: SearchPage?, serverHadResults: Boolean): SearchPage {
        val seen = own.mapTo(HashSet(), ::identity)
        val theirs = yt?.rows.orEmpty()
            .filter { identity(it) !in seen }
            .map { if (serverHadResults) demoteTopTrack(it) else it }
        return SearchPage(rows = own + theirs, continuation = yt?.continuation?.let { YT_TOKEN + it })
    }

    private fun demoteTopTrack(row: SearchResult) = if (row is SearchResult.TopTrack) SearchResult.Track(row.song) else row

    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val SERVER_TOKEN = "srv\u0001"
        private const val YT_TOKEN = "yt\u0001"
        private const val MAX_SUGGESTIONS = 10

        private fun serverToken(token: String, query: String) = "$SERVER_TOKEN$token\u0001$query"

        private fun parseServerToken(token: String): Pair<String, String>? {
            if (!token.startsWith(SERVER_TOKEN)) return null
            val rest = token.removePrefix(SERVER_TOKEN)
            val cut = rest.indexOf('\u0001').takeIf { it >= 0 } ?: return null
            return rest.substring(0, cut) to rest.substring(cut + 1)
        }

        /** What makes a YouTube row "the same" as a server one. */
        fun identity(row: SearchResult): String = when (row) {
            is SearchResult.TopTrack -> "song:" + norm(row.song.title) + "|" + norm(row.song.artist)
            is SearchResult.Track -> "song:" + norm(row.song.title) + "|" + norm(row.song.artist)
            is SearchResult.Browse -> "${row.item.type}:" + norm(row.item.title)
        }

        /** Case, accents, punctuation and "(Remastered 2011)"-style tails ignored. */
        fun norm(s: String): String {
            val base = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("\\s*[(\\[][^)\\]]*(remaster|version|edit|mono|stereo|deluxe|bonus)[^)\\]]*[)\\]]"), "")
            return base.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }
    }
}
