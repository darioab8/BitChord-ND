package com.music.bitchord.data.subsonic

import com.music.bitchord.data.CatalogRepository
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.SearchPage
import com.music.bitchord.data.SongPage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.model.WatchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * BitChord ND: the catalogue, served by a Subsonic/Navidrome server.
 *
 * Every screen upstream built for YouTube Music asks [CatalogRepository]; this
 * answers in the same shapes, with Subsonic ids behind [SubsonicIds] prefixes.
 * Calls that only make sense for YouTube (music-video versions, account
 * channels, server-side listening history) answer empty rather than fail, so
 * the screens simply don't draw those parts.
 *
 * @param client the signed-in server, or null when nobody is signed in — every
 *   call then fails with [NotSignedIn], which the screens show as an error.
 */
class SubsonicRepository(private val client: () -> SubsonicClient?) : CatalogRepository {

    class NotSignedIn : Exception("Not signed in to a Subsonic server")

    // ---- mapping ------------------------------------------------------------

    private fun SubsonicClient.cover(coverArt: String?, size: Int = COVER_PX): String? =
        coverArt?.let { coverArtUrl(it, size) }

    internal fun SubsonicClient.toSong(s: SubsonicSong): Song {
        val credits = s.artists.ifEmpty { listOf(SubsonicArtistRef(s.artistId, s.artist)) }
            .filter { it.name.isNotBlank() }
            .map { ArtistRef(it.name, it.id?.let(SubsonicIds::artist)) }
        return Song(
            videoId = SubsonicIds.song(s.id),
            title = s.title,
            artist = s.artist.ifBlank { credits.joinToString(", ") { it.name } },
            thumbnailUrl = cover(s.coverArt),
            durationText = s.durationSec?.let(::formatDuration),
            artistId = s.artistId?.let(SubsonicIds::artist) ?: credits.firstOrNull()?.browseId,
            artists = credits,
            albumId = s.albumId?.let(SubsonicIds::album),
            albumName = s.album,
            trackNumber = s.track,
            sourceQuality = qualityLabel(s),
            sourceHiRes = (s.bitDepth ?: 0) > 16 || (s.samplingRate ?: 0) > 48_000,
        )
    }

    private fun SubsonicClient.albumItem(a: SubsonicAlbum) = ShelfItem(
        title = a.name,
        subtitle = listOfNotNull(a.artist.ifBlank { null }, a.year?.toString()).joinToString(" • "),
        thumbnailUrl = cover(a.coverArt),
        videoId = null,
        browseId = SubsonicIds.album(a.id),
    )

    private fun SubsonicClient.artistItem(a: SubsonicArtist) = ShelfItem(
        title = a.name,
        subtitle = a.albumCount?.let { if (it == 1) "1 album" else "$it albums" }.orEmpty(),
        thumbnailUrl = a.imageUrl ?: cover(a.coverArt),
        videoId = null,
        browseId = SubsonicIds.artist(a.id),
    )

    private fun SubsonicClient.playlistItem(p: SubsonicPlaylist) = ShelfItem(
        title = p.name,
        subtitle = if (p.songCount == 1) "1 song" else "${p.songCount} songs",
        thumbnailUrl = cover(p.coverArt),
        videoId = null,
        browseId = "VL" + SubsonicIds.playlist(p.id),
    )

    private fun SubsonicClient.albumBrowse(a: SubsonicAlbum) = BrowseItem(
        browseId = SubsonicIds.album(a.id),
        title = a.name,
        subtitle = listOfNotNull("Album", a.artist.ifBlank { null }, a.year?.toString()).joinToString(" • "),
        thumbnailUrl = cover(a.coverArt),
        type = BrowseType.ALBUM,
    )

    private fun SubsonicClient.artistBrowse(a: SubsonicArtist) = BrowseItem(
        browseId = SubsonicIds.artist(a.id),
        title = a.name,
        subtitle = "Artist",
        thumbnailUrl = a.imageUrl ?: cover(a.coverArt),
        type = BrowseType.ARTIST,
    )

