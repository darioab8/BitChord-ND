package com.music.bitchord.auth

import android.content.Context
import com.music.bitchord.data.Catalog
import com.music.bitchord.data.subsonic.SubsonicClient
import com.music.bitchord.data.subsonic.SubsonicCredentials
import com.music.bitchord.data.subsonic.SubsonicException
import com.music.bitchord.data.subsonic.SubsonicRepository
import com.music.bitchord.data.subsonic.SubsonicServerInfo
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * BitChord ND: the signed-in Subsonic server, process-wide.
 *
 * The app and [PlaybackService][com.music.bitchord.playback.PlaybackService]
 * share one process, so the one [client] here is what both the screens (through
 * [Catalog.current]) and the stream resolver use.
 */
object SubsonicSession {

    @Volatile
    var client: SubsonicClient? = null
        private set

    /** Called once from Application.onCreate, before anything asks the catalogue. */
    fun init(context: Context) {
        client = SubsonicStore.get(context).credentials.value?.let(::buildClient)
        Catalog.current = SubsonicRepository { client }
    }

    /**
     * Checks the server answers and accepts the login, then keeps it.
     *
     * @return the server's details, or a failure whose message is fit to show
     *   (see [describe]).
     */
    suspend fun signIn(context: Context, url: String, username: String, password: String): Result<SubsonicServerInfo> {
        val creds = SubsonicCredentials(SubsonicCredentials.normalizeUrl(url), username.trim(), password)
        if (!creds.isComplete) return Result.failure(IllegalArgumentException("Enter the server address and username"))
        val candidate = buildClient(creds) ?: return Result.failure(IllegalArgumentException("That isn't a server address"))
        return try {
            val info = candidate.ping()
            SubsonicStore.get(context).save(creds)
            client = candidate
            SignedIn.state(context).value = true
            Result.success(info)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(describe(e, creds.baseUrl), e))
        }
    }

    fun signOut(context: Context) {
        SubsonicStore.get(context).clear()
        client = null
        SignedIn.state(context).value = false
    }

    private fun buildClient(creds: SubsonicCredentials): SubsonicClient? =
        runCatching { SubsonicClient(creds) }.getOrNull()

    /** Why connecting failed, in words someone setting up a server can act on. */
    fun describe(e: Throwable, url: String): String = when {
        e is SubsonicException && e.isAuthError -> "Wrong username or password"
        e is SubsonicException && e.code < 0 && e.message.startsWith("Not a Subsonic") ->
            "That address answered, but it isn't a Subsonic or Navidrome server. Check the address and port."
        e is SubsonicException && e.code == -404 -> "Nothing found at that address. Check the port and path."
        e is SubsonicException -> e.message
        e is UnknownHostException -> "Couldn't find that server. Check the address."
        e is SSLException && url.startsWith("https://") ->
            "Secure connection failed. If your server doesn't use HTTPS, start the address with http://"
        e is ConnectException -> "The server didn't answer. Is it running, and are you on the same network (or VPN)?"
        e is SocketTimeoutException -> "The server took too long to answer."
        e is IOException && e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
            "This build blocks plain http. Use https:// or a dev build."
        else -> e.message ?: "Couldn't connect"
    }
}
