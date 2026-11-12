package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.subsonic.SubsonicCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord ND: the Subsonic/Navidrome login, kept in its own encrypted prefs
 * file so signing out of a server never touches anything else.
 *
 * One server for now. [credentials] is a flow so the catalogue switch and the
 * login screen both follow a sign-in or sign-out without being told.
 */
class SubsonicStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "bitchordnd_subsonic", "bitchordnd_subsonic_plain")

    private val state = MutableStateFlow(read())

    /** The saved login, or null when none is saved. */
    val credentials: StateFlow<SubsonicCredentials?> = state.asStateFlow()

    fun save(credentials: SubsonicCredentials) {
        prefs.edit()
            .putString(KEY_URL, credentials.baseUrl)
            .putString(KEY_USER, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
        state.value = credentials
    }

    fun clear() {
        prefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_PASSWORD).apply()
        state.value = null
    }

    private fun read(): SubsonicCredentials? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        return SubsonicCredentials(url, user, prefs.getString(KEY_PASSWORD, null).orEmpty())
            .takeIf { it.isComplete }
    }

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_USER = "username"
        private const val KEY_PASSWORD = "password"

        @Volatile private var instance: SubsonicStore? = null

        fun get(context: Context): SubsonicStore =
            instance ?: synchronized(this) {
                instance ?: SubsonicStore(context.applicationContext).also { instance = it }
            }
    }
}