    private fun SubsonicClient.playlistBrowse(p: SubsonicPlaylist) = BrowseItem(
        browseId = "VL" + SubsonicIds.playlist(p.id),
        title = p.name,
        subtitle = listOfNotNull("Playlist", p.owner).joinToString(" • "),
        thumbnailUrl = cover(p.coverArt),
        type = BrowseType.PLAYLIST,
    )

    // ---- YouTube-only: nothing to say ---------------------------------------

    override fun cachedAudioVersion(videoId: String): Song? = null
    override fun cachedVideoVersion(videoId: String): Song? = null
    override fun rememberVideoVersion(audioId: String, video: Song) = Unit
    override fun versionTimeMap(fromId: String, toId: String): VersionTimeMap? = null
    override suspend fun resolveAudio(song: Song): Song = song
    override suspend fun resolveVideo(song: Song): Song? = null
    override suspend fun accountChannels(): Result<List<AccountChannel>> = Result.success(emptyList())
    override suspend fun mixedForYou(): Result<List<ShelfItem>> = Result.success(emptyList())
    override suspend fun homeSupplement(browseId: String): Result<List<HomeShelf>> = Result.success(emptyList())
    override suspend fun moreHome(token: String): Result<HomeFeed> = Result.success(HomeFeed(emptyList(), null))

    // The server keeps no play history over the API; the app's own Replay
    // log already records every play locally.
    override suspend fun homeRecentlyPlayed(): Result<HomeShelf?> = Result.success(null)
    override suspend fun history(): Result<List<Song>> = Result.success(emptyList())
    override suspend fun recents(): Result<List<Song>> = Result.success(emptyList())
    override suspend fun recentHistory(): Result<List<Song>> = Result.success(emptyList())
    override suspend fun removeFromHistory(token: String): Result<Unit> = Result.success(Unit)
    override fun invalidateHistoryCache() = Unit

    // Library state is stars on Subsonic, handled through rate()/setSubscribed().
    override suspend fun setLibraryStatus(token: String): Result<Unit> = Result.success(Unit)
    override suspend fun releaseLibraryState(browseId: String): Result<LibraryState?> = Result.success(null)
    override suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun syncLikedMusic(firstToken: String?, loadNext: suspend (String) -> SongPage?) = Unit
    override suspend fun moreSongs(token: String): Result<SongPage> = Result.success(SongPage(emptyList(), null))

    // ---- home ---------------------------------------------------------------

    override suspend fun home(): Result<HomeFeed> = call { c ->
        coroutineScope {
            val shelves = listOf(
                "Recently added" to "newest",
                "Recently played" to "recent",
                "Most played" to "frequent",
                "Starred albums" to "starred",
                "Discover" to "random",
            ).map { (title, type) ->
                async {
                    val items = runCatching { c.albumList(type, HOME_SHELF_SIZE) }.getOrDefault(emptyList())
                        .map { c.albumItem(it) }
                    HomeShelf(title = title, items = items, moreBrowseId = SubsonicIds.list(type))
                }
            }
            val playlists = async {
                val items = runCatching { c.playlists() }.getOrDefault(emptyList()).map { c.playlistItem(it) }
                HomeShelf(title = "Your playlists", items = items)
            }
            val all = shelves.awaitAll().toMutableList()
            // Playlists after "Recently played": the second thing most people reach for.
            all.add(minOf(2, all.size), playlists.await())
            HomeFeed(all.filter { it.items.isNotEmpty() }, continuation = null)
        }
    }

    override suspend fun quickPicks(excludeSongIds: Set<String>): Result<List<Song>> = call { c ->
        c.randomSongs(QUICK_PICKS + excludeSongIds.size)
            .map { c.toSong(it) }
            .filterNot { it.videoId in excludeSongIds }
            .take(QUICK_PICKS)
    }

    // ---- genres as moods ------------------------------------------------------

