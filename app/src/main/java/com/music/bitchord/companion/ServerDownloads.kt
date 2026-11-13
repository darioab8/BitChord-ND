package com.music.bitchord.companion

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.companion.AlbumDownload
import com.music.bitchord.data.companion.AlbumTrack
import com.music.bitchord.data.companion.CompanionClient
import com.music.bitchord.data.companion.CompanionConfig
import com.music.bitchord.data.companion.CompanionException
import com.music.bitchord.data.companion.CompanionJob
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.subsonic.SubsonicIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * BitChord ND: "Download to server" — YouTube Music albums sent to the
 * companion service, which puts them in the Navidrome library.
 *
 * App-wide rather than per screen: a download takes minutes, and the toast
 * saying it's done should arrive wherever the listener is by then. Jobs the app
 * started are followed until they finish; the settings page shows them all.
 */
object ServerDownloads {

    private const val POLL_MS = 4_000L
    private const val GIVE_UP_MS = 3 * 60 * 60 * 1000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _config = MutableStateFlow<CompanionConfig?>(null)
    /** The companion's address, token and folder, or null when not set up. */
    val config: StateFlow<CompanionConfig?> = _config.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, CompanionJob>>(emptyMap())
    /** Jobs this app started, by id, as last seen. */
    val jobs: StateFlow<Map<String, CompanionJob>> = _jobs.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = EncryptedPrefs.open(app, "bitchordnd_companion", "bitchordnd_companion_plain")
        _config.value = read()
    }

    // ---- settings ----------------------------------------------------------------

    fun save(config: CompanionConfig) {
        prefs.edit()
            .putString("url", config.baseUrl)
            .putString("token", config.token)
            .putString("folder", config.folder)
            .apply()
        _config.value = config.takeIf { it.isComplete }
    }

    fun clear() {
        prefs.edit().clear().apply()
        _config.value = null
    }

    private fun read(): CompanionConfig? {
        val url = prefs.getString("url", null) ?: return null
        return CompanionConfig(url, prefs.getString("token", null).orEmpty(), prefs.getString("folder", null).orEmpty())
            .takeIf { it.isComplete }
    }

    private fun client(config: CompanionConfig? = _config.value): CompanionClient? =
        config?.let { runCatching { CompanionClient(it) }.getOrNull() }

    /** Checks a configuration before it's saved: the health answer, or why it failed. */
    suspend fun test(config: CompanionConfig): Result<com.music.bitchord.data.companion.CompanionHealth> {
        val c = client(config) ?: return Result.failure(IllegalArgumentException("That isn't a server address"))
        return runCatching { c.health() }.recoverCatching { throw Exception(describe(it), it) }
    }

    /** Everything the companion knows about, newest first, for the settings page. */
    suspend fun allJobs(): Result<List<CompanionJob>> {
        val c = client() ?: return Result.success(emptyList())
        return runCatching { c.jobs() }.recoverCatching { throw Exception(describe(it), it) }
    }

    // ---- the action ----------------------------------------------------------------

    /** Whether "Download to server" belongs on this release: a YouTube album, with a companion set up. */
    fun canDownload(browseId: String?, isAlbum: Boolean): Boolean =
        isAlbum && _config.value != null && browseId != null &&
            !SubsonicIds.isSubsonic(browseId) && !browseId.startsWith("local:")

    /**
     * Sends a YouTube album to the companion and follows it to the end.
     *
     * @param subtitle the album's card or page subtitle ("Album • Artist • 2019"),
     *   where its artist and year come from.
     */
    fun download(browseId: String, title: String, subtitle: String, thumbnailUrl: String?, songs: List<Song>) {
        val c = client() ?: return toast("Set up Download to server in Settings first")
        val tracks = songs.filter { !SubsonicIds.isSubsonic(it.videoId) && it.localUri == null && it.videoId.length == 11 }
        if (tracks.isEmpty()) return toast("Nothing on this album can be downloaded")
        val (subtitleArtist, year) = CompanionClient.artistAndYear(subtitle)
        val artist = subtitleArtist.ifBlank {
            tracks.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        }
        val request = AlbumDownload(
            title = title,
            artist = artist,
            year = year,
            coverUrl = thumbnailUrl?.artworkAt(1200),
            browseId = browseId,
            folder = c.config.folder.ifBlank { null },
            tracks = tracks.map { AlbumTrack(it.videoId, it.title, it.artist) },
        )
        scope.launch {
            val job = runCatching { c.queue(request) }.getOrElse {
                toast("Couldn't send “$title” to your server: ${describe(it)}")
                return@launch
            }
            remember(job)
            toast("Downloading “$title” to your server")
            follow(c, job)
        }
    }

    /** One download with its tracks, for its page. */
    suspend fun job(id: String): Result<CompanionJob> {
        val c = client() ?: return Result.failure(IllegalStateException("Download to server isn't set up"))
        return runCatching { c.job(id) }.recoverCatching { throw Exception(describe(it), it) }
    }

    /**
     * Runs a failed or cancelled download again, then follows it like a new
     * one (toast when it's done). See [CompanionClient.retry].
     */
    suspend fun retry(id: String, skipFailed: Boolean): Result<CompanionJob> {
        val c = client() ?: return Result.failure(IllegalStateException("Download to server isn't set up"))
        return runCatching { c.retry(id, skipFailed) }
            .recoverCatching { throw Exception(describe(it), it) }
            .onSuccess { job ->
                remember(job)
                scope.launch { follow(c, job) }
            }
    }

    suspend fun cancel(id: String): Result<CompanionJob> {
        val c = client() ?: return Result.failure(IllegalStateException("Download to server isn't set up"))
        return runCatching { c.cancel(id) }.recoverCatching { throw Exception(describe(it), it) }
    }

    suspend fun remove(id: String): Result<Unit> {
        val c = client() ?: return Result.failure(IllegalStateException("Download to server isn't set up"))
        return runCatching { c.remove(id) }
            .recoverCatching { throw Exception(describe(it), it) }
            .onSuccess { _jobs.update { it - id } }
    }

    private suspend fun follow(c: CompanionClient, first: CompanionJob) {
        var job = first
        val started = System.currentTimeMillis()
        var misses = 0
        while (!job.finished && System.currentTimeMillis() - started < GIVE_UP_MS) {
            delay(POLL_MS)
            try {
                job = c.job(job.id)
                misses = 0
                remember(job)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The phone left the network, or the companion restarted: keep
                // trying for a while rather than report a failure that isn't one.
                if (++misses > 30) return
            }
        }
        val detail = job.summary
        when {
            job.state == CompanionJob.DONE && detail.contains("as-is") ->
                toast("“${job.title}” is in your library (as-is: no confident MusicBrainz match)")
            job.state == CompanionJob.DONE -> toast("“${job.title}” is in your library")
            job.state == CompanionJob.SKIPPED -> toast("“${job.title}” wasn't imported: $detail")
            job.failed -> toast("Couldn't download “${job.title}”: $detail")
        }
    }

    private fun remember(job: CompanionJob) = _jobs.update { it + (job.id to job) }

    fun describe(e: Throwable): String = when (e) {
        is CompanionException -> if (e.isAuthError) "Wrong token" else e.message
        is java.net.UnknownHostException -> "Couldn't find that server"
        is java.net.ConnectException -> "The companion didn't answer. Is it running, and is the phone on the same network?"
        is java.net.SocketTimeoutException -> "The companion took too long to answer"
        is IOException -> e.message ?: "Network error"
        is kotlinx.serialization.SerializationException -> "That address answered, but it isn't the companion"
        else -> e.message ?: "Something went wrong"
    }

    private fun toast(text: String) {
        if (!::app.isInitialized) return
        main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}
