package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.companion.ServerDownloads
import com.music.bitchord.data.companion.CompanionHealth
import com.music.bitchord.data.companion.CompanionJob
import com.music.bitchord.ui.icons.SfIcons
import kotlinx.coroutines.delay

/**
 * BitChord ND: Settings → Download to server.
 *
 * Where the companion is, which folder albums go to, whether it's reachable,
 * and what it's working on. The editors are raised by the activity (see
 * SourcesScreen for why a frosted alert can't be drawn from in here).
 */
@Composable
fun ServerDownloadsScreen(
    contentPadding: PaddingValues,
    onEditCompanion: () -> Unit,
    onEditFolder: () -> Unit,
    onOpenJob: (CompanionJob) -> Unit,
    modifier: Modifier = Modifier,
) {
    val config by ServerDownloads.config.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf<CompanionHealth?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var jobs by remember { mutableStateOf<List<CompanionJob>>(emptyList()) }

    // Reachability and the queue, refreshed while the page is open.
    LaunchedEffect(config) {
        health = null
        problem = null
        jobs = emptyList()
        val current = config ?: return@LaunchedEffect
        while (true) {
            ServerDownloads.test(current).fold(
                onSuccess = { health = it; problem = null },
                onFailure = { health = null; problem = it.message },
            )
            if (health != null) ServerDownloads.allJobs().onSuccess { jobs = it }
            delay(if (jobs.any { !it.finished }) 3_000 else 15_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle("Download to server")

        SettingsGroup(
            footer = "YouTube Music albums get a Download to server action. The companion on your " +
                "server downloads them, tags them with beets and adds them to your Navidrome library. " +
                "Setup: companion/README.md in the BitChord-ND repository.",
        ) {
            SettingsRow(
                icon = SfIcons.Storage,
                title = "Companion",
                value = config?.baseUrl?.substringAfter("://") ?: "Not set up",
                onClick = onEditCompanion,
            )
            // With the server's own beets importing, beets' paths decide where albums go.
            if (health?.importsWithHostBeets != true) {
                RowDivider()
                SettingsRow(
                    icon = SfIcons.Folder,
                    title = "Folder in your library",
                    value = config?.folder?.ifBlank { null }
                        ?: health?.defaultFolder?.ifBlank { null }?.let { "$it (default)" }
                        ?: "Default",
                    enabled = config != null,
                    onClick = onEditFolder,
                )
            }
        }

        if (config != null) {
            SettingsGroup(header = "Status") {
                val h = health
                when {
                    h != null -> {
                        InfoRow(title = "Connected", value = "v${h.version.orEmpty()}")
                        RowDivider()
                        InfoRow(
                            title = "Import",
                            value = if (h.importsWithHostBeets) "Your beets on the server" else "beets in the companion",
                        )
                        RowDivider()
                        InfoRow(
                            title = "Audio",
                            value = if (h.premiumCookies) "Premium (AAC up to 256 kbps)" else "Opus (~130–160 kbps)",
                        )
                        RowDivider()
                        InfoRow(title = "Navidrome rescan", value = if (h.navidromeRescan) "Right away" else "On its own schedule")
                    }
                    problem != null -> InfoRow(title = "Not reachable", subtitle = problem)
                    else -> InfoRow(title = "Checking…")
                }
            }

            if (jobs.isNotEmpty()) {
                SettingsGroup(header = "Recent downloads") {
                    jobs.take(20).forEachIndexed { index, job ->
                        if (index > 0) RowDivider()
                        SettingsRow(
                            title = job.title,
                            subtitle = listOfNotNull(
                                job.artist.ifBlank { null },
                                job.summary.ifBlank { null },
                            ).joinToString(" · "),
                            value = jobLabel(job),
                            onClick = { onOpenJob(job) },
                        )
                    }
                }
            }

            SettingsGroup {
                DestructiveRow(label = "Remove companion") { ServerDownloads.clear() }
            }
        }
    }
}

private fun jobLabel(job: CompanionJob): String = when (job.state) {
    "queued" -> "Waiting"
    "downloading" -> if (job.total > 0) "${job.progress}/${job.total}" else "Downloading"
    "tagging" -> "Tagging"
    "importing" -> "Importing"
    "scanning" -> "Scanning"
    CompanionJob.DONE -> "Done"
    CompanionJob.SKIPPED -> "Already there"
    CompanionJob.FAILED -> "Failed"
    CompanionJob.CANCELLED -> "Cancelled"
    else -> job.state
}

/**
 * A row that only shows something. [SettingsRow] puts a chevron after any
 * value, which reads as "tap me"; these rows don't open anything.
 */
@Composable
internal fun InfoRow(title: String, value: String? = null, subtitle: String? = null) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        trailing = value?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
    )
}
