package com.music.bitchord.data.companion

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

/**
 * BitChord ND: the companion service that downloads YouTube Music albums into
 * the user's Navidrome library (see `companion/` in this repo).
 *
 * The app only ever queues albums and reads job states; yt-dlp, beets and the
 * rescan all happen on the server.
 */
data class CompanionConfig(
    val baseUrl: String,
    val token: String,
    /** Folder under the music library; blank means the companion's default. */
    val folder: String = "",
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    companion object {
        /** `nas:8765` → `http://nas:8765`; a typed scheme is kept, trailing slashes dropped. */
        fun normalizeUrl(raw: String): String {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            val lower = s.lowercase(Locale.ROOT)
            return if (lower.startsWith("http://") || lower.startsWith("https://")) s else "http://$s"
        }
    }
}

class CompanionException(val status: Int, override val message: String) : Exception(message) {
    val isAuthError: Boolean get() = status == 401
}

@Serializable
data class CompanionHealth(
    val ok: Boolean = false,
    val version: String? = null,
    @SerialName("ytDlp") val ytDlp: String? = null,
    val beets: String? = null,
    val premiumCookies: Boolean = false,
    /** "host": the server's own beets imports from its incoming folder; "beets": the companion's. */
    val importMode: String = "beets",
    val defaultFolder: String = "",
    val navidromeRescan: Boolean = false,
) {
    /** Host mode: the user's beets decides where albums go, so there's no folder to pick. */
    val importsWithHostBeets: Boolean get() = importMode == "host"
}

@Serializable
data class CompanionJob(
    val id: String,
    val state: String,
    val title: String = "",
    val artist: String = "",
    val folder: String = "",
    val progress: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val finished: Boolean = false,
) {
    val succeeded: Boolean get() = state == DONE || state == SKIPPED
    val failed: Boolean get() = state == FAILED

    companion object {
        const val DONE = "done"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"
    }
}

@Serializable
data class AlbumDownload(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val coverUrl: String? = null,
    val browseId: String? = null,
    val folder: String? = null,
    val tracks: List<AlbumTrack>,
)

@Serializable
data class AlbumTrack(val videoId: String, val title: String, val artist: String)

@Serializable
private data class JobList(val jobs: List<CompanionJob> = emptyList())

@Serializable
private data class ErrorBody(val error: String? = null)

class CompanionClient(
    val config: CompanionConfig,
    private val http: OkHttpClient = Http.client,
) {
    private val base: HttpUrl = config.baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Not a server address: ${config.baseUrl}")

    suspend fun health(): CompanionHealth = decode(call("GET", "api/health"))

    suspend fun queue(album: AlbumDownload): CompanionJob = decode(call("POST", "api/jobs", json.encodeToString(album)))

    suspend fun job(id: String): CompanionJob = decode(call("GET", "api/jobs/$id"))

    suspend fun jobs(): List<CompanionJob> = decode<JobList>(call("GET", "api/jobs")).jobs

    suspend fun cancel(id: String): CompanionJob = decode(call("POST", "api/jobs/$id/cancel", "{}"))

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .method(method, body?.toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { json.decodeFromString<ErrorBody>(text).error }.getOrNull()
                throw CompanionException(
                    response.code,
                    reason ?: if (response.code == 401) "Wrong token" else "HTTP ${response.code}",
                )
            }
            text
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        /**
         * "Album • Artist • 2019" (or "Artist • 2019") into the artist and the
         * year. The kind word goes, a four-digit part is the year, and the
         * first part left is the artist.
         */
        fun artistAndYear(subtitle: String): Pair<String, Int?> {
            val kinds = setOf("album", "ep", "single", "playlist", "compilation", "live album", "mixtape")
            val parts = subtitle.split('•', '·').map { it.trim() }.filter { it.isNotEmpty() }
            val year = parts.firstOrNull { it.length == 4 && it.all(Char::isDigit) }?.toIntOrNull()
            val artist = parts.firstOrNull { it.lowercase(Locale.ROOT) !in kinds && it.toIntOrNull() == null }.orEmpty()
            return artist to year
        }
    }
}