    override suspend fun moodAndGenres(): Result<List<MoodGenreSection>> = call { c ->
        val genres = c.genres().sortedByDescending { it.albumCount }
        if (genres.isEmpty()) emptyList()
        else listOf(MoodGenreSection("Genres", genres.map { MoodGenre(it.name, SubsonicIds.genre(it.name), null) }))
    }

    override suspend fun moodGenreShelves(browseId: String, params: String?): Result<List<HomeShelf>> = call { c ->
        val genre = SubsonicIds.genreName(browseId) ?: return@call emptyList()
        coroutineScope {
            val albums = async { c.albumList("byGenre", 50, genre = genre).map { c.albumItem(it) } }
            val songs = async { c.songsByGenre(genre, 20).map { c.toSong(it) } }
            listOfNotNull(
                songs.await().takeIf { it.isNotEmpty() }?.let { list ->
                    HomeShelf(
                        title = "Songs",
                        items = list.map { s -> ShelfItem(s.title, s.artist, s.thumbnailUrl, s.videoId, null) },
                        layout = ShelfLayout.LIST,
                    )
                },
                albums.await().takeIf { it.isNotEmpty() }?.let { HomeShelf("Albums", it) },
            )
        }
    }

    override suspend fun moodGenreArtwork(browseId: String, params: String?): Result<String?> = call { c ->
        val genre = SubsonicIds.genreName(browseId) ?: return@call null
        c.albumList("byGenre", 1, genre = genre).firstOrNull()?.let { c.cover(it.coverArt) }
    }

    // ---- search ---------------------------------------------------------------

    override suspend fun searchPage(query: String, filter: SearchFilter, anonymous: Boolean): Result<SearchPage> =
        call { c -> searchAt(c, query, filter, 0) }

    override suspend fun searchContinuation(token: String, filter: SearchFilter): Result<SearchPage> = call { c ->
        val (offset, query) = parseSearchToken(token) ?: return@call SearchPage(emptyList(), null)
        searchAt(c, query, filter, offset)
    }

    override suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        searchPage(query, filter, true).map { it.rows }

    private suspend fun searchAt(c: SubsonicClient, query: String, filter: SearchFilter, offset: Int): SearchPage {
        val q = query.trim()
        if (q.isEmpty()) return SearchPage(emptyList(), null)
        return when (filter) {
            SearchFilter.ALL -> {
                val r = c.search(q, songCount = 20, albumCount = 10, artistCount = 5)
                val songs = r.songs.map { c.toSong(it) }
                val rows = buildList {
                    songs.firstOrNull()?.let { add(SearchResult.TopTrack(it)) }
                    r.artists.forEach { add(SearchResult.Browse(c.artistBrowse(it))) }
                    r.albums.forEach { add(SearchResult.Browse(c.albumBrowse(it))) }
                    songs.drop(1).forEach { add(SearchResult.Track(it)) }
                }
                SearchPage(rows, null)
            }
            SearchFilter.SONGS -> {
                val songs = c.search(q, songCount = PAGE, albumCount = 0, artistCount = 0, songOffset = offset).songs
                SearchPage(songs.map { SearchResult.Track(c.toSong(it)) }, nextToken(songs.size, offset, q))
            }
            SearchFilter.ALBUMS -> {
                val albums = c.search(q, songCount = 0, albumCount = PAGE, artistCount = 0, albumOffset = offset).albums
                SearchPage(albums.map { SearchResult.Browse(c.albumBrowse(it)) }, nextToken(albums.size, offset, q))
            }
            SearchFilter.ARTISTS -> {
                val artists = c.search(q, songCount = 0, albumCount = 0, artistCount = PAGE, artistOffset = offset).artists
                SearchPage(artists.map { SearchResult.Browse(c.artistBrowse(it)) }, nextToken(artists.size, offset, q))
            }
            SearchFilter.PLAYLISTS -> {
                val needle = q.lowercase(Locale.ROOT)
                val rows = c.playlists().filter { needle in it.name.lowercase(Locale.ROOT) }
                    .map { SearchResult.Browse(c.playlistBrowse(it)) }
                SearchPage(rows, null)
            }
            SearchFilter.VIDEOS -> SearchPage(emptyList(), null)
        }
    }

