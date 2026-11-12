package com.music.bitchord.data.scrobbling

import com.music.bitchord.auth.SubsonicSession
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.subsonic.SubsonicIds

/**
 * BitChord ND: plays reported to the Subsonic server itself, so its "recently
 * played", "most played" and play counts follow what is heard in this app —
 * the same rules as Last.fm (half the track or four minutes, whichever first).
 *
 * Songs that aren't the server's are skipped silently.
 */
object NavidromeScrobbleSink : ScrobbleSink {
    override suspend fun scrobble(song: Song, artist: String, durationSeconds: Int, startedAtSeconds: Long) =
        report(song) { client, id -> client.scrobble(id, submission = true, timeMillis = startedAtSeconds * 1000) }

    override suspend fun nowPlaying(song: Song, artist: String, durationSeconds: Int?) =
        report(song) { client, id -> client.scrobble(id, submission = false) }

    private suspend fun report(
        song: Song,
        block: suspend (com.music.bitchord.data.subsonic.SubsonicClient, String) -> Unit,
    ): Result<Unit> {
        val id = SubsonicIds.songId(song.videoId) ?: return Result.success(Unit)
        val client = SubsonicSession.client ?: return Result.success(Unit)
        return runCatching { block(client, id) }
    }
}
