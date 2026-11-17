package com.hypergallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.repository.TimelineGridItem
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PinchDragMediaGrid(
    pagingItems: LazyPagingItems<TimelineGridItem>,
    columns: Int,
    listViewMode: Boolean,
    selectedIds: Set<Long>,
    onColumnsChange: (Int) -> Unit,
    onMediaClick: (MediaEntity) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSelectRange: (Collection<Long>) -> Unit,
    onSelectGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
    topHeaderContent: (@Composable () -> Unit)? = null
) {
    val gridState = rememberLazyGridState()
    val selectionMode = selectedIds.isNotEmpty()
    var accumulatedScale by remember(columns) { mutableFloatStateOf(1f) }

    // Non-blocking pinch-to-zoom detector: passes 1-finger touches straight through to 120Hz scroll!
    val pinchModifier = Modifier.pointerInput(columns) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                if (event.changes.size >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        accumulatedScale *= zoom
                        if (accumulatedScale > 1.28f && columns > 1) {
                            onColumnsChange(columns - 1)
                            accumulatedScale = 1f
                        } else if (accumulatedScale < 0.76f && columns < 8) {
                            onColumnsChange(columns + 1)
                            accumulatedScale = 1f
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            accumulatedScale = 1f
        }
    }

    Box(modifier = modifier.fillMaxSize().then(pinchModifier)) {
        if (listViewMode) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                if (topHeaderContent != null) {
                    item(key = "top_slot", contentType = "slot") {
                        topHeaderContent()
                    }
                }
                items(
                    count = pagingItems.itemCount,
                    key = { idx -> pagingItems.peek(idx)?.stableKey ?: "placeholder_$idx" },
                    contentType = { idx -> pagingItems.peek(idx)?.contentType ?: -1 }
                ) { idx ->
                    when (val item = pagingItems[idx]) {
                        is TimelineGridItem.SectionHeader -> {
                            StickySectionHeaderRow(
                                title = item.title,
                                onSelectGroup = { onSelectGroup(item.groupKey) }
                            )
                        }
                        is TimelineGridItem.MediaCell -> {
                            DetailedMediaListRow(
                                media = item.media,
                                isSelected = item.media.id in selectedIds,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) onToggleSelect(item.media.id)
                                    else onMediaClick(item.media)
                                },
                                onLongClick = { onToggleSelect(item.media.id) }
                            )
                        }
                        null -> Unit
                    }
                }
            }
        } else {
            // Drag-select overlay state when user is already in multi-selection mode
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns.coerceIn(1, 8)),
                modifier = Modifier
                    .fillMaxSize()
                    .dragSelectInGrid(
                        enabled = selectionMode,
                        gridState = gridState,
                        pagingItems = pagingItems,
                        onSelectIds = onSelectRange
                    ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                if (topHeaderContent != null) {
                    item(
                        key = "top_slot",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = 99
                    ) {
                        topHeaderContent()
                    }
                }

                items(
                    count = pagingItems.itemCount,
                    key = { idx -> pagingItems.peek(idx)?.stableKey ?: "ph_$idx" },
                    span = { idx ->
                        when (pagingItems.peek(idx)) {
                            is TimelineGridItem.SectionHeader -> GridItemSpan(maxLineSpan)
                            else -> GridItemSpan(1)
                        }
                    },
                    contentType = { idx -> pagingItems.peek(idx)?.contentType ?: -1 }
                ) { idx ->
                    when (val item = pagingItems[idx]) {
                        is TimelineGridItem.SectionHeader -> {
                            StickySectionHeaderRow(
                                title = item.title,
                                onSelectGroup = { onSelectGroup(item.groupKey) }
                            )
                        }
                        is TimelineGridItem.MediaCell -> {
                            MediaGridThumbnailCell(
                                media = item.media,
                                columns = columns,
                                isSelected = item.media.id in selectedIds,
                                selectionMode = selectionMode,
                                onClick = {
                                    if (selectionMode) onToggleSelect(item.media.id)
                                    else onMediaClick(item.media)
                                },
                                onLongClick = { onToggleSelect(item.media.id) }
                            )
                        }
                        null -> Unit
                    }
                }
            }

            // Chronological Fast Scroller on the right edge
            if (pagingItems.itemCount > 30) {
                GridFastScroller(
                    gridState = gridState,
                    pagingItems = pagingItems,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 12.dp)
                )
            }
        }
    }
}

