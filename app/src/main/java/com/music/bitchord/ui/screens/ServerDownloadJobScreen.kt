package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.music.bitchord.companion.ServerDownloads
import com.music.bitchord.data.companion.CompanionJob
import com.music.bitchord.data.companion.CompanionTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BitChord ND: one download to the server, track by track.
 *
 * Opened from Settings → Download to server → Recent downloads. Shows each
 * track's state (and why a failed one failed), and the actions that fit:
 * retry the failed tracks, import the album without them, cancel, remove.
 */
@Composable
fun ServerDownloadJobScreen(
    jobId: String,
    title: String,
    contentPadding: PaddingValues,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var job by remember { mutableStateOf<CompanionJob?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Bumped after an action, to start following the job again.
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(jobId, generation) {
        while (true) {
            ServerDownloads.job(jobId).fold(
                onSuccess = { job = it; problem = null },
                onFailure = { problem = it.message },
            )
            if (job?.finished == true) break
            delay(2_000)
        }
    }

    fun act(block: suspend () -> Result<*>, after: () -> Unit = { generation++ }) {
        if (busy) return
        busy = true
        scope.launch {
            block().fold(onSuccess = { problem = null; after() }, onFailure = { problem = it.message })
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        val j = job
        SettingsPageTitle(j?.title?.ifBlank { null } ?: title)

        SettingsGroup(footer = problem) {
            when {
                j == null && problem == null -> SettingsRow(title = "Loading…")
                j == null -> SettingsRow(title = "Couldn't load this download")
                else -> {
                    if (j.artist.isNotBlank()) {
                        SettingsRow(title = "Artist", value = j.artist)
                        RowDivider()
                    }
                    SettingsRow(title = "Status", value = stateLabel(j))
                    if (j.summary.isNotBlank()) {
                        RowDivider()
                        SettingsRow(title = j.summary)
                    }
                }
            }
        }

        if (j != null && j.retryable) {
            SettingsGroup(
                footer = if (j.failedTracks > 0) {
                    "The tracks that downloaded are kept. \"Import without\" leaves the failed ones out; " +
                        "beets then imports the album as-is if MusicBrainz expects them."
                } else {
                    null
                },
            ) {
                SettingsRow(
                    title = when {
                        j.failedTracks == 1 -> "Retry the failed track"
                        j.failedTracks > 1 -> "Retry the ${j.failedTracks} failed tracks"
                        else -> "Retry"
                    },
                    enabled = !busy,
                    onClick = { act({ ServerDownloads.retry(jobId, skipFailed = false) }) },
                )
                if (j.failedTracks > 0) {
                    RowDivider()
                    SettingsRow(
                        title = if (j.failedTracks == 1) "Import without it" else "Import without them",
                        enabled = !busy,
                        onClick = { act({ ServerDownloads.retry(jobId, skipFailed = true) }) },
                    )
                }
            }
        }

        if (j != null && j.tracks.isNotEmpty()) {
            SettingsGroup(header = "Tracks") {
                j.tracks.forEachIndexed { index, track ->
                    if (index > 0) RowDivider()
                    SettingsRow(
                        title = "${track.number}. ${track.title?.ifBlank { null } ?: "Track ${track.number}"}",
                        subtitle = if (track.state == "failed") {
                            track.error?.removePrefix("ERROR: ")?.ifBlank { null } ?: "Failed"
                        } else {
                            track.artist?.ifBlank { null }
                        },
                        value = trackLabel(track),
                    )
                }
            }
        }

        if (j != null) {
            SettingsGroup {
                if (!j.finished) {
                    DestructiveRow(label = "Cancel download") {
                        act({ ServerDownloads.cancel(jobId) })
                    }
                } else {
                    DestructiveRow(label = "Remove from the list") {
                        act({ ServerDownloads.remove(jobId) }, after = onRemoved)
                    }
                }
            }
        }
    }
}

private fun stateLabel(job: CompanionJob): String = when (job.state) {
    "queued" -> "Waiting"
    "downloading" -> if (job.total > 0) "Downloading ${job.progress}/${job.total}" else "Downloading"
    "tagging" -> "Tagging"
    "importing" -> "Importing with beets"
    "scanning" -> "Rescanning Navidrome"
    CompanionJob.DONE -> "Done"
    CompanionJob.SKIPPED -> "Not imported"
    CompanionJob.FAILED -> "Failed"
    CompanionJob.CANCELLED -> "Cancelled"
    else -> job.state
}

private fun trackLabel(track: CompanionTrack): String = when (track.state) {
    "waiting" -> "Waiting"
    "downloading" -> "Downloading"
    "done" -> "Done"
    "failed" -> "Failed"
    "skipped" -> "Left out"
    else -> track.state
}
