package com.music.bitchord.data.subsonic

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * BitChord ND: a Subsonic / OpenSubsonic API client (Navidrome, Gonic, Airsonic…).
 *
 * Stateless apart from [credentials]: every request carries its own token
 * (`t = md5(password + salt)`, a fresh salt each time), so nothing has to be
 * refreshed and a URL handed to the player keeps working on its own.
 *
 * Every call runs on IO and throws [SubsonicException] for an error the server
 * stated and [java.io.IOException] for one it couldn't state (no route, TLS,
 * timeout). Callers in the repository turn both into a failed [Result].
 */
class SubsonicClient(
    val credentials: SubsonicCredentials,
    private val http: OkHttpClient = Http.client,
    private val salt: () -> String = ::randomSalt,
) {

    private val base: HttpUrl = credentials.baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a server address: ${credentials.baseUrl}")

    // ---- system -------------------------------------------------------------

    /** Reachability and credentials in one round trip. */
    suspend fun ping(): SubsonicServerInfo {
        val r = call("ping")
        return SubsonicServerInfo(
            apiVersion = r.str("version"),
            type = r.str("type"),
            serverVersion = r.str("serverVersion"),
            openSubsonic = r.bool("openSubsonic") == true,
        )
    }

    // ---- browsing -----------------------------------------------------------

    /** `type`: newest, recent, frequent, random, starred, alphabeticalByName, byGenre… */
    suspend fun albumList(
        type: String,
        size: Int = 20,
        offset: Int = 0,
        genre: String? = null,
    ): List<SubsonicAlbum> {
        val params = mutableListOf("type" to type, "size" to "$size", "offset" to "$offset")
        if (genre != null) params += "genre" to genre
        return call("getAlbumList2", *params.toTypedArray())
            .obj("albumList2").arr("album").map(::parseAlbum)
    }

    suspend fun album(id: String): Pair<SubsonicAlbum, List<SubsonicSong>> {
        val a = call("getAlbum", "id" to id).obj("album")
        return parseAlbum(a) to a.arr("song").map(::parseSong)
    }

    suspend fun song(id: String): SubsonicSong = parseSong(call("getSong", "id" to id).obj("song"))

    suspend fun artists(): List<SubsonicArtist> =
        call("getArtists").obj("artists").arr("index")
            .flatMap { it.jsonObject.arr("artist") }
            .map(::parseArtist)

    suspend fun artist(id: String): Pair<SubsonicArtist, List<SubsonicAlbum>> {
        val a = call("getArtist", "id" to id).obj("artist")
        return parseArtist(a) to a.arr("album").map(::parseAlbum)
    }

    suspend fun artistInfo(id: String, similarCount: Int = 10): SubsonicArtistInfo {
        val i = call("getArtistInfo2", "id" to id, "count" to "$similarCount").obj("artistInfo2")
        return SubsonicArtistInfo(
            biography = i.str("biography"),
            largeImageUrl = i.str("largeImageUrl") ?: i.str("mediumImageUrl"),
            similar = i.arr("similarArtist").map(::parseArtist),
        )
    }

    suspend fun topSongs(artistName: String, count: Int = 20): List<SubsonicSong> =
        call("getTopSongs", "artist" to artistName, "count" to "$count")
            .obj("topSongs").arr("song").map(::parseSong)

    suspend fun similarSongs(id: String, count: Int = 50): List<SubsonicSong> =
        call("getSimilarSongs2", "id" to id, "count" to "$count")
            .obj("similarSongs2").arr("song").map(::parseSong)

    suspend fun randomSongs(size: Int = 50, genre: String? = null): List<SubsonicSong> {
        val params = mutableListOf("size" to "$size")
        if (genre != null) params += "genre" to genre
        return call("getRandomSongs", *params.toTypedArray())
            .obj("randomSongs").arr("song").map(::parseSong)
    }

    suspend fun genres(): List<SubsonicGenre> =
        call("getGenres").obj("genres").arr("genre").map { g ->
            val o = g.jsonObject
            SubsonicGenre(
                name = o.str("value") ?: o.str("name").orEmpty(),
                songCount = o.int("songCount") ?: 0,
                albumCount = o.int("albumCount") ?: 0,
            )
        }.filter { it.name.isNotBlank() }

    suspend fun songsByGenre(genre: String, count: Int = 50, offset: Int = 0): List<SubsonicSong> =
        call("getSongsByGenre", "genre" to genre, "count" to "$count", "offset" to "$offset")
            .obj("songsByGenre").arr("song").map(::parseSong)

    // ---- search -------------------------------------------------------------

    suspend fun search(
        query: String,
        songCount: Int = 20,
        albumCount: Int = 20,
        artistCount: Int = 20,
        songOffset: Int = 0,
        albumOffset: Int = 0,
        artistOffset: Int = 0,
    ): SubsonicSearchResult {
        val r = call(
            "search3",
            "query" to query,
            "songCount" to "$songCount", "songOffset" to "$songOffset",
            "albumCount" to "$albumCount", "albumOffset" to "$albumOffset",
            "artistCount" to "$artistCount", "artistOffset" to "$artistOffset",
        ).obj("searchResult3")
        return SubsonicSearchResult(
            artists = r.arr("artist").map(::parseArtist),
            albums = r.arr("album").map(::parseAlbum),
            songs = r.arr("song").map(::parseSong),
        )
    }

    // ---- stars --------------------------------------------------------------

    suspend fun starred(): SubsonicSearchResult {
        val r = call("getStarred2").obj("starred2")
        return SubsonicSearchResult(
            artists = r.arr("artist").map(::parseArtist),
            albums = r.arr("album").map(::parseAlbum),
            songs = r.arr("song").map(::parseSong),
        )
    }

    suspend fun star(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        call("star", *starParams(songId, albumId, artistId))
    }

    suspend fun unstar(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        call("unstar", *starParams(songId, albumId, artistId))
    }

    private fun starParams(songId: String?, albumId: String?, artistId: String?) = buildList {
        songId?.let { add("id" to it) }
        albumId?.let { add("albumId" to it) }
        artistId?.let { add("artistId" to it) }
    }.toTypedArray()

    // ---- playlists ----------------------------------------------------------

    suspend fun playlists(): List<SubsonicPlaylist> =
        call("getPlaylists").obj("playlists").arr("playlist").map { parsePlaylist(it.jsonObject) }

    suspend fun playlist(id: String): Pair<SubsonicPlaylist, List<SubsonicSong>> {
        val p = call("getPlaylist", "id" to id).obj("playlist")
        return parsePlaylist(p) to p.arr("entry").map(::parseSong)
    }

    /** @return the new playlist's id, when the server says (1.14+ servers do). */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? {
        val params = listOf("name" to name) + songIds.map { "songId" to it }
        return call("createPlaylist", *params.toTypedArray()).objOrNull("playlist")?.str("id")
    }

    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        addSongIds: List<String> = emptyList(),
        removeIndexes: List<Int> = emptyList(),
    ) {
        val params = buildList {
            add("playlistId" to id)
            name?.let { add("name" to it) }
            addSongIds.forEach { add("songIdToAdd" to it) }
            removeIndexes.forEach { add("songIndexToRemove" to "$it") }
        }
        call("updatePlaylist", *params.toTypedArray())
    }

    /** Replaces a playlist's whole contents (order included) in one call. */
    suspend fun replacePlaylist(id: String, songIds: List<String>) {
        val params = listOf("playlistId" to id) + songIds.map { "songId" to it }
        call("createPlaylist", *params.toTypedArray())
    }

    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", "id" to id)
    }

    // ---- playback side ------------------------------------------------------

    /** @param submission false for "now playing", true for a finished play. */
    suspend fun scrobble(id: String, submission: Boolean, timeMillis: Long? = null) {
        val params = mutableListOf("id" to id, "submission" to "$submission")
        if (timeMillis != null) params += "time" to "$timeMillis"
        call("scrobble", *params.toTypedArray())
    }

    /** OpenSubsonic `songLyrics`; empty on servers without the extension. */
    suspend fun lyrics(id: String): List<SubsonicLyrics> = runCatching {
        call("getLyricsBySongId", "id" to id).obj("lyricsList").arr("structuredLyrics").map { l ->
            val o = l.jsonObject
            SubsonicLyrics(
                synced = o.bool("synced") == true,
                offsetMs = o.long("offset") ?: 0L,
                lang = o.str("lang"),
                lines = o.arr("line").map { line ->
                    val lo = line.jsonObject
                    SubsonicLyricLine(startMs = lo.long("start"), text = lo.str("value").orEmpty())
                },
            )
        }
    }.getOrElse { if (it is SubsonicException) emptyList() else throw it }

    /**
     * A URL the player can open directly — credentials are in the query, so it
     * needs no headers and survives being cached by the media stack.
     *
     * @param maxBitRateKbps null or 0 for the original; otherwise the server
     *   transcodes down to it (when it has a transcoder configured).
     * @param format "raw" to forbid transcoding outright.
     */
    fun streamUrl(id: String, maxBitRateKbps: Int? = null, format: String? = null): String {
        val params = mutableListOf("id" to id)
        if (maxBitRateKbps != null && maxBitRateKbps > 0) params += "maxBitRate" to "$maxBitRateKbps"
        if (format != null) params += "format" to format
        return url("stream", params, stableSalt).toString()
    }

    /** The original file, never transcoded — for downloads. */
    fun downloadUrl(id: String): String = url("download", listOf("id" to id), stableSalt).toString()

    fun coverArtUrl(coverArtId: String, size: Int? = null): String {
        val params = mutableListOf("id" to coverArtId)
        if (size != null) params += "size" to "$size"
        return url("getCoverArt", params, stableSalt).toString()
    }

    // ---- plumbing -----------------------------------------------------------

    /**
     * The salt for URLs handed out to be opened later (covers, streams).
     *
     * Fixed per login rather than fresh per URL: the image and audio caches
     * key on the URL, and a new salt each time would make the same cover a
     * different URL on every screen. Derived, not stored, so it survives a
     * restart without another pref; it reveals nothing the token next to it
     * doesn't already.
     */
    private val stableSalt: String =
        md5Hex("bitchordnd:${credentials.username}:${credentials.password}").take(12)

    internal fun url(method: String, params: List<Pair<String, String>>, fixedSalt: String? = null): HttpUrl {
        val s = fixedSalt ?: salt()
        val b = base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(method)
            .addQueryParameter("u", credentials.username)
            .addQueryParameter("t", md5Hex(credentials.password + s))
            .addQueryParameter("s", s)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** The `subsonic-response` object of a successful call. */
    internal suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(method, params.toList())).get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful && body.isBlank()) {
                    throw SubsonicException(-response.code, "HTTP ${response.code}")
                }
                parseEnvelope(body, httpCode = response.code)
            }
        }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "BitChordND"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val random = SecureRandom()

        fun randomSalt(): String {
            val bytes = ByteArray(8).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Unwraps `{"subsonic-response": {...}}`, throwing on `status: failed`. */
        fun parseEnvelope(body: String, httpCode: Int = 200): JsonObject {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw SubsonicException(-httpCode, "Not a Subsonic server (HTTP $httpCode)")
            val r = root["subsonic-response"] as? JsonObject
                ?: throw SubsonicException(-httpCode, "Not a Subsonic server (HTTP $httpCode)")
            if (r.str("status") != "ok") {
                val e = r.objOrNull("error")
                throw SubsonicException(e?.int("code") ?: 0, e?.str("message") ?: "Request failed")
            }
            return r
        }

        fun parseSong(e: JsonElement): SubsonicSong {
            val o = e.jsonObject
            return SubsonicSong(
                id = o.str("id").orEmpty(),
                title = o.str("title") ?: o.str("name").orEmpty(),
                artist = o.str("artist").orEmpty(),
                artistId = o.str("artistId"),
                artists = o.arr("artists").map { a ->
                    val ao = a.jsonObject
                    SubsonicArtistRef(id = ao.str("id"), name = ao.str("name").orEmpty())
                }.filter { it.name.isNotBlank() },
                album = o.str("album"),
                albumId = o.str("albumId"),
                coverArt = o.str("coverArt"),
                durationSec = o.int("duration"),
                track = o.int("track"),
                discNumber = o.int("discNumber"),
                year = o.int("year"),
                genre = o.str("genre"),
                suffix = o.str("suffix"),
                contentType = o.str("contentType"),
                bitRate = o.int("bitRate"),
                bitDepth = o.int("bitDepth"),
                samplingRate = o.int("samplingRate"),
                channelCount = o.int("channelCount"),
                sizeBytes = o.long("size"),
                starred = o.str("starred") != null,
                playCount = o.int("playCount"),
                isVideo = o.bool("isVideo") == true,
            )
        }

        fun parseAlbum(e: JsonElement): SubsonicAlbum {
            val o = e.jsonObject
            return SubsonicAlbum(
                id = o.str("id").orEmpty(),
                name = o.str("name") ?: o.str("title") ?: o.str("album").orEmpty(),
                artist = o.str("artist").orEmpty(),
                artistId = o.str("artistId"),
                coverArt = o.str("coverArt"),
                songCount = o.int("songCount"),
                durationSec = o.int("duration"),
                year = o.int("year"),
                genre = o.str("genre"),
                starred = o.str("starred") != null,
                releaseTypes = o.arr("releaseTypes").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            )
        }

        fun parseArtist(e: JsonElement): SubsonicArtist {
            val o = e.jsonObject
            return SubsonicArtist(
                id = o.str("id").orEmpty(),
                name = o.str("name").orEmpty(),
                coverArt = o.str("coverArt"),
                imageUrl = o.str("artistImageUrl"),
                albumCount = o.int("albumCount"),
                starred = o.str("starred") != null,
            )
        }

        fun parsePlaylist(o: JsonObject) = SubsonicPlaylist(
            id = o.str("id").orEmpty(),
            name = o.str("name").orEmpty(),
            owner = o.str("owner"),
            comment = o.str("comment"),
            songCount = o.int("songCount") ?: 0,
            durationSec = o.int("duration"),
            coverArt = o.str("coverArt"),
            public = o.bool("public") == true,
        )
    }
}

// ---- JSON helpers: lenient, because servers disagree on shapes ---------------
// (a single child is sometimes an object rather than a one-element array, and
// numbers are sometimes strings).

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull() }

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** An absent child reads as empty: servers leave out empty lists entirely. */
internal fun JsonObject.obj(key: String): JsonObject = objOrNull(key) ?: JsonObject(emptyMap())

internal fun JsonObject.arr(key: String): List<JsonElement> = when (val v = this[key]) {
    is JsonArray -> v
    is JsonObject -> listOf(v)
    else -> emptyList()
}
