package com.hypergallery.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil3.compose.AsyncImage
import com.hypergallery.domain.model.FolderExclusionMode
import com.hypergallery.domain.model.ThemeMode
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.components.PinAuthenticationDialog
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.launch
import java.io.File

private enum class ToolsSubPage {
    HOME,
    VAULT,
    TRASH,
    EXCLUSIONS,
    FAVORITES
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VaultAndToolsTabScreen(
    viewModel: GalleryViewModel,
    onOpenViewer: () -> Unit,
    onNavigateToFavoritesTimeline: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    val settings by viewModel.settings.collectAsState()
    val vaultItems by viewModel.vaultItems.collectAsState()
    val trashItems by viewModel.trashItems.collectAsState()
    val allRules by viewModel.allFolderRules.collectAsState()
    val isVaultUnlocked by viewModel.securitySessionManager.isVaultUnlocked.collectAsState()

    var subPage by remember { mutableStateOf(ToolsSubPage.HOME) }
    var showPinDialogForVault by remember { mutableStateOf(false) }
    var showPinConfigDialog by remember { mutableStateOf(false) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportBackupJson(it) }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importBackupJson(it) }
    }

    fun requestOpenVault() {
        if (isVaultUnlocked) {
            subPage = ToolsSubPage.VAULT
            return
        }
        if (activity != null) {
            viewModel.biometricAuthManager.authenticate(
                activity = activity,
                title = "Coffre-Fort Chiffré AES-256-GCM",
                subtitle = "Clé matérielle Android Keystore",
                onSuccess = {
                    viewModel.securitySessionManager.unlockVault()
                    subPage = ToolsSubPage.VAULT
                },
                onUsePinFallback = { showPinDialogForVault = true },
                onError = { viewModel.emitMessage(it) }
            )
        } else {
            showPinDialogForVault = true
        }
    }

    when (subPage) {
        ToolsSubPage.VAULT -> {
            BackHandler { subPage = ToolsSubPage.HOME }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { subPage = ToolsSubPage.HOME }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Coffre-Fort AES-256-GCM", fontWeight = FontWeight.Bold)
                        Text(
                            "FLAG_SECURE actif • Déchiffrement en flux RAM uniquement",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    IconButton(onClick = {
                        viewModel.securitySessionManager.lockAllNow()
                        subPage = ToolsSubPage.HOME
                    }) {
                        Icon(Icons.Filled.Lock, contentDescription = "Verrouiller immédiatement")
                    }
                }

                if (vaultItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Le Coffre-Fort est vide. Sélectionnez des photos/vidéos puis touchez 'Coffre AES'.")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(vaultItems, key = { it.vaultId }) { v ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                            ) {
                                Column {
                                    AsyncImage(
                                        model = viewModel.vaultManager.buildVaultUri(v.encryptedFilePath),
                                        contentDescription = v.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch {
                                                    if (viewModel.prepareViewerForVault()) {
                                                        onOpenViewer()
                                                    }
                                                }
                                            }
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.restoreVaultItem(listOf(v.vaultId)) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Filled.Restore, contentDescription = "Restaurer", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteVaultItems(listOf(v.vaultId)) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.DeleteForever,
                                                contentDescription = "Supprimer",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        ToolsSubPage.TRASH -> {
            BackHandler { subPage = ToolsSubPage.HOME }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { subPage = ToolsSubPage.HOME }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Text(
                        text = "Corbeille Récupérable (30 jours) • ${trashItems.size}",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (trashItems.isNotEmpty()) {
                        TextButton(onClick = {
                            viewModel.emptyTrash(trashItems.map { it.trashId })
                        }) {
                            Text("Tout vider", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (trashItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("La corbeille est vide.")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(trashItems, key = { it.trashId }) { t ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = File(t.trashFilePath),
                                        contentDescription = t.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            ByteFormatting.formatSize(t.sizeBytes),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { viewModel.restoreTrashItems(listOf(t.trashId)) }) {
                                        Icon(Icons.Filled.Restore, contentDescription = "Restaurer", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.emptyTrash(listOf(t.trashId)) }) {
                                        Icon(Icons.Filled.DeleteForever, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        ToolsSubPage.EXCLUSIONS -> {
            BackHandler { subPage = ToolsSubPage.HOME }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { subPage = ToolsSubPage.HOME }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Text("Gestion des Dossiers Exclus & Inclus", fontWeight = FontWeight.Bold)
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mode Dossiers Choisis Uniquement (Liste Blanche)", fontWeight = FontWeight.Bold)
                            Text("N'afficher dans la chronologie que les dossiers marqués 'Liste Blanche'", fontSize = 11.sp)
                        }
                        Switch(
                            checked = settings.whitelistModeEnabled,
                            onCheckedChange = {
                                scope.launch { viewModel.settingsRepository.updateWhitelistMode(it) }
                            }
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allRules, key = { it.path }) { rule ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(File(rule.path).name, fontWeight = FontWeight.Bold)
                                Text(rule.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Mode : ${rule.exclusionMode} • Verrouillé : ${if (rule.isLocked) "Oui" else "Non"} • Exclu IA : ${if (rule.excludeFromFaceAi) "Oui" else "Non"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        viewModel.setFolderExclusion(rule.path, FolderExclusionMode.NONE)
                                        viewModel.TriggerRescanAfterRestore()
                                    }) {
                                        Text("Restaurer / Réinclure")
                                    }
                                    if (rule.isLocked) {
                                        TextButton(onClick = { viewModel.setFolderLocked(rule.path, false) }) {
                                            Text("Retirer verrou")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return
        }

        else -> Unit
    }

    // Main Tools & Settings Screen
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Coffre-Fort, Outils & Paramètres",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )

        // Quick Security & Vault Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolActionCard(
                icon = Icons.Filled.EnhancedEncryption,
                title = "Coffre AES-256",
                subtitle = "${vaultItems.size} fichiers chiffrés",
                onClick = { requestOpenVault() },
                modifier = Modifier.weight(1f)
            )
            ToolActionCard(
                icon = Icons.Filled.Delete,
                title = "Corbeille (30j)",
                subtitle = "${trashItems.size} éléments",
                onClick = { subPage = ToolsSubPage.TRASH },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ToolActionCard(
                icon = Icons.Filled.Favorite,
                title = "Favoris ❤️",
                subtitle = "Accès direct",
                onClick = onNavigateToFavoritesTimeline,
                modifier = Modifier.weight(1f)
            )
            ToolActionCard(
                icon = Icons.Filled.FolderOff,
                title = "Exclusions & Liste Blanche",
                subtitle = "${allRules.size} règles dossiers",
                onClick = { subPage = ToolsSubPage.EXCLUSIONS },
                modifier = Modifier.weight(1f)
            )
        }

        // Locked Folders Quick Status
        val lockedRules = allRules.filter { it.isLocked }
        if (lockedRules.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${lockedRules.size} dossier(s) verrouillé(s) par empreinte", fontWeight = FontWeight.Bold)
                        Text("Reverrouillage instantané dès l'extinction d'écran ou mise en arrière-plan", fontSize = 11.sp)
                    }
                    IconButton(onClick = {
                        viewModel.securitySessionManager.lockAllNow()
                        viewModel.emitMessage("Toutes les sessions biométriques sont reverrouillées")
                    }) {
                        Icon(Icons.Filled.LockOpen, contentDescription = "Reverrouiller")
                    }
                }
            }
        }

        HorizontalDivider()

        // Appearance & 120Hz AMOLED Settings
        Text("Apparence & Rendu 120 Hz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                    label = { Text(mode.label, fontSize = 12.sp) }
                )
            }
        }

        SettingSwitchItem(
            title = "Couleurs dynamiques Material You (Android 12+)",
            subtitle = "Adapte les accents au fond d'écran système tout en préservant le noir pur AMOLED",
            checked = settings.dynamicColors,
            onCheckedChange = { viewModel.setDynamicColors(it) }
        )

        SettingSwitchItem(
            title = "Souvenirs du jour dans l'onglet Photos",
            subtitle = "Affiche vos clichés pris à la même date les années précédentes",
            checked = settings.memoriesEnabled,
            onCheckedChange = { scope.launch { viewModel.settingsRepository.updateMemoriesEnabled(it) } }
        )

        HorizontalDivider()

        // Hidden Files & Security Settings
        Text("Fichiers Cachés & Biométrie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingSwitchItem(
            title = "Afficher les dossiers et fichiers cachés (. et .nomedia)",
            subtitle = "Révèle les dossiers commençant par un point et ceux contenant .nomedia",
            checked = settings.showHiddenFiles,
            onCheckedChange = { scope.launch { viewModel.settingsRepository.updateShowHidden(it) } }
        )

        SettingSwitchItem(
            title = "Protéger l'affichage des fichiers cachés par empreinte",
            subtitle = "Exige BiometricPrompt ou le PIN de secours avant d'afficher les fichiers cachés",
            checked = settings.protectHiddenWithBiometric,
            onCheckedChange = { scope.launch { viewModel.settingsRepository.updateProtectHiddenBiometric(it) } }
        )

        OutlinedButton(
            onClick = { showPinConfigDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Password, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Configurer / Modifier le Code PIN de secours (PBKDF2)")
        }

        HorizontalDivider()

        // Local AI Vision Settings
        Text("IA Locale de Reconnaissance Faciale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SettingSwitchItem(
            title = "Activer l'indexation IA locale des visages",
            subtitle = "100 % hors-ligne sur NPU/GPU (BlazeFace + MobileFaceNet + DBSCAN)",
            checked = settings.faceAiEnabled,
            onCheckedChange = {
                scope.launch {
                    viewModel.settingsRepository.updateFaceAiSettings(enabled = it)
                    if (it) viewModel.faceCoordinator.enqueueIndexing()
                }
            }
        )

        Column {
            Text(
                text = "Seuil de regroupement cosinus DBSCAN : ${String.format("%.2f", settings.faceClusterThreshold)} (défaut 0.40)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Slider(
                value = settings.faceClusterThreshold,
                onValueChange = {
                    scope.launch { viewModel.settingsRepository.updateFaceAiSettings(threshold = it) }
                },
                valueRange = 0.25f..0.60f
            )
            OutlinedButton(onClick = {
                scope.launch {
                    viewModel.faceCoordinator.reclusterWithThreshold(settings.faceClusterThreshold)
                    viewModel.emitMessage("Regroupement DBSCAN recalculé")
                }
            }) {
                Text("Recalculer les groupes avec ce seuil", fontSize = 12.sp)
            }
        }

        SettingSwitchItem(
            title = "Scan initial sur secteur ou batterie > 25 %",
            subtitle = "Économise l'autonomie lors des grosses indexations",
            checked = settings.faceScanOnlyChargingOrHighBattery,
            onCheckedChange = {
                scope.launch { viewModel.settingsRepository.updateFaceAiSettings(batteryGuard = it) }
            }
        )

        SettingSwitchItem(
            title = "Protection thermique intelligente",
            subtitle = "Met automatiquement le worker IA en pause si le SoC chauffe",
            checked = settings.faceScanThermalProtection,
            onCheckedChange = {
                scope.launch { viewModel.settingsRepository.updateFaceAiSettings(thermalGuard = it) }
            }
        )

        HorizontalDivider()

        // Offline JSON Backup & Restore
        Text("Sauvegarde & Restauration Hors-Ligne (JSON)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { exportBackupLauncher.launch("hypergallery_backup.json") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Exporter JSON", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Importer JSON", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(88.dp))
    }

    if (showPinDialogForVault) {
        PinAuthenticationDialog(
            biometricAuthManager = viewModel.biometricAuthManager,
            title = "Déverrouiller le Coffre-Fort AES-256",
            onDismiss = { showPinDialogForVault = false },
            onAuthenticated = {
                showPinDialogForVault = false
                viewModel.securitySessionManager.unlockVault()
                subPage = ToolsSubPage.VAULT
            }
        )
    }

    if (showPinConfigDialog) {
        var newPin by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPinConfigDialog = false },
            title = { Text("Nouveau Code PIN de secours") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { ch -> ch.isDigit() }.take(12) },
                    label = { Text("PIN (4 à 12 chiffres)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPin.length >= 4) {
                        scope.launch {
                            viewModel.biometricAuthManager.setupPin(newPin)
                            viewModel.emitMessage("Code PIN haché PBKDF2-SHA256 enregistré !")
                            showPinConfigDialog = false
                        }
                    }
                }) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinConfigDialog = false }) { Text("Annuler") }
            }
        )
    }
}

private fun GalleryViewModel.TriggerRescanAfterRestore() {
    triggerFullMediaScan()
}

@Composable
private fun ToolActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