    private fun nextToken(got: Int, offset: Int, query: String): String? =
        if (got < PAGE) null else "${offset + got}|$query"

    private fun parseSearchToken(token: String): Pair<Int, String>? {
        val offset = token.substringBefore('|').toIntOrNull() ?: return null
        return offset to token.substringAfter('|')
    }

    override suspend fun searchSuggestions(input: String): Result<List<String>> = call { c ->
        val q = input.trim()
        if (q.length < 2) return@call emptyList()
        val r = c.search(q, songCount = 5, albumCount = 3, artistCount = 3)
        (r.artists.map { it.name } + r.albums.map { it.name } + r.songs.map { it.title })
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(8)
    }

    override suspend fun searchTypeahead(input: String): Result<SearchPage> = call { c ->
        val q = input.trim()
        if (q.length < 2) return@call SearchPage(emptyList(), null)
        val r = c.search(q, songCount = 3, albumCount = 2, artistCount = 2)
        SearchPage(
            r.artists.map { SearchResult.Browse(c.artistBrowse(it)) } +
                r.albums.map { SearchResult.Browse(c.albumBrowse(it)) } +
                r.songs.map { SearchResult.Track(c.toSong(it)) },
            null,
        )
    }

    // ---- account & library ----------------------------------------------------

    override suspend fun account(): Result<Account> = call { c ->
        Account(
            name = c.credentials.username,
            email = c.credentials.baseUrl.substringAfter("://"),
            thumbnailUrl = null,
        )
    }

