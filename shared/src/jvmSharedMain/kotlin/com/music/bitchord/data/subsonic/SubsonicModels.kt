package com.music.bitchord.data.subsonic

import java.util.Locale

/** BitChord ND: where a Subsonic server is and who to log in as. */
data class SubsonicCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && username.isNotBlank()

    companion object {
        /**
         * A typed address made usable: scheme added, trailing slash and a
         * pasted `/rest` or `/app` path dropped.
         *
         * A bare private, loopback or Tailscale IPv4 gets `http://` because
         * that is how Navidrome listens out of the box (port 4533, no TLS);
         * anything else gets `https://`. A typed scheme is always kept.
         */
        fun normalizeUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            for (suffix in listOf("/rest", "/app", "/app/#")) {
                if (s.lowercase(Locale.ROOT).endsWith(suffix)) s = s.dropLast(suffix.length).trimEnd('/')
            }
            val lower = s.lowercase(Locale.ROOT)
            if (lower.startsWith("http://") || lower.startsWith("https://")) return s
            val host = s.substringBefore('/').substringBefore(':')
            return if (isPrivateIpv4(host) || host.equals("localhost", ignoreCase = true)) "http://$s" else "https://$s"
        }

        internal fun isPrivateIpv4(host: String): Boolean {
            val p = host.split('.').map { it.toIntOrNull() ?: return false }
            if (p.size != 4 || p.any { it !in 0..255 }) return false
            return p[0] == 10 ||
                p[0] == 127 ||
                (p[0] == 192 && p[1] == 168) ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 100 && p[1] in 64..127) // Tailscale / CGNAT
        }
    }
}

class SubsonicException(val code: Int, override val message: String) : Exception(message) {
    /** 40/41: wrong username or password; 44/45 token/LDAP auth refusals. */
    val isAuthError: Boolean get() = code in 40..45
}

data class SubsonicServerInfo(
    val apiVersion: String?,
    val type: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean,
)

data class SubsonicArtistRef(val id: String?, val name: String)

data class SubsonicSong(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    /** OpenSubsonic: every credited artist; empty on plain Subsonic servers. */
    val artists: List<SubsonicArtistRef> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val durationSec: Int? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null,
    val channelCount: Int? = null,
    val sizeBytes: Long? = null,
    val starred: Boolean = false,
    val playCount: Int? = null,
    val isVideo: Boolean = false,
) {
    /** FLAC/ALAC/WAV/AIFF: worth asking for untranscoded when lossless is wanted. */
    val isLossless: Boolean
        get() = suffix?.lowercase(Locale.ROOT) in setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
}

data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val durationSec: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: Boolean = false,
    /** OpenSubsonic: "Album", "EP", "Single", "Compilation"… */
    val releaseTypes: List<String> = emptyList(),
)

data class SubsonicArtist(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val imageUrl: String? = null,
    val albumCount: Int? = null,
    val starred: Boolean = false,
)

data class SubsonicArtistInfo(
    val biography: String?,
    val largeImageUrl: String?,
    val similar: List<SubsonicArtist>,
)

data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val owner: String? = null,
    val comment: String? = null,
    val songCount: Int = 0,
    val durationSec: Int? = null,
    val coverArt: String? = null,
    val public: Boolean = false,
)

data class SubsonicGenre(val name: String, val songCount: Int, val albumCount: Int)

data class SubsonicSearchResult(
    val artists: List<SubsonicArtist>,
    val albums: List<SubsonicAlbum>,
    val songs: List<SubsonicSong>,
)

data class SubsonicLyricLine(val startMs: Long?, val text: String)

data class SubsonicLyrics(
    val synced: Boolean,
    val offsetMs: Long,
    val lang: String?,
    val lines: List<SubsonicLyricLine>,
)
