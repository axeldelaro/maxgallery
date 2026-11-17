package com.hypergallery.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.hypergallery.ai.DuplicateDetector
import com.hypergallery.ai.FaceClusterEngine
import com.hypergallery.ai.FaceIndexCoordinator
import com.hypergallery.data.backup.BackupManager
import com.hypergallery.data.files.GalleryFileManager
import com.hypergallery.data.local.dao.FaceDao
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.MediaQuerySpec
import com.hypergallery.data.local.dao.TrashDao
import com.hypergallery.data.local.dao.VaultDao
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.local.entity.PersonClusterEntity
import com.hypergallery.data.local.entity.TrashItemEntity
import com.hypergallery.data.local.entity.VaultItemEntity
import com.hypergallery.data.preferences.SettingsRepository
import com.hypergallery.data.repository.GalleryRepository
import com.hypergallery.data.repository.TimelineGridItem
import com.hypergallery.data.scanner.HybridMediaScanner
import com.hypergallery.domain.model.AppSettings
import com.hypergallery.domain.model.DuplicateGroup
import com.hypergallery.domain.model.FaceIndexStatus
import com.hypergallery.domain.model.FileTreeNode
import com.hypergallery.domain.model.FolderExclusionMode
import com.hypergallery.domain.model.FolderSummary
import com.hypergallery.domain.model.GroupMode
import com.hypergallery.domain.model.QuickFilter
import com.hypergallery.domain.model.SlideshowConfig
import com.hypergallery.domain.model.SortCriterion
import com.hypergallery.domain.model.ThemeMode
import com.hypergallery.security.BiometricAuthManager
import com.hypergallery.security.EncryptedVaultManager
import com.hypergallery.security.SecuritySessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: GalleryRepository,
    val settingsRepository: SettingsRepository,
    val scanner: HybridMediaScanner,
    val fileManager: GalleryFileManager,
    val biometricAuthManager: BiometricAuthManager,
    val securitySessionManager: SecuritySessionManager,
    val vaultManager: EncryptedVaultManager,
    val faceCoordinator: FaceIndexCoordinator,
    private val clusterEngine: FaceClusterEngine,
    private val duplicateDetector: DuplicateDetector,
    private val backupManager: BackupManager,
    private val mediaDao: MediaDao,
    private val folderRuleDao: FolderRuleDao,
    private val faceDao: FaceDao,
    private val vaultDao: VaultDao,
    private val trashDao: TrashDao
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeFilters = MutableStateFlow<Set<QuickFilter>>(emptySet())
    val activeFilters: StateFlow<Set<QuickFilter>> = _activeFilters.asStateFlow()

    private val _selectedMediaIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedMediaIds: StateFlow<Set<Long>> = _selectedMediaIds.asStateFlow()

    // Combined Multi-Face selection in People tab (1, 2, 3+ people simultaneously)
    private val _selectedPersonIds = MutableStateFlow<List<Long>>(emptyList())
    val selectedPersonIds: StateFlow<List<Long>> = _selectedPersonIds.asStateFlow()

    // Current folder opened in Folders tab (null = root overview)
    private val _activeFolderPath = MutableStateFlow<String?>(null)
    val activeFolderPath: StateFlow<String?> = _activeFolderPath.asStateFlow()

    // Explorer tree state
    private val _treeCurrentDir = MutableStateFlow<String?>(null)
    val treeCurrentDir: StateFlow<String?> = _treeCurrentDir.asStateFlow()
    private val _treeNodes = MutableStateFlow<List<FileTreeNode>>(emptyList())
    val treeNodes: StateFlow<List<FileTreeNode>> = _treeNodes.asStateFlow()

    // Active playlist for Fullscreen Viewer & Slideshow
    private val _viewerItems = MutableStateFlow<List<MediaEntity>>(emptyList())
    val viewerItems: StateFlow<List<MediaEntity>> = _viewerItems.asStateFlow()

    private val _viewerStartIndex = MutableStateFlow(0)
    val viewerStartIndex: StateFlow<Int> = _viewerStartIndex.asStateFlow()

    private val _slideshowConfig = MutableStateFlow(SlideshowConfig())
    val slideshowConfig: StateFlow<SlideshowConfig> = _slideshowConfig.asStateFlow()

    // Duplicate scan state
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()
    private val _isScanningDuplicates = MutableStateFlow(false)
    val isScanningDuplicates: StateFlow<Boolean> = _isScanningDuplicates.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    val isScanningMedia: StateFlow<Boolean> = scanner.isScanning

    val totalIndexedCount: StateFlow<Int> = mediaDao.observeTotalIndexedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val memoriesToday: StateFlow<List<MediaEntity>> = repository.observeMemoriesToday()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val faceIndexStatus: StateFlow<FaceIndexStatus> = faceCoordinator.statusFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FaceIndexStatus())

    val activePersons: StateFlow<List<PersonClusterEntity>> = faceDao.observeActivePersons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPersonsIncludingHidden: StateFlow<List<PersonClusterEntity>> = faceDao.observeAllPersons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFolderRules: StateFlow<List<FolderRuleEntity>> = folderRuleDao.observeAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vaultItems: StateFlow<List<VaultItemEntity>> = vaultDao.observeVaultItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashItems: StateFlow<List<TrashItemEntity>> = trashDao.observeTrashItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Main Timeline Paging 3 Flow
    private val timelineQuerySpecFlow: Flow<Pair<MediaQuerySpec, GroupMode>> = combine(
        settings,
        _searchQuery,
        _activeFilters,
        securitySessionManager.isHiddenSessionAuthorized
    ) { cfg, query, filters, hiddenAuth ->
        val effectiveHidden = cfg.showHiddenFiles && (!cfg.protectHiddenWithBiometric || hiddenAuth)
        val spec = MediaQuerySpec(
            folderPath = null,
            showHidden = effectiveHidden,
            whitelistEnabled = cfg.whitelistModeEnabled,
            filters = filters,
            searchQuery = query,
            sortCriterion = cfg.defaultSort,
            sortAscending = cfg.defaultSortAsc,
            shuffleSeed = cfg.shuffleSeed
        )
        spec to cfg.groupMode
    }

    val pagedTimeline: Flow<PagingData<TimelineGridItem>> = timelineQuerySpecFlow
        .flatMapLatest { (spec, groupMode) ->
            repository.pagedGroupedMedia(spec, groupMode)
        }
        .cachedIn(viewModelScope)

    // Folder-specific Paging 3 Flow (honors per-folder custom sort!)
    val pagedActiveFolder: Flow<PagingData<TimelineGridItem>> = combine(
        _activeFolderPath,
        settings,
        allFolderRules,
        securitySessionManager.unlockedFolders,
        securitySessionManager.isHiddenSessionAuthorized
    ) { folderPath, cfg, rules, unlockedSet, hiddenAuth ->
        if (folderPath == null) return@combine null
        val rule = rules.firstOrNull { it.path == folderPath }
        val customSort = rule?.sortCriterion?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
        val effectiveSort = customSort ?: cfg.defaultSort
        val effectiveAsc = rule?.sortAscending ?: cfg.defaultSortAsc
        val effectiveHidden = cfg.showHiddenFiles && (!cfg.protectHiddenWithBiometric || hiddenAuth)

        MediaQuerySpec(
            folderPath = folderPath,
            isUnlockedSessionForFolder = folderPath in unlockedSet,
            showHidden = effectiveHidden || File(folderPath, ".nomedia").exists() || File(folderPath).name.startsWith("."),
            sortCriterion = effectiveSort,
            sortAscending = effectiveAsc,
            shuffleSeed = cfg.shuffleSeed
        ) to cfg.groupMode
    }.flatMapLatest { pair ->
        if (pair == null) {
            kotlinx.coroutines.flow.flowOf(PagingData.empty())
        } else {
            repository.pagedGroupedMedia(pair.first, pair.second)
        }
    }.cachedIn(viewModelScope)

    // Combined Multi-Person Paging 3 Flow
    val pagedPersonFilteredMedia: Flow<PagingData<TimelineGridItem>> = combine(
        _selectedPersonIds,
        settings
    ) { personIds, cfg ->
        if (personIds.isEmpty()) return@combine null
        MediaQuerySpec(
            personIds = personIds,
            showHidden = cfg.showHiddenFiles && !cfg.protectHiddenWithBiometric,
            sortCriterion = cfg.defaultSort,
            sortAscending = cfg.defaultSortAsc,
            shuffleSeed = cfg.shuffleSeed
        ) to cfg.groupMode
    }.flatMapLatest { pair ->
        if (pair == null) {
            kotlinx.coroutines.flow.flowOf(PagingData.empty())
        } else {
            repository.pagedGroupedMedia(pair.first, pair.second)
        }
    }.cachedIn(viewModelScope)

    // Folders overview list
    val folders: StateFlow<List<FolderSummary>> = combine(
        settings,
        securitySessionManager.isHiddenSessionAuthorized
    ) { cfg, hiddenAuth ->
        val effectiveHidden = cfg.showHiddenFiles && (!cfg.protectHiddenWithBiometric || hiddenAuth)
        Triple(effectiveHidden, cfg.whitelistModeEnabled, cfg.folderListSort to cfg.folderListSortAsc)
    }.flatMapLatest { (showHidden, whitelist, sortPair) ->
        repository.observeFolders(
            showHidden = showHidden,
            whitelistEnabled = whitelist,
            sortCriterion = sortPair.first,
            ascending = sortPair.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            fileManager.purgeExpiredTrash()
        }
        scanner.registerMediaObserver {
            faceCoordinator.enqueueIndexing()
        }
    }

    fun triggerFullMediaScan() {
        viewModelScope.launch {
            scanner.scanAllMedia()
            faceCoordinator.enqueueIndexing()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFilter(filter: QuickFilter) {
        val current = _activeFilters.value.toMutableSet()
        if (!current.add(filter)) current.remove(filter)
        _activeFilters.value = current
    }

    fun clearFilters() {
        _activeFilters.value = emptySet()
    }

    fun toggleMediaSelection(mediaId: Long) {
        val next = _selectedMediaIds.value.toMutableSet()
        if (!next.add(mediaId)) next.remove(mediaId)
        _selectedMediaIds.value = next
    }

    fun selectMediaRange(ids: Collection<Long>, select: Boolean = true) {
        val next = _selectedMediaIds.value.toMutableSet()
        if (select) next.addAll(ids) else next.removeAll(ids.toSet())
        _selectedMediaIds.value = next
    }

    fun clearSelection() {
        _selectedMediaIds.value = emptySet()
    }

    fun selectEntireGroup(groupKey: String, folderPath: String? = null) {
        viewModelScope.launch {
            val cfg = settings.value
            val spec = MediaQuerySpec(
                folderPath = folderPath,
                isUnlockedSessionForFolder = folderPath != null && securitySessionManager.isFolderUnlocked(folderPath),
                showHidden = cfg.showHiddenFiles,
                whitelistEnabled = cfg.whitelistModeEnabled,
                filters = _activeFilters.value,
                searchQuery = _searchQuery.value,
                sortCriterion = cfg.defaultSort,
                sortAscending = cfg.defaultSortAsc
            )
            val all = repository.queryMatchingMediaList(spec)
            val groupIds = all.filter {
                GalleryRepository.computeGroupInfo(it, cfg.groupMode, cfg.defaultSort).first == groupKey
            }.map { it.id }

            val current = _selectedMediaIds.value
            val allAlreadySelected = groupIds.isNotEmpty() && groupIds.all { it in current }
            selectMediaRange(groupIds, select = !allAlreadySelected)
        }
    }

    fun openFolder(path: String?) {
        _activeFolderPath.value = path
        clearSelection()
    }

    fun navigateFileTree(dirPath: String?) {
        _treeCurrentDir.value = dirPath
        if (dirPath != null) {
            viewModelScope.launch {
                val cfg = settings.value
                val showHidden = cfg.showHiddenFiles &&
                    (!cfg.protectHiddenWithBiometric || securitySessionManager.isHiddenSessionAuthorized.value)
                _treeNodes.value = fileManager.listDirectoryNodes(dirPath, showHidden)
            }
        } else {
            _treeNodes.value = emptyList()
        }
    }

    fun togglePersonFilter(personId: Long) {
        val list = _selectedPersonIds.value.toMutableList()
        if (personId in list) list.remove(personId) else list.add(personId)
        _selectedPersonIds.value = list
    }

    fun setSinglePersonView(personId: Long?) {
        _selectedPersonIds.value = if (personId == null) emptyList() else listOf(personId)
    }

    suspend fun prepareViewerFromCurrentContext(
        clickedMediaId: Long,
        folderPath: String? = null,
        personIds: List<Long> = emptyList(),
        favoritesOnly: Boolean = false
    ): Boolean {
        val cfg = settings.value
        val rule = folderPath?.let { p -> allFolderRules.value.firstOrNull { it.path == p } }
        val sort = rule?.sortCriterion?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
            ?: cfg.defaultSort
        val asc = rule?.sortAscending ?: cfg.defaultSortAsc
        val filters = if (favoritesOnly) setOf(QuickFilter.FAVORITES) else _activeFilters.value

        val spec = MediaQuerySpec(
            folderPath = folderPath,
            isUnlockedSessionForFolder = folderPath != null && securitySessionManager.isFolderUnlocked(folderPath),
            showHidden = cfg.showHiddenFiles || (folderPath != null && File(folderPath, ".nomedia").exists()),
            whitelistEnabled = cfg.whitelistModeEnabled && folderPath == null,
            filters = filters,
            searchQuery = if (folderPath == null && personIds.isEmpty() && !favoritesOnly) _searchQuery.value else "",
            personIds = personIds,
            sortCriterion = sort,
            sortAscending = asc,
            shuffleSeed = cfg.shuffleSeed,
            limit = 5000
        )
        val items = repository.queryMatchingMediaList(spec)
        if (items.isEmpty()) return false
        _viewerItems.value = items
        _viewerStartIndex.value = items.indexOfFirst { it.id == clickedMediaId }.coerceAtLeast(0)
        return true
    }

    suspend fun prepareViewerForVault(): Boolean {
        val vItems = vaultItems.value
        if (vItems.isEmpty()) return false
        _viewerItems.value = vItems.map { v ->
            MediaEntity(
                id = -v.vaultId,
                uri = vaultManager.buildVaultUri(v.encryptedFilePath),
                path = v.encryptedFilePath,
                parentPath = "Coffre-Fort AES-256",
                folderName = "Coffre-Fort",
                fileName = v.fileName,
                naturalSortKey = v.fileName,
                extension = v.fileName.substringAfterLast('.', ""),
                mimeType = v.mimeType,
                sizeBytes = v.sizeBytes,
                width = v.width,
                height = v.height,
                megapixels = (v.width.toLong() * v.height.toLong()) / 1_000_000f,
                durationMs = v.durationMs,
                dateTaken = v.dateTaken,
                dateModified = v.addedAt,
                dateAdded = v.addedAt,
                isVideo = v.isVideo,
                isRaw = false,
                isAnimated = v.fileName.endsWith(".gif", true),
                isScreenshot = false,
                isHidden = true,
                hasNoMediaParent = true,
                isLockedFolder = true
            )
        }
        _viewerStartIndex.value = 0
        return true
    }

    suspend fun prepareSlideshow(
        folderPath: String? = null,
        personIds: List<Long> = emptyList(),
        selectedIdsOnly: Boolean = false,
        favoritesOnly: Boolean = false
    ): Boolean {
        val list = if (selectedIdsOnly && _selectedMediaIds.value.isNotEmpty()) {
            mediaDao.getByIds(_selectedMediaIds.value.toList())
        } else {
            val cfg = settings.value
            repository.queryMatchingMediaList(
                MediaQuerySpec(
                    folderPath = folderPath,
                    isUnlockedSessionForFolder = folderPath != null && securitySessionManager.isFolderUnlocked(folderPath),
                    showHidden = cfg.showHiddenFiles,
                    whitelistEnabled = cfg.whitelistModeEnabled && folderPath == null,
                    filters = if (favoritesOnly) setOf(QuickFilter.FAVORITES) else _activeFilters.value,
                    personIds = personIds,
                    sortCriterion = cfg.defaultSort,
                    sortAscending = cfg.defaultSortAsc,
                    limit = 3000
                )
            )
        }
        if (list.isEmpty()) {
            emitMessage("Aucun média à afficher dans le diaporama")
            return false
        }
        _viewerItems.value = list
        _viewerStartIndex.value = 0
        return true
    }

    fun updateSlideshowConfig(config: SlideshowConfig) {
        _slideshowConfig.value = config
    }

    fun observeFacesForMedia(mediaId: Long): Flow<List<FaceEntity>> =
        faceDao.observeFacesForMedia(mediaId)

    suspend fun getFacesForMediaOnce(mediaId: Long): List<FaceEntity> =
        faceDao.getFacesForMedia(mediaId)

    fun observeFacesForPerson(personId: Long): Flow<List<FaceEntity>> =
        faceDao.observeFacesForPerson(personId)

    // Batch & Single Media Actions
    fun toggleFavorite(mediaIds: List<Long>, favorite: Boolean) {
        viewModelScope.launch {
            mediaDao.setFavorite(mediaIds, favorite)
            emitMessage(if (favorite) "Ajouté aux Favoris ❤️" else "Retiré des Favoris")
        }
    }

    fun moveSelectedToTrash(ids: List<Long> = _selectedMediaIds.value.toList()) {
        viewModelScope.launch {
            val count = fileManager.moveToTrash(ids)
            clearSelection()
            emitMessage("$count élément(s) déplacé(s) vers la Corbeille (30 j)")
        }
    }

    fun deleteSelectedPermanently(ids: List<Long> = _selectedMediaIds.value.toList()) {
        viewModelScope.launch {
            val count = fileManager.deletePermanently(ids)
            clearSelection()
            emitMessage("$count élément(s) supprimé(s) définitivement")
        }
    }

    fun moveSelectedToVault(ids: List<Long> = _selectedMediaIds.value.toList()) {
        viewModelScope.launch {
            val count = vaultManager.moveMediaToVault(ids)
            clearSelection()
            emitMessage("$count élément(s) chiffré(s) en AES-256-GCM dans le Coffre-Fort")
        }
    }

    fun restoreVaultItem(vaultIds: List<Long>) {
        viewModelScope.launch {
            val count = vaultManager.restoreFromVault(vaultIds)
            emitMessage("$count élément(s) déchiffré(s) et restauré(s)")
        }
    }

    fun deleteVaultItems(vaultIds: List<Long>) {
        viewModelScope.launch {
            vaultManager.deleteVaultItemsPermanently(vaultIds)
            emitMessage("Supprimé définitivement du Coffre-Fort")
        }
    }

    fun restoreTrashItems(trashIds: List<Long>) {
        viewModelScope.launch {
            val count = fileManager.restoreFromTrash(trashIds)
            emitMessage("$count élément(s) restauré(s) depuis la Corbeille")
        }
    }

    fun emptyTrash(trashIds: List<Long>) {
        viewModelScope.launch {
            fileManager.emptyTrashItems(trashIds)
            emitMessage("Corbeille vidée")
        }
    }

    fun copyOrMoveSelected(targetFolderPath: String, move: Boolean) {
        val ids = _selectedMediaIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val count = fileManager.copyOrMoveMedia(ids, targetFolderPath, move)
            clearSelection()
            emitMessage(if (move) "$count élément(s) déplacé(s)" else "$count élément(s) copié(s)")
        }
    }

    fun batchRenameSelected(pattern: String) {
        val ids = _selectedMediaIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val count = fileManager.batchPatternRename(ids, pattern)
            clearSelection()
            emitMessage("$count fichier(s) renommé(s)")
        }
    }

    fun shareSelected(activityContext: Context, explicitMedia: List<MediaEntity>? = null) {
        viewModelScope.launch {
            val items = explicitMedia ?: mediaDao.getByIds(_selectedMediaIds.value.toList())
            fileManager.shareMedia(activityContext, items)
        }
    }

    fun stripExif(media: MediaEntity) {
        viewModelScope.launch {
            val ok = fileManager.stripExifMetadata(media)
            emitMessage(if (ok) "Métadonnées EXIF & GPS nettoyées !" else "Impossible de nettoyer les EXIF")
        }
    }

    // Folder Management
    fun togglePinFolder(path: String) {
        viewModelScope.launch { repository.togglePinFolder(path) }
    }

    fun setFolderLocked(path: String, locked: Boolean) {
        viewModelScope.launch {
            repository.setFolderLocked(path, locked)
            if (!locked) {
                emitMessage("Dossier déverrouillé publiquement")
            } else {
                emitMessage("Dossier verrouillé par empreinte biométrique")
            }
        }
    }

    fun setFolderExclusion(path: String, mode: FolderExclusionMode) {
        viewModelScope.launch {
            repository.setFolderExclusionMode(path, mode)
            emitMessage("Règle appliquée : ${mode.label}")
        }
    }

    fun setFolderExcludeFaceAi(path: String, exclude: Boolean) {
        viewModelScope.launch {
            repository.setFolderExcludeFromFaceAi(path, exclude)
            emitMessage(if (exclude) "Dossier exclu du scan IA des visages" else "Dossier réintégré au scan IA")
        }
    }

    fun setFolderCustomSort(path: String, criterion: SortCriterion?, ascending: Boolean?) {
        viewModelScope.launch {
            repository.setFolderCustomSort(path, criterion, ascending)
        }
    }

    fun toggleFolderNoMedia(path: String) {
        viewModelScope.launch {
            val created = fileManager.toggleNoMedia(path)
            emitMessage(
                if (created) "Fichier .nomedia créé (masqué aux autres apps)"
                else "Fichier .nomedia supprimé (visible aux autres apps)"
            )
            _treeCurrentDir.value?.let { navigateFileTree(it) }
        }
    }

    fun createNewFolder(parentPath: String, name: String) {
        viewModelScope.launch {
            val dir = fileManager.createNewFolder(parentPath, name)
            if (dir != null) {
                emitMessage("Dossier '${dir.name}' créé")
                _treeCurrentDir.value?.let { navigateFileTree(it) }
            } else {
                emitMessage("Impossible de créer le dossier")
            }
        }
    }

    // People AI Curation
    fun renamePerson(personId: Long, newName: String) {
        viewModelScope.launch {
            faceDao.updatePersonName(personId, newName.trim())
            emitMessage("Nom enregistré : ${newName.trim()}")
        }
    }

    fun mergePersons(personIds: List<Long>) {
        viewModelScope.launch {
            clusterEngine.mergePersons(personIds)
            _selectedPersonIds.value = emptyList()
            emitMessage("Visages fusionnés dans la même personne")
        }
    }

    fun detachSelectedMediaFromPerson(personId: Long, mediaIds: List<Long>) {
        viewModelScope.launch {
            clusterEngine.removeMediaFromPerson(mediaIds, personId)
            clearSelection()
            emitMessage("Retiré de cette personne (contrainte cannot_link enregistrée)")
        }
    }

    fun setPersonCover(personId: Long, face: FaceEntity) {
        viewModelScope.launch {
            faceDao.setPersonCover(personId, face.faceId, face.cropPath)
            emitMessage("Photo de profil mise à jour")
        }
    }

    fun setPersonHidden(personId: Long, hidden: Boolean) {
        viewModelScope.launch {
            faceDao.setPersonHidden(personId, hidden)
        }
    }

    // Duplicate Detection
    fun runDuplicateScan() {
        viewModelScope.launch {
            _isScanningDuplicates.value = true
            try {
                _duplicateGroups.value = duplicateDetector.findDuplicateGroups(
                    maxHammingDistance = 5,
                    includeHidden = settings.value.showHiddenFiles
                )
            } finally {
                _isScanningDuplicates.value = false
            }
        }
    }

    fun cleanDuplicatesKeepBest(group: DuplicateGroup) {
        viewModelScope.launch {
            val toTrash = group.items.filter { it.id != group.recommendedKeepId }.map { it.id }
            val count = fileManager.moveToTrash(toTrash)
            _duplicateGroups.value = _duplicateGroups.value.filter { it.groupId != group.groupId }
            emitMessage("$count doublon(s) déplacé(s) vers la Corbeille (meilleure résolution conservée)")
        }
    }

    // Backup & Restore JSON
    fun exportBackupJson(uri: Uri) {
        viewModelScope.launch {
            val ok = backupManager.exportToUri(uri)
            emitMessage(if (ok) "Sauvegarde JSON exportée avec succès" else "Échec de l'export JSON")
        }
    }

    fun importBackupJson(uri: Uri) {
        viewModelScope.launch {
            val ok = backupManager.importFromUri(uri)
            emitMessage(if (ok) "Préférences et visages restaurés depuis le JSON" else "Fichier JSON invalide")
        }
    }

    // Theme & Settings wrappers
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.updateTheme(mode) }
    }

    fun setDynamicColors(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateDynamicColors(enabled) }
    }

    fun setGridColumns(cols: Int) {
        viewModelScope.launch { settingsRepository.updateGridColumns(cols) }
    }

    fun setListViewMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateListViewMode(enabled) }
    }

    fun setDefaultSort(criterion: SortCriterion, ascending: Boolean) {
        viewModelScope.launch { settingsRepository.updateDefaultSort(criterion, ascending) }
    }

    fun reshuffleTimeline() {
        viewModelScope.launch { settingsRepository.reshuffle() }
    }

    fun setGroupMode(mode: GroupMode) {
        viewModelScope.launch { settingsRepository.updateGroupMode(mode) }
    }

    fun emitMessage(msg: String) {
        _snackbarMessage.tryEmit(msg)
    }
}