private fun Modifier.dragSelectInGrid(
    enabled: Boolean,
    gridState: LazyGridState,
    pagingItems: LazyPagingItems<TimelineGridItem>,
    onSelectIds: (Collection<Long>) -> Unit
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitPointerEvent()
            if (down.changes.size != 1) return@awaitEachGesture
            var startIndex: Int? = null
            var movedHorizontallyOrDragged = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break

                val pos = change.position
                val hitItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    pos.x >= info.offset.x &&
                        pos.x <= info.offset.x + info.size.width &&
                        pos.y >= info.offset.y &&
                        pos.y <= info.offset.y + info.size.height
                }

                if (hitItem != null && startIndex != null && hitItem.index != startIndex) {
                    movedHorizontallyOrDragged = true
                }
                if (startIndex == null && hitItem != null) {
                    startIndex = hitItem.index
                }

                if (movedHorizontallyOrDragged && startIndex != null && hitItem != null) {
                    val minIdx = minOf(startIndex, hitItem.index).coerceAtLeast(0)
                    val maxIdx = maxOf(startIndex, hitItem.index).coerceAtMost(pagingItems.itemCount - 1)
                    val rangeIds = mutableListOf<Long>()
                    for (k in minIdx..maxIdx) {
                        val peeked = pagingItems.peek(k)
                        if (peeked is TimelineGridItem.MediaCell) {
                            rangeIds.add(peeked.media.id)
                        }
                    }
                    if (rangeIds.isNotEmpty()) {
                        onSelectIds(rangeIds)
                    }
                }
            }
        }
    }
}

@Composable
private fun StickySectionHeaderRow(
    title: String,
    onSelectGroup: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        IconButton(
            onClick = onSelectGroup,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Circle,
                contentDescription = "Sélectionner le groupe",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MediaGridThumbnailCell(
    media: MediaEntity,
    columns: Int,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbPx = when {
        columns == 1 -> 1080
        columns <= 3 -> 512
        columns <= 5 -> 320
        else -> 200
    }

    val modelData: Any = remember(media.path, media.uri) {
        if (media.uri.startsWith("hgvault://") || media.isRaw) {
            media.uri
        } else {
            File(media.path)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (columns == 1) 1.45f else 1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary)
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(modelData)
                .size(Size(thumbPx, thumbPx))
                .allowHardware(true)
                .build(),
            contentDescription = media.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Subtle bottom gradient for badges
        if (media.isVideo || media.isRaw || media.isAnimated || media.isFavorite || columns <= 2) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
            )
        }

        // Video duration badge
        if (media.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayCircleFilled,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                if (columns <= 5) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = ByteFormatting.formatDuration(media.durationMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // RAW or Animated badge
        if (media.isRaw && columns <= 5) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Text(
                    text = media.extension.uppercase(Locale.ROOT),
                    color = Color(0xFF00E5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        } else if (media.isAnimated && columns <= 5) {
            Icon(
                imageVector = Icons.Filled.Gif,
                contentDescription = "Animé",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
            )
        }

        if (media.isFavorite) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Favori",
                tint = Color(0xFFFF4081),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .size(14.dp)
            )
        }

        // 1-Column rich caption
        if (columns == 1) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = if (media.isFavorite) 24.dp else 8.dp, bottom = 6.dp, end = 64.dp)
            ) {
                Text(
                    text = media.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${media.width}×${media.height} • ${ByteFormatting.formatSize(media.sizeBytes)}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }

        // Selection indicator
        if (selectionMode || isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.4f))
                    .clickable { onLongClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailedMediaListRow(
    media: MediaEntity,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(if (media.isRaw) media.uri else File(media.path))
                .size(Size(180, 180))
                .build(),
            contentDescription = media.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${ByteFormatting.formatSize(media.sizeBytes)} • ${media.width}×${media.height}" +
                    if (media.isVideo) " • ${ByteFormatting.formatDuration(media.durationMs)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = media.parentPath,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selectionMode || isSelected) {
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun GridFastScroller(
    gridState: LazyGridState,
    pagingItems: LazyPagingItems<TimelineGridItem>,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val scrollFraction by remember {
        derivedStateOf {
            val total = pagingItems.itemCount.coerceAtLeast(1)
            (gridState.firstVisibleItemIndex.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
    }

    val activeFraction = if (isDragging) dragFraction else scrollFraction

    val bubbleLabel by remember {
        derivedStateOf {
            val idx = (activeFraction * (pagingItems.itemCount - 1)).roundToInt()
                .coerceIn(0, (pagingItems.itemCount - 1).coerceAtLeast(0))
            val item = pagingItems.peek(idx)
            val ts = when (item) {
                is TimelineGridItem.MediaCell -> item.media.dateTaken
                is TimelineGridItem.SectionHeader -> item.representativeTimestamp
                null -> 0L
            }
            if (ts > 0L) {
                SimpleDateFormat("MMM yyyy", Locale.FRANCE).format(Date(ts))
                    .replaceFirstChar { it.uppercase(Locale.FRANCE) }
            } else {
                ""
            }
        }
    }

    Box(
        modifier = modifier
            .width(120.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat().coerceAtLeast(1f) }
    ) {
        val thumbY = ((trackHeightPx - 120f).coerceAtLeast(0f) * activeFraction).roundToInt()

        if (isDragging && bubbleLabel.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-34, thumbY) }
            ) {
                Text(
                    text = bubbleLabel,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(26.dp)
                .fillMaxHeight()
                .pointerInput(pagingItems.itemCount) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val f = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                            dragFraction = f
                            val target = (f * (pagingItems.itemCount - 1)).roundToInt()
                                .coerceIn(0, (pagingItems.itemCount - 1).coerceAtLeast(0))
                            coroutineScope.launch {
                                gridState.scrollToItem(target)
                            }
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbY) }
                    .padding(end = 4.dp)
                    .width(6.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isDragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
            )
        }
    }
}
