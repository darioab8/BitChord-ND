package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.companion.AlbumShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.shelves.AlbumShelves
import com.music.bitchord.ui.components.LIBRARY_GRID_SPACING
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.libraryGrid
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * BitChord ND: a shelf's own page — its description and count, Play and
 * Shuffle, then its albums as the same grid of cards a Library "Show all"
 * opens onto. Each card opens its album, wherever that album lives.
 *
 * @param shelf null while it's first read.
 * @param error why it couldn't be read, when it couldn't.
 */
@Composable
fun AlbumShelfScreen(
    shelf: AlbumShelf?,
    error: String?,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEdit: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (shelf == null) {
        Column(modifier.fillMaxSize().padding(contentPadding)) {
            if (error != null) {
                MessageState(error, actionLabel = "Retry", onAction = onRetry)
            } else {
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        }
        return
    }
    val cards = remember(shelf) { shelf.albums.map(AlbumShelves::albumCard) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val grid = libraryGrid(maxWidth - PAGE_GUTTER * 2)
        LazyVerticalGrid(
            columns = GridCells.Fixed(grid.columns),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(LIBRARY_GRID_SPACING),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                ShelfHeader(
                    shelf = shelf,
                    onPlay = onPlay.takeIf { cards.isNotEmpty() },
                    onShuffle = onShuffle.takeIf { cards.isNotEmpty() },
                    onEdit = onEdit,
                )
            }
            if (cards.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    MessageState("Nothing here yet. Hold an album anywhere in the app and choose Add to shelf.")
                }
            }
            items(cards, key = { it.browseId ?: it.title }) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ShelfHeader(
    shelf: AlbumShelf,
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        if (shelf.description.isNotBlank()) {
            Text(
                text = shelf.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
        }
        val count = shelf.albums.size
        Text(
            text = if (count == 1) "1 album" else "$count albums",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPlay != null) {
                HeaderPill(BitChordIcons.Play, "Play", filled = true, onClick = onPlay, modifier = Modifier.weight(1f))
            }
            if (onShuffle != null) {
                HeaderPill(BitChordIcons.Shuffle, "Shuffle", filled = false, onClick = onShuffle, modifier = Modifier.weight(1f))
            }
            HeaderPill(null, "Edit", filled = false, onClick = onEdit)
        }
    }
}

@Composable
private fun HeaderPill(
    icon: ImageVector?,
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 1)
    }
}