    override suspend fun library(): Result<LibraryPage> = call { c ->
        coroutineScope {
            val starred = async { c.starred() }
            val playlists = async { runCatching { c.playlists() }.getOrDefault(emptyList()) }
            val albums = async { runCatching { c.albumList("alphabeticalByName", LIBRARY_ALBUMS) }.getOrDefault(emptyList()) }
            val artists = async { runCatching { c.artists() }.getOrDefault(emptyList()) }
            val star = starred.await()
            val liked = star.songs.map { c.toSong(it) }
            LikeState.seedLiked(liked.mapTo(HashSet()) { it.videoId })
            LibraryPage(
                likedSongs = liked,
                librarySongs = emptyList(),
                shelves = listOf(
                    HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, playlists.await().map { c.playlistItem(it) }),
                    HomeShelf("Albums", albums.await().map { c.albumItem(it) }, moreBrowseId = SubsonicIds.list("alphabeticalByName")),
                    HomeShelf("Artists", artists.await().map { c.artistItem(it) }),
                    HomeShelf("Starred albums", star.albums.map { c.albumItem(it) }),
                    HomeShelf("Starred artists", star.artists.map { c.artistItem(it) }),
                ).filter { it.items.isNotEmpty() },
            )
        }
    }

    // ---- pages ----------------------------------------------------------------

    override suspend fun browseSongs(browseId: String): Result<SongPage> = call { c ->
        SubsonicIds.albumId(browseId)?.let { id ->
            val (album, songs) = c.album(id)
            val subtitle = listOfNotNull(
                releaseKind(album),
                album.artist.ifBlank { null },
                album.year?.toString(),
            ).joinToString(" • ")
            return@call SongPage(
                songs = songs.map { c.toSong(it) },
                continuation = null,
                header = InnertubeParser.BrowseHeader(album.name, subtitle, c.cover(album.coverArt, HEADER_PX)),
            )
        }
        SubsonicIds.playlistId(browseId)?.let { id ->
            val (playlist, songs) = c.playlist(id)
            return@call SongPage(
                songs = songs.mapIndexed { i, s -> c.toSong(s).copy(setVideoId = SubsonicIds.entry(i)) },
                continuation = null,
                owned = playlist.owner == null || playlist.owner == c.credentials.username,
                header = InnertubeParser.BrowseHeader(
                    playlist.name,
                    listOfNotNull("Playlist", playlist.owner, "${playlist.songCount} songs").joinToString(" • "),
                    c.cover(playlist.coverArt, HEADER_PX),
                ),
                description = playlist.comment,
            )
        }
        SubsonicIds.genreName(browseId)?.let { genre ->
            return@call SongPage(c.songsByGenre(genre, 200).map { c.toSong(it) }, null)
        }
        if (browseId == SubsonicIds.STARRED_SONGS) {
            return@call SongPage(c.starred().songs.map { c.toSong(it) }, null)
        }
        SubsonicIds.listType(browseId)?.let { type ->
            // An album list opened as a page: play every album in it, in order.
            val albums = c.albumList(type, 30)
            val songs = coroutineScope {
                albums.map { a -> async { runCatching { c.album(a.id).second }.getOrDefault(emptyList()) } }.awaitAll()
            }.flatten()
            return@call SongPage(songs.map { c.toSong(it) }, null)
        }
        throw IllegalArgumentException("Not a Subsonic page: $browseId")
    }

    override suspend fun allSongs(browseId: String): Result<List<Song>> = browseSongs(browseId).map { it.songs }

    override suspend fun playlistEntries(browseId: String): Result<List<Song>> = allSongs(browseId)

    override suspend fun playlistAsAudio(playlistId: String): Result<List<Song>> = allSongs(playlistId)

    override suspend fun playlistOwned(browseId: String): Result<Boolean?> = call { c ->
        val id = SubsonicIds.playlistId(browseId) ?: return@call null
        val owner = c.playlist(id).first.owner
        owner == null || owner == c.credentials.username
    }

    override suspend fun artistPage(browseId: String): Result<ArtistPage> = call { c ->
        val id = SubsonicIds.artistId(browseId) ?: throw IllegalArgumentException("Not a Subsonic artist: $browseId")
        coroutineScope {
            val artistAsync = async { c.artist(id) }
            val infoAsync = async { runCatching { c.artistInfo(id) }.getOrNull() }
            val (artist, albums) = artistAsync.await()
            val top = runCatching { c.topSongs(artist.name, 20) }.getOrDefault(emptyList())
            // No Last.fm on the server means no top songs: fall back to the
            // newest album's tracks so the page still has something to play.
            val songs = top.ifEmpty {
                albums.maxByOrNull { it.year ?: 0 }?.let { runCatching { c.album(it.id).second }.getOrNull() }.orEmpty()
            }
            val info = infoAsync.await()
            val (singles, full) = albums.partition { a -> releaseKind(a) != "Album" }
            ArtistPage(
                songs = songs.map { c.toSong(it) },
                moreSongsBrowseId = null,
                sections = listOfNotNull(
                    full.sortedByDescending { it.year ?: 0 }.takeIf { it.isNotEmpty() }
                        ?.let { HomeShelf("Albums", it.map { a -> c.albumItem(a) }) },
                    singles.sortedByDescending { it.year ?: 0 }.takeIf { it.isNotEmpty() }
                        ?.let { HomeShelf("Singles & EPs", it.map { a -> c.albumItem(a) }) },
                    info?.similar?.takeIf { it.isNotEmpty() }
                        ?.let { HomeShelf("Similar artists", it.map { a -> c.artistItem(a) }) },
                ),
                thumbnailUrl = info?.largeImageUrl ?: artist.imageUrl ?: c.cover(artist.coverArt, HEADER_PX),
                name = artist.name,
                description = info?.biography?.let(::stripHtml)?.ifBlank { null },
            )
        }
    }

    override suspend fun artistLanding(browseId: String): Result<ArtistPage> = artistPage(browseId)

    override suspend fun findArtistPageId(name: String): Result<String?> = call { c ->
        val n = name.trim()
        if (n.isEmpty()) return@call null
        c.search(n, songCount = 0, albumCount = 0, artistCount = 10).artists
            .firstOrNull { it.name.equals(n, ignoreCase = true) }
            ?.let { SubsonicIds.artist(it.id) }
    }

    override suspend fun trackLinks(videoId: String): Result<Song> = call { c ->
        val id = SubsonicIds.songId(videoId) ?: throw IllegalArgumentException("Not a Subsonic song: $videoId")
        c.toSong(c.song(id))
    }

    // ---- radio ------------------------------------------------------------------

    override suspend fun radio(videoId: String, preferVideo: Boolean): Result<List<Song>> = call { c ->
        val id = SubsonicIds.songId(videoId) ?: return@call emptyList()
        val similar = runCatching { c.similarSongs(id, RADIO) }.getOrDefault(emptyList())
        val songs = similar.ifEmpty {
            // No similar-songs data (no Last.fm key on the server): same
            // artist's top songs, then anything, so a radio always starts.
            val seed = runCatching { c.song(id) }.getOrNull()
            val byArtist = seed?.artist?.takeIf { it.isNotBlank() }
                ?.let { runCatching { c.topSongs(it, RADIO) }.getOrNull() }.orEmpty()
            byArtist.ifEmpty { c.randomSongs(RADIO, genre = seed?.genre) }
        }
        songs.filterNot { it.id == id }.map { c.toSong(it) }
    }

    override suspend fun watchQueue(target: WatchTarget): Result<List<Song>> {
        target.videoId?.takeIf(SubsonicIds::isSubsonic)?.let { return radio(it) }
        return allSongs(target.playlistId)
    }

    // ---- stars ------------------------------------------------------------------

    override suspend fun songMenu(videoId: String): Result<SongMenu> = call { c ->
        val id = SubsonicIds.songId(videoId) ?: throw IllegalArgumentException("Not a Subsonic song: $videoId")
        val song = c.song(id)
        SongMenu(
            likeStatus = if (song.starred) LikeStatus.LIKE else LikeStatus.INDIFFERENT,
            inLibrary = true,
            addToLibraryToken = null,
            removeFromLibraryToken = null,
        )
    }

    override suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = call { c ->
        val id = SubsonicIds.songId(videoId) ?: return@call
        if (status == LikeStatus.LIKE) c.star(songId = id) else c.unstar(songId = id)
    }

    /** "Subscribe" to an artist is starring it. */
    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = call { c ->
        val id = SubsonicIds.artistId(channelId) ?: return@call
        if (subscribed) c.star(artistId = id) else c.unstar(artistId = id)
    }

    // ---- playlists ----------------------------------------------------------------

    override suspend fun userPlaylists(): Result<List<UserPlaylist>> = call { c ->
        c.playlists()
            .filter { it.owner == null || it.owner == c.credentials.username }
            .map {
                UserPlaylist(
                    playlistId = SubsonicIds.playlist(it.id),
                    title = it.name,
                    subtitle = if (it.songCount == 1) "1 song" else "${it.songCount} songs",
                    thumbnailUrl = c.cover(it.coverArt),
                )
            }
    }

    override suspend fun libraryPlaylists(): Result<List<ShelfItem>> = call { c ->
        c.playlists().map { c.playlistItem(it) }
    }

    override suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
    ): Result<String> = call { c ->
        val songIds = videoIds.mapNotNull(SubsonicIds::songId)
        val id = c.createPlaylist(title, songIds)
            // Pre-1.14 servers don't return the new playlist: find it by name.
            ?: c.playlists().lastOrNull { it.name == title }?.id
            ?: error("The server created the playlist but didn't say which it is")
        SubsonicIds.playlist(id)
    }

    /**
     * @return each added song's new row, as the [SubsonicIds.entry] index the
     *   playlist page will give it — Subsonic's stand-in for setVideoId.
     */
    override suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Result<Map<String, String>> =
        call { c ->
            val id = SubsonicIds.playlistId(playlistId) ?: error("Not a Subsonic playlist: $playlistId")
            val songIds = videoIds.mapNotNull(SubsonicIds::songId)
            val before = c.playlist(id).second.size
            c.updatePlaylist(id, addSongIds = songIds)
            videoIds.filter(SubsonicIds::isSubsonic)
                .mapIndexed { i, v -> v to SubsonicIds.entry(before + i) }
                .toMap()
        }

    override suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = call { c ->
        val id = SubsonicIds.playlistId(playlistId) ?: error("Not a Subsonic playlist: $playlistId")
        val indexes = entries.mapNotNull { SubsonicIds.entryIndex(it.second) }
        if (indexes.isNotEmpty()) c.updatePlaylist(id, removeIndexes = indexes.sortedDescending())
    }

    /** [current] and [target] are row ids ([SubsonicIds.entry]) in the old and new order. */
    override suspend fun reorderPlaylist(
        playlistId: String,
        current: List<String>,
        target: List<String>,
    ): Result<Unit> = call { c ->
        val id = SubsonicIds.playlistId(playlistId) ?: error("Not a Subsonic playlist: $playlistId")
        val songs = c.playlist(id).second
        val order = target.map { row ->
            val i = SubsonicIds.entryIndex(row) ?: error("Unknown playlist row $row")
            songs.getOrNull(i)?.id ?: error("Playlist changed on the server; reload it and try again")
        }
        if (order.size != songs.size) error("Playlist changed on the server; reload it and try again")
        c.replacePlaylist(id, order)
    }

    override suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> = call { c ->
        c.updatePlaylist(SubsonicIds.playlistId(playlistId) ?: error("Not a Subsonic playlist"), name = title)
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> = call { c ->
        c.deletePlaylist(SubsonicIds.playlistId(playlistId) ?: error("Not a Subsonic playlist"))
    }

    // ---- plumbing -------------------------------------------------------------------

    private suspend fun <T> call(block: suspend (SubsonicClient) -> T): Result<T> {
        val c = client() ?: return Result.failure(NotSignedIn())
        return try {
            Result.success(block(c))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val COVER_PX = 600
        const val HEADER_PX = 1200
        private const val HOME_SHELF_SIZE = 20
        private const val QUICK_PICKS = 20
        private const val LIBRARY_ALBUMS = 500
        private const val RADIO = 50
        private const val PAGE = 50

        fun formatDuration(seconds: Int): String {
            val s = seconds.coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
            else "%d:%02d".format(s / 60, s % 60)
        }

        /** "FLAC 24/96", "MP3 320" — what the player's quality badge shows. */
        fun qualityLabel(s: SubsonicSong): String? {
            val codec = s.suffix?.uppercase(Locale.ROOT) ?: return null
            val depth = s.bitDepth?.takeIf { it > 0 }
            val rate = s.samplingRate?.takeIf { it > 0 }
            return when {
                s.isLossless && depth != null && rate != null -> {
                    val khz = rate / 1000.0
                    val k = if (khz % 1.0 == 0.0) "${khz.toInt()}" else "%.1f".format(Locale.ROOT, khz)
                    "$codec $depth/$k"
                }
                s.bitRate != null && s.bitRate > 0 -> "$codec ${s.bitRate}"
                else -> codec
            }
        }

        /**
         * "Album", "Single" or "EP" — the only kinds the page header reads as a
         * kind. The OpenSubsonic `releaseTypes` come straight from the files'
         * release-type tags, which are free text ("album", "a", "Album;Live"…);
         * anything else would be shown as if it were an artist.
         */
        fun releaseKind(album: SubsonicAlbum): String {
            val types = album.releaseTypes.flatMap { it.split(';', '/', ',') }
                .map { it.trim().lowercase(Locale.ROOT) }
            return when {
                "single" in types -> "Single"
                "ep" in types -> "EP"
                else -> "Album"
            }
        }

        fun stripHtml(s: String): String =
            s.replace(Regex("<a [^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .trim()
    }
}
