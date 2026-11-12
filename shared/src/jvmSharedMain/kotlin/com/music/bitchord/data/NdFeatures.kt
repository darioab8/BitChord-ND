package com.music.bitchord.data

/**
 * BitChord ND: which of upstream's features this build shows.
 *
 * YouTube Music features are hidden, not deleted: the code stays in the tree
 * so they can come back as an option later. Every place that hides one checks
 * [YOUTUBE], so `grep NdFeatures.YOUTUBE` lists them all.
 *
 * Hidden while [YOUTUBE] is false:
 * - Listen Together (needs upstream's party server, and shares YouTube ids)
 * - The YouTube channel/profile switcher, and Spotify playlist import
 * - Sources' addon ranking and matching (they find copies of YouTube tracks)
 * - Music-video features: convert to video/audio, video thumbnails, Home video filter
 * - Top Picks and the mood row on Home (built from YouTube)
 * - Dislike (Subsonic has stars only)
 * - YouTube share links, and upstream's update checker (it would offer upstream's APK)
 */
object NdFeatures {
    const val YOUTUBE = false

    /**
     * Search adds YouTube Music's results after the server's, and they play.
     * Independent of [YOUTUBE]: the rest of YouTube's UI stays hidden.
     * See [CompositeCatalog].
     */
    const val YOUTUBE_IN_SEARCH = true
}
