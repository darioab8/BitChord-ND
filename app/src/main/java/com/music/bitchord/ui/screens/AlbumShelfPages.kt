package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.companion.AlbumShelf
import com.music.bitchord.data.companion.ShelfAlbum
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.shelves.AlbumShelves
import com.music.bitchord.ui.components.PillTextField
import kotlinx.coroutines.launch

/**
 * BitChord ND: a shelf being made or edited. [id] null makes a new one,
 * starting with [firstAlbum] when it was asked for from "Add to shelf".
 */
data class ShelfEdit(
    val id: String?,
    val name: String = "",
    val description: String = "",
    val firstAlbum: ShelfAlbum? = null,
)

/** A shelf's page: reads it, and reads it again whenever any shelf changes. */
@Composable
fun AlbumShelfPageHost(
    shelfId: String,
    contentPadding: PaddingValues,
    onEdit: (AlbumShelf) -> Unit,
    onPlay: (AlbumShelf, shuffled: Boolean) -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
) {
    val live by AlbumShelves.shelves.collectAsStateWithLifecycle()
    var shelf by remember(shelfId) { mutableStateOf<AlbumShelf?>(null) }
    var error by remember(shelfId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    // The list's entry for this shelf changes with every edit (its count, its
    // `updated`): the cue to read the albums again.
    val stamp = live?.firstOrNull { it.id == shelfId }?.let { it.updated to it.count }
    LaunchedEffect(shelfId, stamp, attempt) {
        AlbumShelves.shelf(shelfId)
            .onSuccess {
                shelf = it
                error = null
            }
            .onFailure { if (shelf == null) error = it.message ?: "Couldn't read this shelf" }
    }
    AlbumShelfScreen(
        shelf = shelf,
        error = error,
        gridState = rememberLazyGridState(),
        onRetry = {
            error = null
            attempt++
        },
        onPlay = { shelf?.let { onPlay(it, false) } },
        onShuffle = { shelf?.let { onPlay(it, true) } },
        onEdit = { shelf?.let(onEdit) },
        onItemClick = onItemClick,
        onItemLongPress = onItemLongPress,
        contentPadding = contentPadding,
    )
}

/** Name and description of a new or existing shelf; deleting an existing one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfEditSheet(
    edit: ShelfEdit,
    onDismiss: () -> Unit,
    onDeleted: (shelfId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(edit.name) }
    var description by remember { mutableStateOf(edit.description) }
    var busy by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val fail: (Throwable) -> Unit = {
        busy = false
        Toast.makeText(context, it.message ?: "That didn't work", Toast.LENGTH_SHORT).show()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (edit.id == null) "New shelf" else "Edit shelf",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            PillTextField(value = name, onValueChange = { name = it.take(100) }, placeholder = "Name")
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                    .padding(12.dp),
            ) {
                if (description.isEmpty()) {
                    Text(
                        "Description (optional)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val id = edit.id
                        if (id == null) {
                            AlbumShelves.create(name, description, edit.firstAlbum)
                                .onSuccess { shelf ->
                                    if (edit.firstAlbum != null) {
                                        Toast.makeText(context, "Added to ${shelf.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    onDismiss()
                                }
                                .onFailure(fail)
                        } else {
                            AlbumShelves.edit(id, name = name, description = description)
                                .onSuccess { onDismiss() }
                                .onFailure(fail)
                        }
                    }
                },
                enabled = name.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (edit.id == null) "Create" else "Save")
            }
            val id = edit.id
            if (id != null) {
                TextButton(
                    onClick = {
                        if (!confirmingDelete) {
                            confirmingDelete = true
                            return@TextButton
                        }
                        busy = true
                        scope.launch {
                            AlbumShelves.delete(id)
                                .onSuccess {
                                    onDismiss()
                                    onDeleted(id)
                                }
                                .onFailure(fail)
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (confirmingDelete) "Delete “${edit.name}”? Tap again" else "Delete shelf")
                }
            }
        }
    }
}

/**
 * "Add to shelf": every shelf, ticked where the album already is. A tap puts
 * it on or takes it off at once; the sheet stays up for more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfPickerSheet(
    album: ShelfAlbum,
    onDismiss: () -> Unit,
    onNewShelf: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelves by AlbumShelves.shelves.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { AlbumShelves.refresh() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = "Add “${album.title}” to a shelf",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                item(key = "new") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNewShelf)
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("New shelf", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(shelves.orEmpty(), key = { it.id }) { shelf ->
                    val on = album.id in shelf.albumIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = busy == null) {
                                busy = shelf.id
                                scope.launch {
                                    val result = if (on) {
                                        AlbumShelves.remove(shelf.id, album.id)
                                    } else {
                                        AlbumShelves.add(shelf.id, album)
                                    }
                                    busy = null
                                    Toast.makeText(
                                        context,
                                        result.fold(
                                            onSuccess = { if (on) "Removed from ${shelf.name}" else "Added to ${shelf.name}" },
                                            onFailure = { it.message ?: "That didn't work" },
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = shelf.preview.firstOrNull()?.let { AlbumShelves.coverOf(it, 120) },
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                shelf.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (shelf.count == 1) "1 album" else "${shelf.count} albums",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (on) "On this shelf" else null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (shelves == null) {
                    item(key = "none") {
                        Text(
                            "Couldn't reach the companion. Shelves need companion 0.3.0 or later.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
