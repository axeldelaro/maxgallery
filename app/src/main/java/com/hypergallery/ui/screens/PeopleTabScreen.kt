package com.hypergallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import com.hypergallery.data.local.entity.PersonClusterEntity
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.components.PinchDragMediaGrid
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun PeopleTabScreen(
    viewModel: GalleryViewModel,
    onOpenViewer: () -> Unit,
    onLaunchSlideshow: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val faceStatus by viewModel.faceIndexStatus.collectAsState()
    val allPersons by viewModel.allPersonsIncludingHidden.collectAsState()
    val selectedPersonIds by viewModel.selectedPersonIds.collectAsState()
    val selectedMediaIds by viewModel.selectedMediaIds.collectAsState()

    val duplicates by viewModel.duplicateGroups.collectAsState()
    val isScanningDups by viewModel.isScanningDuplicates.collectAsState()

    var showHiddenPersons by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var viewingFilteredResults by remember { mutableStateOf(false) }
    var showDuplicatesView by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PersonClusterEntity?>(null) }
    var coverPickerPerson by remember { mutableStateOf<PersonClusterEntity?>(null) }

    val visiblePersons = remember(allPersons, showHiddenPersons) {
        if (showHiddenPersons) allPersons else allPersons.filter { !it.isHidden }
    }

    // Sub-screen: Duplicate & Similar Photo Detector (64-bit dHash)
    if (showDuplicatesView) {
        BackHandler { showDuplicatesView = false }
        DuplicatesSubScreen(
            groups = duplicates,
            isScanning = isScanningDups,
            onBack = { showDuplicatesView = false },
            onRescan = { viewModel.runDuplicateScan() },
            onCleanGroup = { viewModel.cleanDuplicatesKeepBest(it) }
        )
        return
    }

    // Sub-screen: Viewing 1 Person OR Combined Multi-Face Co-occurrence Results (2, 3+ people together!)
    if (viewingFilteredResults && selectedPersonIds.isNotEmpty()) {
        BackHandler {
            viewingFilteredResults = false
            viewModel.clearSelection()
            if (!multiSelectMode) viewModel.setSinglePersonView(null)
        }

        val personPaging = viewModel.pagedPersonFilteredMedia.collectAsLazyPagingItems()
        val chosenPersons = allPersons.filter { it.personId in selectedPersonIds }
        val singlePerson = chosenPersons.singleOrNull()

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    viewingFilteredResults = false
                    viewModel.clearSelection()
                    if (!multiSelectMode) viewModel.setSinglePersonView(null)
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour aux Personnes")
                }

                Column(modifier = Modifier.weight(1f)) {
                    val headerTitle = if (singlePerson != null) {
                        singlePerson.name.ifBlank { "Personne #${singlePerson.personId}" }
                    } else {
                        "Ensemble (${chosenPersons.size}) : " +
                            chosenPersons.joinToString(" & ") { it.name.ifBlank { "#${it.personId}" } }
                    }
                    Text(
                        text = headerTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (singlePerson != null) "${singlePerson.faceCount} apparitions"
                        else "Photos où toutes ces personnes apparaissent ensemble",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (singlePerson != null) {
                    IconButton(onClick = { renameTarget = singlePerson }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Nommer cette personne")
                    }
                    IconButton(onClick = { coverPickerPerson = singlePerson }) {
                        Icon(Icons.Filled.Face, contentDescription = "Choisir photo de profil")
                    }
                    IconButton(onClick = {
                        viewModel.setPersonHidden(singlePerson.personId, !singlePerson.isHidden)
                    }) {
                        Icon(
                            imageVector = if (singlePerson.isHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Masquer cette personne"
                        )
                    }
                }

                IconButton(onClick = {
                    scope.launch {
                        if (viewModel.prepareSlideshow(personIds = selectedPersonIds)) {
                            onLaunchSlideshow()
                        }
                    }
                }) {
                    Icon(Icons.Filled.Slideshow, contentDescription = "Diaporama de cette personne")
                }
            }

            // Human Correction Bar ("Retirer de cette personne" -> cannot_link)
            if (singlePerson != null && selectedMediaIds.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedMediaIds.size} photo(s) sélectionnée(s)",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Button(onClick = {
                            viewModel.detachSelectedMediaFromPerson(
                                personId = singlePerson.personId,
                                mediaIds = selectedMediaIds.toList()
                            )
                        }) {
                            Icon(Icons.Filled.PersonRemove, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Retirer de cette personne")
                        }
                    }
                }
            }

            PinchDragMediaGrid(
                pagingItems = personPaging,
                columns = settings.gridColumns,
                listViewMode = settings.listViewMode,
                selectedIds = selectedMediaIds,
                onColumnsChange = { viewModel.setGridColumns(it) },
                onMediaClick = { media ->
                    scope.launch {
                        if (viewModel.prepareViewerFromCurrentContext(media.id, personIds = selectedPersonIds)) {
                            onOpenViewer()
                        }
                    }
                },
                onToggleSelect = { viewModel.toggleMediaSelection(it) },
                onSelectRange = { viewModel.selectMediaRange(it, true) },
                onSelectGroup = { gk -> viewModel.selectEntireGroup(gk) },
                modifier = Modifier.weight(1f)
            )
        }

        renameTarget?.let { target ->
            RenamePersonDialog(
                person = target,
                onDismiss = { renameTarget = null },
                onSave = { name -> viewModel.renamePerson(target.personId, name) }
            )
        }

        coverPickerPerson?.let { person ->
            val personFaces by viewModel.observeFacesForPerson(person.personId).collectAsState(initial = emptyList())
            ModalBottomSheet(onDismissRequest = { coverPickerPerson = null }) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Choisir la photo de profil favorite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(personFaces, key = { it.faceId }) { face ->
                            AsyncImage(
                                model = File(face.cropPath),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        viewModel.setPersonCover(person.personId, face)
                                        coverPickerPerson = null
                                    }
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        return
    }

    // Main People Overview Grid
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Personnes (IA 100 % Locale)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "MobileFaceNet + DBSCAN hors-ligne",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Duplicate Detector Quick Button
            OutlinedButton(
                onClick = {
                    showDuplicatesView = true
                    if (duplicates.isEmpty()) viewModel.runDuplicateScan()
                }
            ) {
                Icon(Icons.Filled.FindReplace, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Doublons", fontSize = 12.sp)
            }

            IconButton(onClick = { showHiddenPersons = !showHiddenPersons }) {
                Icon(
                    imageVector = if (showHiddenPersons) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = "Personnes masquées"
                )
            }
        }

        // Real-Time Local AI Indexing Banner (Pause / Reprendre / Réindexer)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Analyse IA locale : ${faceStatus.processedCount} / ${faceStatus.totalCount} médias • ${faceStatus.detectedFacesCount} visages trouvés",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (faceStatus.totalCount > 0 && faceStatus.processedCount < faceStatus.totalCount) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            (faceStatus.processedCount.toFloat() / faceStatus.totalCount.coerceAtLeast(1).toFloat())
                                .coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = faceStatus.statusNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (faceStatus.isPaused) {
                            TextButton(onClick = { scope.launch { viewModel.faceCoordinator.resumeIndexing() } }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Reprendre", fontSize = 12.sp)
                            }
                        } else {
                            TextButton(onClick = { scope.launch { viewModel.faceCoordinator.pauseIndexing() } }) {
                                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Pause", fontSize = 12.sp)
                            }
                        }
                        TextButton(onClick = { scope.launch { viewModel.faceCoordinator.fullReindex() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Réindexer", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Multi-Person Action Bar (Combined Multi-Face Filter OR Merge Same Person!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = multiSelectMode,
                onClick = {
                    multiSelectMode = !multiSelectMode
                    if (!multiSelectMode) viewModel.setSinglePersonView(null)
                },
                leadingIcon = { Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Sélection multiple / Multi-visages") }
            )

            if (multiSelectMode && selectedPersonIds.size >= 2) {
                Button(onClick = { viewingFilteredResults = true }) {
                    Text("Ensemble (${selectedPersonIds.size})", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    viewModel.mergePersons(selectedPersonIds)
                    multiSelectMode = false
                }) {
                    Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fusionner", fontSize = 12.sp)
                }
            }
        }

        if (visiblePersons.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucun visage regroupé pour le moment.\nLancez ou patientez pendant l'analyse IA locale de vos photos.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visiblePersons, key = { it.personId }) { person ->
                    val isSelected = person.personId in selectedPersonIds
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (multiSelectMode) {
                                    viewModel.togglePersonFilter(person.personId)
                                } else {
                                    viewModel.setSinglePersonView(person.personId)
                                    viewingFilteredResults = true
                                }
                            },
                            onLongClick = {
                                multiSelectMode = true
                                viewModel.togglePersonFilter(person.personId)
                            }
                        )
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            AsyncImage(
                                model = person.coverCropPath?.let { File(it) },
                                contentDescription = person.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .then(
                                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier
                                    )
                            )
                            if (isSelected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.Black, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = person.name.ifBlank { "Ajouter un nom" },
                            fontWeight = if (person.name.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                            color = if (person.name.isNotBlank()) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${person.faceCount} photos",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenamePersonDialog(
    person: PersonClusterEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(person.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nommer cette personne") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Vous pourrez ensuite rechercher ce prénom directement dans la barre de recherche globale.",
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Prénom ou nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(name)
                onDismiss()
            }) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun DuplicatesSubScreen(
    groups: List<com.hypergallery.domain.model.DuplicateGroup>,
    isScanning: Boolean,
    onBack: () -> Unit,
    onRescan: () -> Unit,
    onCleanGroup: (com.hypergallery.domain.model.DuplicateGroup) -> Unit
) {
    val totalReclaimable = remember(groups) { groups.sumOf { it.reclaimableBytes } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Doublons & Similaires (dHash 64-bit)", fontWeight = FontWeight.Bold)
                Text(
                    text = "${groups.size} groupes • ${ByteFormatting.formatSize(totalReclaimable)} récupérables",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = "Relancer scan")
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!isScanning && groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucun doublon détecté (distance de Hamming ≤ 5).")
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(groups, key = { it.groupId }) { group ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${group.items.size} clichés similaires • Libérer ${ByteFormatting.formatSize(group.reclaimableBytes)}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Button(onClick = { onCleanGroup(group) }) {
                                    Text("Garder le meilleur", fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(group.items, key = { it.id }) { dup ->
                                    val isBest = dup.id == group.recommendedKeepId
                                    Column(
                                        modifier = Modifier.width(120.dp)
                                    ) {
                                        Box {
                                            AsyncImage(
                                                model = File(dup.path),
                                                contentDescription = dup.fileName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(120.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .then(
                                                        if (isBest) Modifier.border(
                                                            2.dp,
                                                            Color(0xFF00E676),
                                                            RoundedCornerShape(10.dp)
                                                        ) else Modifier
                                                    )
                                            )
                                            if (isBest) {
                                                Surface(
                                                    color = Color(0xFF00E676),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier
                                                        .align(Alignment.TopStart)
                                                        .padding(4.dp)
                                                ) {
                                                    Text(
                                                        text = "Meilleur",
                                                        color = Color.Black,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "${dup.width}×${dup.height} • ${ByteFormatting.formatSize(dup.sizeBytes)}",
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
