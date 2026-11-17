package com.hypergallery.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypergallery.domain.model.FolderSummary
import com.hypergallery.domain.model.GroupMode
import com.hypergallery.domain.model.SortCriterion
import com.hypergallery.security.BiometricAuthManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortAndGridSheet(
    currentSort: SortCriterion,
    currentAsc: Boolean,
    currentGroup: GroupMode,
    gridColumns: Int,
    listViewMode: Boolean,
    isFolderOverride: Boolean = false,
    onDismiss: () -> Unit,
    onSelectSort: (SortCriterion, Boolean) -> Unit,
    onClearFolderSortOverride: (() -> Unit)? = null,
    onReshuffle: () -> Unit,
    onSelectGroup: (GroupMode) -> Unit,
    onColumnsChange: (Int) -> Unit,
    onListViewChange: (Boolean) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFolderOverride) "Tri personnalisé de ce dossier" else "Tri, Regroupement & Grille",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onSelectSort(currentSort, !currentAsc) }) {
                        Icon(
                            imageVector = if (currentAsc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (currentAsc) "Croissant" else "Décroissant", fontSize = 12.sp)
                    }
                }
            }

            if (isFolderOverride && onClearFolderSortOverride != null) {
                TextButton(onClick = {
                    onClearFolderSortOverride()
                    onDismiss()
                }) {
                    Text("Réinitialiser au tri global par défaut")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SortCriterion.entries
                    .filter { it != SortCriterion.ITEM_COUNT }
                    .forEach { criterion ->
                        FilterChip(
                            selected = currentSort == criterion,
                            onClick = { onSelectSort(criterion, currentAsc) },
                            label = { Text(criterion.label, fontSize = 12.sp) }
                        )
                    }
            }

            if (currentSort == SortCriterion.SHUFFLE) {
                OutlinedButton(onClick = onReshuffle, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Recalculer un nouvel ordre aléatoire")
                }
            }

            HorizontalDivider()

            Text("Regroupement chronologique / dossier", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentGroup == mode,
                        onClick = { onSelectGroup(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) }
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mode Liste détaillée", fontWeight = FontWeight.SemiBold)
                Switch(checked = listViewMode, onCheckedChange = onListViewChange)
            }

            if (!listViewMode) {
                Text("Colonnes de grille : $gridColumns (ou zoom pincé à 2 doigts)", fontSize = 13.sp)
                Slider(
                    value = gridColumns.toFloat(),
                    onValueChange = { onColumnsChange(it.roundToInt().coerceIn(1, 8)) },
                    valueRange = 1f..8f,
                    steps = 6
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun BatchSelectionActionBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onBatchRename: () -> Unit,
    onMoveToVault: () -> Unit,
    onMoveToTrash: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Annuler sélection")
                    }
                    Text(
                        text = "$selectedCount sélectionné(s)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Glisser pour étendre",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Partager")
                }
                TextButton(onClick = onFavorite) {
                    Icon(Icons.Filled.Favorite, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Favoris")
                }
                TextButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copier")
                }
                TextButton(onClick = onMove) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Déplacer")
                }
                TextButton(onClick = onBatchRename) {
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Renommer lot")
                }
                TextButton(onClick = onMoveToVault) {
                    Icon(Icons.Filled.EnhancedEncryption, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Coffre AES")
                }
                TextButton(onClick = onMoveToTrash) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Corbeille")
                }
                TextButton(onClick = onDeletePermanently) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Suppr. déf.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun BatchRenameDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pattern by remember { mutableStateOf("Vacances_{date}_{index}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommage en série ($count fichiers)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Jetons pris en charge : {date}, {index}, {name}, {folder}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Motif de renommage") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Aperçu : ${
                        pattern
                            .replace("{date}", "20261117")
                            .replace("{index}", "001")
                            .replace("{name}", "IMG")
                            .replace("{folder}", "Camera")
                    }.jpg",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(pattern)
                onDismiss()
            }) {
                Text("Renommer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
fun FolderDestinationDialog(
    title: String,
    folders: List<FolderSummary>,
    onDismiss: () -> Unit,
    onSelectFolder: (String) -> Unit
) {
    var customPath by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(folders, key = { it.path }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectFolder(folder.path)
                                    onDismiss()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(folder.name, fontWeight = FontWeight.SemiBold)
                                Text(folder.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    label = { Text("Ou chemin cible personnalisé") },
                    placeholder = { Text("/storage/emulated/0/Pictures/MonAlbum") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            if (customPath.isNotBlank()) {
                Button(onClick = {
                    onSelectFolder(customPath.trim())
                    onDismiss()
                }) {
                    Text("Choisir ce chemin")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
fun PinAuthenticationDialog(
    biometricAuthManager: BiometricAuthManager,
    title: String = "Code PIN de Sécurité",
    onDismiss: () -> Unit,
    onAuthenticated: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var isSetupMode by remember { mutableStateOf<Boolean?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        isSetupMode = !biometricAuthManager.hasPinConfigured()
    }

    val setup = isSetupMode ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (setup) "Configurer un Code PIN de secours" else title)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (setup) {
                        "Choisissez un code PIN d'au moins 4 chiffres (haché localement en PBKDF2-HMAC-SHA256)."
                    } else {
                        "Saisissez votre code PIN pour déverrouiller."
                    },
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { ch -> ch.isDigit() }.take(12)
                        errorText = null
                    },
                    label = { Text("Code PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorText?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    if (setup) {
                        if (pin.length < 4) {
                            errorText = "Minimum 4 chiffres requis"
                        } else {
                            biometricAuthManager.setupPin(pin)
                            onAuthenticated()
                        }
                    } else {
                        if (biometricAuthManager.verifyPin(pin)) {
                            onAuthenticated()
                        } else {
                            errorText = "Code PIN incorrect"
                        }
                    }
                }
            }) {
                Text(if (setup) "Enregistrer & Ouvrir" else "Déverrouiller")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
