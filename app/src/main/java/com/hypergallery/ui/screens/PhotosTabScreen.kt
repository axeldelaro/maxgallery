package com.hypergallery.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.hypergallery.domain.model.QuickFilter
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.components.BatchRenameDialog
import com.hypergallery.ui.components.BatchSelectionActionBar
import com.hypergallery.ui.components.FolderDestinationDialog
import com.hypergallery.ui.components.PinAuthenticationDialog
import com.hypergallery.ui.components.PinchDragMediaGrid
import com.hypergallery.ui.components.SortAndGridSheet
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PhotosTabScreen(
    viewModel: GalleryViewModel,
    hasFullStoragePermission: Boolean,
    onRequestFullStoragePermission: () -> Unit,
    onOpenViewer: () -> Unit,
    onLaunchSlideshow: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilters by viewModel.activeFilters.collectAsState()
    val selectedIds by viewModel.selectedMediaIds.collectAsState()
    val isScanning by viewModel.isScanningMedia.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val hiddenCount by viewModel.hiddenIndexedCount.collectAsState()
    val memories by viewModel.memoriesToday.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val sensitiveUnlocked by viewModel.securitySessionManager.isSensitiveContentActive.collectAsState()

    val pagingItems = viewModel.pagedTimeline.collectAsLazyPagingItems()

    var showSearchBar by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var copyOrMoveMode by remember { mutableStateOf<Boolean?>(null) }
    var showPinForHidden by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "HyperGallery AI",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = if (settings.onlyHiddenMediaMode) Color(0xFF00E676).copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            viewModel.toggleOnlyHiddenMediaMode(!settings.onlyHiddenMediaMode)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = if (settings.onlyHiddenMediaMode) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (settings.onlyHiddenMediaMode) "Masqués uniquement" else "Tous médias",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (settings.onlyHiddenMediaMode) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Text(
                    text = if (isScanning) {
                        "Scan Turbo : ${scanProgress.scannedDirectories} dossiers • ${scanProgress.hiddenMediaFound} médias masqués (${scanProgress.currentFolder})"
                    } else {
                        "$hiddenCount médias masqués détectés (.nomedia, .point & déguisés)" +
                            if (scanProgress.elapsedMs > 0) " • ${scanProgress.elapsedMs} ms" else ""
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }

            // 1-Tap Relock Padlock when any locked folder or vault session is active
            if (sensitiveUnlocked) {
                IconButton(onClick = {
                    viewModel.securitySessionManager.lockAllNow()
                    viewModel.emitMessage("Dossiers et Coffre-Fort reverrouillés")
                }) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Reverrouiller immédiatement",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            IconButton(onClick = { showSearchBar = !showSearchBar }) {
                Icon(Icons.Filled.Search, contentDescription = "Rechercher")
            }

            IconButton(onClick = { showSortSheet = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Trier & Regrouper")
            }

            IconButton(onClick = {
                scope.launch {
                    if (viewModel.prepareSlideshow(selectedIdsOnly = selectedIds.isNotEmpty())) {
                        onLaunchSlideshow()
                    }
                }
            }) {
                Icon(Icons.Filled.Slideshow, contentDescription = "Diaporama")
            }

            IconButton(onClick = { viewModel.triggerFullMediaScan() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Scan Turbo")
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Global Search Bar (Files, Folders, EXIF Models, AND Recognized Person Names!)
        if (showSearchBar || searchQuery.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Nom, dossier masqué, appareil EXIF ou prénom IA...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = {
                        viewModel.setSearchQuery("")
                        showSearchBar = false
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Effacer")
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        // Cumulable Instant Quick Filters Row (includes Dossiers .point & Dossiers .nomedia!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeFilters.isEmpty(),
                onClick = { viewModel.clearFilters() },
                label = { Text(if (settings.onlyHiddenMediaMode) "Tous masqués" else "Tout") }
            )
            QuickFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter in activeFilters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        // Selection Batch Bar when items are selected
        if (selectedIds.isNotEmpty()) {
            BatchSelectionActionBar(
                selectedCount = selectedIds.size,
                onClear = { viewModel.clearSelection() },
                onShare = { viewModel.shareSelected(context) },
                onFavorite = { viewModel.toggleFavorite(selectedIds.toList(), true) },
                onCopy = { copyOrMoveMode = false },
                onMove = { copyOrMoveMode = true },
                onBatchRename = { showRenameDialog = true },
                onMoveToVault = { viewModel.moveSelectedToVault() },
                onMoveToTrash = { viewModel.moveSelectedToTrash() },
                onDeletePermanently = { viewModel.deleteSelectedPermanently() }
            )
        }

        // Main 120Hz Paging 3 Grid
        Box(modifier = Modifier.weight(1f)) {
            PinchDragMediaGrid(
                pagingItems = pagingItems,
                columns = settings.gridColumns,
                listViewMode = settings.listViewMode,
                selectedIds = selectedIds,
                onColumnsChange = { viewModel.setGridColumns(it) },
                onMediaClick = { media ->
                    scope.launch {
                        if (viewModel.prepareViewerFromCurrentContext(media.id)) {
                            onOpenViewer()
                        }
                    }
                },
                onToggleSelect = { viewModel.toggleMediaSelection(it) },
                onSelectRange = { viewModel.selectMediaRange(it, true) },
                onSelectGroup = { groupKey -> viewModel.selectEntireGroup(groupKey) },
                topHeaderContent = {
                    Column {
                        // Critical Full Storage Access Banner (MANAGE_EXTERNAL_STORAGE required by Android 11+ to read .nomedia & dot folders)
                        if (!hasFullStoragePermission) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.FolderSpecial,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Permission requise pour les médias masqués",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "Android bloque les dossiers .nomedia et .cachés sans MANAGE_EXTERNAL_STORAGE. Activez-le pour un scan complet.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = onRequestFullStoragePermission) {
                                        Text("Autoriser", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // Memories "Souvenirs de ce jour"
                        if (settings.memoriesEnabled && memories.isNotEmpty() && activeFilters.isEmpty() && searchQuery.isEmpty()) {
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = "Souvenirs masqués de ce jour (${memories.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(memories, key = { it.id }) { mem ->
                                        AsyncImage(
                                            model = File(mem.path),
                                            contentDescription = mem.fileName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 116.dp, height = 148.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable {
                                                    scope.launch {
                                                        if (viewModel.prepareViewerFromCurrentContext(mem.id)) {
                                                            onOpenViewer()
                                                        }
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    if (showSortSheet) {
        SortAndGridSheet(
            currentSort = settings.defaultSort,
            currentAsc = settings.defaultSortAsc,
            currentGroup = settings.groupMode,
            gridColumns = settings.gridColumns,
            listViewMode = settings.listViewMode,
            onDismiss = { showSortSheet = false },
            onSelectSort = { crit, asc -> viewModel.setDefaultSort(crit, asc) },
            onReshuffle = { viewModel.reshuffleTimeline() },
            onSelectGroup = { viewModel.setGroupMode(it) },
            onColumnsChange = { viewModel.setGridColumns(it) },
            onListViewChange = { viewModel.setListViewMode(it) }
        )
    }

    if (showRenameDialog) {
        BatchRenameDialog(
            count = selectedIds.size,
            onDismiss = { showRenameDialog = false },
            onConfirm = { pattern -> viewModel.batchRenameSelected(pattern) }
        )
    }

    copyOrMoveMode?.let { isMove ->
        FolderDestinationDialog(
            title = if (isMove) "Déplacer vers le dossier" else "Copier vers le dossier",
            folders = folders,
            onDismiss = { copyOrMoveMode = null },
            onSelectFolder = { target -> viewModel.copyOrMoveSelected(target, move = isMove) }
        )
    }

    if (showPinForHidden) {
        PinAuthenticationDialog(
            biometricAuthManager = viewModel.biometricAuthManager,
            title = "Déverrouiller les fichiers cachés",
            onDismiss = { showPinForHidden = false },
            onAuthenticated = {
                showPinForHidden = false
                viewModel.securitySessionManager.authorizeHiddenFilesSession()
                scope.launch { viewModel.settingsRepository.updateShowHidden(true) }
            }
        )
    }
}
