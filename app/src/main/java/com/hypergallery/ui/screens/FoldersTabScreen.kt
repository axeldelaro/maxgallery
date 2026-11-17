package com.hypergallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.hypergallery.domain.model.FolderExclusionMode
import com.hypergallery.domain.model.FolderSummary
import com.hypergallery.domain.model.SortCriterion
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.components.BatchSelectionActionBar
import com.hypergallery.ui.components.PinAuthenticationDialog
import com.hypergallery.ui.components.PinchDragMediaGrid
import com.hypergallery.ui.components.SortAndGridSheet
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldersTabScreen(
    viewModel: GalleryViewModel,
    onOpenViewer: () -> Unit,
    onLaunchSlideshow: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val activeFolderPath by viewModel.activeFolderPath.collectAsState()
    val unlockedFolders by viewModel.securitySessionManager.unlockedFolders.collectAsState()
    val selectedIds by viewModel.selectedMediaIds.collectAsState()
    val allRules by viewModel.allFolderRules.collectAsState()

    val treeDir by viewModel.treeCurrentDir.collectAsState()
    val treeNodes by viewModel.treeNodes.collectAsState()
    val storageVolumes = remember { viewModel.scanner.discoverStorageVolumes() }

    var explorerTreeMode by remember { mutableStateOf(false) }
    var contextMenuFolder by remember { mutableStateOf<FolderSummary?>(null) }
    var pendingLockedFolderPath by remember { mutableStateOf<String?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showFolderSortSheet by remember { mutableStateOf(false) }

    LaunchedEffect(explorerTreeMode) {
        if (explorerTreeMode && treeDir == null) {
            storageVolumes.firstOrNull()?.let { viewModel.navigateFileTree(it.absolutePath) }
        }
    }

    fun requestUnlockAndOpenFolder(path: String) {
        if (path in unlockedFolders) {
            viewModel.openFolder(path)
            return
        }
        if (activity != null) {
            viewModel.biometricAuthManager.authenticate(
                activity = activity,
                title = "Dossier verrouillé par empreinte",
                subtitle = File(path).name,
                onSuccess = {
                    viewModel.securitySessionManager.unlockFolder(path)
                    viewModel.openFolder(path)
                },
                onUsePinFallback = { pendingLockedFolderPath = path },
                onError = { viewModel.emitMessage(it) }
            )
        } else {
            pendingLockedFolderPath = path
        }
    }

    // Inside a specific opened folder
    val currentOpenedFolder = activeFolderPath
    if (currentOpenedFolder != null) {
        BackHandler { viewModel.openFolder(null) }
        val folderPaging = viewModel.pagedActiveFolder.collectAsLazyPagingItems()
        val folderRule = allRules.firstOrNull { it.path == currentOpenedFolder }
        val effectiveSort = folderRule?.sortCriterion?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
            ?: settings.defaultSort
        val effectiveAsc = folderRule?.sortAscending ?: settings.defaultSortAsc
        val hasNoMedia = remember(currentOpenedFolder, folders) { File(currentOpenedFolder, ".nomedia").exists() }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.openFolder(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour aux dossiers")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = File(currentOpenedFolder).name.ifEmpty { currentOpenedFolder },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentOpenedFolder,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { viewModel.toggleFolderNoMedia(currentOpenedFolder) }) {
                    Icon(
                        imageVector = if (hasNoMedia) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "Basculer .nomedia",
                        tint = if (hasNoMedia) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showFolderSortSheet = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Tri du dossier")
                }
                IconButton(onClick = {
                    scope.launch {
                        if (viewModel.prepareSlideshow(folderPath = currentOpenedFolder)) {
                            onLaunchSlideshow()
                        }
                    }
                }) {
                    Icon(Icons.Filled.Slideshow, contentDescription = "Diaporama du dossier")
                }
            }

            if (selectedIds.isNotEmpty()) {
                BatchSelectionActionBar(
                    selectedCount = selectedIds.size,
                    onClear = { viewModel.clearSelection() },
                    onShare = { viewModel.shareSelected(context) },
                    onFavorite = { viewModel.toggleFavorite(selectedIds.toList(), true) },
                    onCopy = {},
                    onMove = {},
                    onBatchRename = {},
                    onMoveToVault = { viewModel.moveSelectedToVault() },
                    onMoveToTrash = { viewModel.moveSelectedToTrash() },
                    onDeletePermanently = { viewModel.deleteSelectedPermanently() }
                )
            }

            PinchDragMediaGrid(
                pagingItems = folderPaging,
                columns = settings.gridColumns,
                listViewMode = settings.listViewMode,
                selectedIds = selectedIds,
                onColumnsChange = { viewModel.setGridColumns(it) },
                onMediaClick = { media ->
                    scope.launch {
                        if (viewModel.prepareViewerFromCurrentContext(media.id, folderPath = currentOpenedFolder)) {
                            onOpenViewer()
                        }
                    }
                },
                onToggleSelect = { viewModel.toggleMediaSelection(it) },
                onSelectRange = { viewModel.selectMediaRange(it, true) },
                onSelectGroup = { gk -> viewModel.selectEntireGroup(gk, folderPath = currentOpenedFolder) },
                modifier = Modifier.weight(1f)
            )
        }

        if (showFolderSortSheet) {
            SortAndGridSheet(
                currentSort = effectiveSort,
                currentAsc = effectiveAsc,
                currentGroup = settings.groupMode,
                gridColumns = settings.gridColumns,
                listViewMode = settings.listViewMode,
                isFolderOverride = true,
                onDismiss = { showFolderSortSheet = false },
                onSelectSort = { crit, asc ->
                    viewModel.setFolderCustomSort(currentOpenedFolder, crit, asc)
                },
                onClearFolderSortOverride = {
                    viewModel.setFolderCustomSort(currentOpenedFolder, null, null)
                },
                onReshuffle = { viewModel.reshuffleTimeline() },
                onSelectGroup = { viewModel.setGroupMode(it) },
                onColumnsChange = { viewModel.setGridColumns(it) },
                onListViewChange = { viewModel.setListViewMode(it) }
            )
        }
        return
    }

    // Root Folders Tab (Albums Grid OR Hierarchical File Tree Explorer)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Dossiers & Stockage",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )

            // Switch Albums vs Real Tree Explorer
            FilterChip(
                selected = !explorerTreeMode,
                onClick = { explorerTreeMode = false },
                leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Albums") }
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = explorerTreeMode,
                onClick = { explorerTreeMode = true },
                leadingIcon = { Icon(Icons.Filled.AccountTree, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Arborescence") }
            )

            IconButton(onClick = {
                scope.launch {
                    viewModel.settingsRepository.updateShowHidden(!settings.showHiddenFiles)
                }
            }) {
                Icon(
                    imageVector = if (settings.showHiddenFiles) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = "Afficher cachés (. et .nomedia)",
                    tint = if (settings.showHiddenFiles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { showCreateFolderDialog = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Nouveau dossier")
            }
        }

        if (!explorerTreeMode) {
            // Albums Card Grid (Pinned on top, Biometric Padlock blur, .nomedia badge)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(folders, key = { it.path }) { folder ->
                    val isLockedAndClosed = folder.isLocked && folder.path !in unlockedFolders
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.05f)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = {
                                    if (isLockedAndClosed) {
                                        requestUnlockAndOpenFolder(folder.path)
                                    } else {
                                        viewModel.openFolder(folder.path)
                                    }
                                },
                                onLongClick = { contextMenuFolder = folder }
                            )
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (folder.coverPath != null) {
                                AsyncImage(
                                    model = File(folder.coverPath),
                                    contentDescription = folder.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (isLockedAndClosed) Modifier.blur(26.dp) else Modifier)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Black.copy(alpha = if (isLockedAndClosed) 0.55f else 0.1f),
                                                Color.Black.copy(alpha = 0.82f)
                                            )
                                        )
                                    )
                            )

                            // Top badges (Pin, .nomedia, Excluded)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (folder.isPinned) {
                                    Icon(
                                        Icons.Filled.PushPin,
                                        contentDescription = "Épinglé",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (folder.hasNoMedia || folder.isHiddenDot) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.7f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (folder.hasNoMedia) ".nomedia" else "Caché",
                                            color = Color(0xFFFFD740),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            if (isLockedAndClosed) {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = "Verrouillé",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = "Empreinte requise",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = folder.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${folder.mediaCount} éléments • ${ByteFormatting.formatSize(folder.totalSizeBytes)}",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Hierarchical Real File System Explorer (/storage/emulated/0 + SD + USB OTG)
            Column(modifier = Modifier.fillMaxSize()) {
                // Storage Volume Selector (Internal, SD Card, USB OTG)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    storageVolumes.forEach { vol ->
                        val selected = treeDir?.startsWith(vol.absolutePath) == true
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.navigateFileTree(vol.absolutePath) },
                            leadingIcon = { Icon(Icons.Filled.SdStorage, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = {
                                Text(if (vol.absolutePath.contains("emulated")) "Stockage Interne" else "Volume ${vol.name}")
                            }
                        )
                    }
                }

                // Breadcrumb bar + Up button
                val currentDir = treeDir
                if (currentDir != null) {
                    val isRootVol = storageVolumes.any { it.absolutePath == currentDir }
                    if (!isRootVol) {
                        BackHandler {
                            File(currentDir).parent?.let { viewModel.navigateFileTree(it) }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isRootVol) {
                            IconButton(onClick = {
                                File(currentDir).parent?.let { viewModel.navigateFileTree(it) }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Dossier parent")
                            }
                        }
                        Text(
                            text = currentDir,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { viewModel.openFolder(currentDir) }) {
                            Text("Ouvrir grille")
                        }
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(treeNodes, key = { it.path }) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (node.isDirectory) {
                                            if (node.isLocked && node.path !in unlockedFolders) {
                                                requestUnlockAndOpenFolder(node.path)
                                            } else {
                                                viewModel.navigateFileTree(node.path)
                                            }
                                        } else {
                                            viewModel.openFolder(File(node.path).parent)
                                        }
                                    },
                                    onLongClick = {
                                        if (node.isDirectory) {
                                            contextMenuFolder = FolderSummary(
                                                path = node.path,
                                                name = node.name,
                                                coverPath = null,
                                                coverUri = null,
                                                mediaCount = node.directMediaCount,
                                                totalSizeBytes = 0L,
                                                lastModified = node.lastModified,
                                                hasNoMedia = node.hasNoMedia,
                                                isHiddenDot = node.isHidden,
                                                isPinned = node.isPinned,
                                                isLocked = node.isLocked,
                                                exclusionMode = FolderExclusionMode.NONE,
                                                excludeFromFaceAi = false,
                                                sortCriterion = null,
                                                sortAscending = null
                                            )
                                        }
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (node.isLocked) Icons.Filled.Lock else Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(node.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (node.isDirectory) {
                                        "${node.subfolderCount} sous-dossiers • ${node.directMediaCount} médias" +
                                            if (node.hasNoMedia) " • .nomedia" else ""
                                    } else {
                                        ByteFormatting.formatSize(node.sizeBytes)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (node.isDirectory) {
                                TextButton(onClick = { viewModel.toggleFolderNoMedia(node.path) }) {
                                    Text(if (node.hasNoMedia) "-.nomedia" else "+.nomedia", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-Press Folder Context Bottom Sheet (Pin, Biometric Lock, .nomedia, Exclusions)
    contextMenuFolder?.let { folder ->
        ModalBottomSheet(onDismissRequest = { contextMenuFolder = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(folder.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.togglePinFolder(folder.path)
                        contextMenuFolder = null
                    }) {
                        Text(if (folder.isPinned) "Désépingler" else "Épingler en haut")
                    }
                    OutlinedButton(onClick = {
                        viewModel.toggleFolderNoMedia(folder.path)
                        contextMenuFolder = null
                    }) {
                        Text(if (folder.hasNoMedia) "Supprimer .nomedia" else "Créer .nomedia")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Verrouiller sur place par empreinte", fontWeight = FontWeight.SemiBold)
                        Text("Masque immédiatement de la timeline, recherche & Personnes", fontSize = 11.sp)
                    }
                    Switch(
                        checked = folder.isLocked,
                        onCheckedChange = { wantLock ->
                            viewModel.setFolderLocked(folder.path, wantLock)
                            contextMenuFolder = null
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Exclure de l'analyse IA des visages", fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = folder.excludeFromFaceAi,
                        onCheckedChange = {
                            viewModel.setFolderExcludeFaceAi(folder.path, it)
                            contextMenuFolder = null
                        }
                    )
                }

                HorizontalDivider()
                Text("Règle d'inclusion / exclusion", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FolderExclusionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = folder.exclusionMode == mode,
                            onClick = {
                                viewModel.setFolderExclusion(folder.path, mode)
                                contextMenuFolder = null
                            },
                            label = { Text(mode.label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Fallback PIN Unlock for Locked Folder
    pendingLockedFolderPath?.let { lockedPath ->
        PinAuthenticationDialog(
            biometricAuthManager = viewModel.biometricAuthManager,
            title = "Déverrouiller '${File(lockedPath).name}'",
            onDismiss = { pendingLockedFolderPath = null },
            onAuthenticated = {
                pendingLockedFolderPath = null
                viewModel.securitySessionManager.unlockFolder(lockedPath)
                viewModel.openFolder(lockedPath)
            }
        )
    }

    // Create New Folder Dialog
    if (showCreateFolderDialog) {
        val defaultParent = treeDir ?: storageVolumes.firstOrNull()?.let { "${it.absolutePath}/Pictures" } ?: "/storage/emulated/0/Pictures"
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Créer un nouveau dossier") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emplacement : $defaultParent", fontSize = 12.sp)
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Nom du dossier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (folderName.isNotBlank()) {
                        viewModel.createNewFolder(defaultParent, folderName)
                        showCreateFolderDialog = false
                    }
                }) {
                    Text("Créer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Annuler") }
            }
        )
    }
}
