package com.hypergallery.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode(val label: String) {
    AMOLED_BLACK("Noir Pur AMOLED (#000000)"),
    SLATE_DARK("Sombre Ardoise"),
    LIGHT("Clair"),
    SYSTEM("Système")
}

@Serializable
enum class SortCriterion(val label: String) {
    DATE_TAKEN("Date de prise de vue (EXIF)"),
    DATE_MODIFIED("Date de modification"),
    DATE_ADDED("Date d'ajout"),
    NAME_NATURAL("Nom (Tri naturel A-Z)"),
    FILE_SIZE("Taille du fichier"),
    MEGAPIXELS("Résolution / Mégapixels"),
    DURATION("Durée vidéo"),
    EXTENSION("Format / Extension"),
    ITEM_COUNT("Nombre d'éléments"),
    SHUFFLE("Ordre aléatoire (Shuffle)")
}

@Serializable
enum class GroupMode(val label: String) {
    DAY("Par Jour"),
    MONTH("Par Mois"),
    YEAR("Par Année"),
    FOLDER("Par Dossier"),
    NONE("Sans regroupement")
}

@Serializable
enum class QuickFilter(val label: String) {
    PHOTOS("Photos"),
    VIDEOS("Vidéos"),
    FAVORITES("Favoris ❤️"),
    RAW("Fichiers RAW"),
    ANIMATED("GIFs & WebP animés"),
    SCREENSHOTS("Captures d'écran"),
    LARGE_FILES("Gros fichiers (> 50 Mo)")
}

@Serializable
enum class FolderExclusionMode(val label: String) {
    NONE("Inclus normalement"),
    EXCLUDE_TIMELINE("Exclu de la Chronologie uniquement"),
    EXCLUDE_COMPLETELY("Exclu totalement de l'application"),
    WHITELIST_INCLUDED("Inclus dans la Liste Blanche")
}

@Serializable
enum class SlideshowTransition(val label: String) {
    KEN_BURNS("Ken Burns (Zoom centré visages IA)"),
    CROSSFADE("Fondu enchaîné doux"),
    SLIDE("Glissement latéral"),
    CUBE_3D("Rotation Cube 3D"),
    INSTANT_CUT("Coupure franche")
}

@Serializable
data class SlideshowConfig(
    val intervalMs: Long = 4_000L,
    val transition: SlideshowTransition = SlideshowTransition.KEN_BURNS,
    val shuffle: Boolean = false,
    val loop: Boolean = true,
    val includeVideos: Boolean = true,
    val muteVideos: Boolean = false
)

enum class VideoAspectMode(val label: String) {
    FIT("Adapter"),
    FILL_CROP("Remplir / Recadrer"),
    STRETCH("Étirer")
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.AMOLED_BLACK,
    val dynamicColors: Boolean = false,
    val gridColumns: Int = 4,
    val listViewMode: Boolean = false,
    val defaultSort: SortCriterion = SortCriterion.DATE_TAKEN,
    val defaultSortAsc: Boolean = false,
    val folderListSort: SortCriterion = SortCriterion.ITEM_COUNT,
    val folderListSortAsc: Boolean = false,
    val groupMode: GroupMode = GroupMode.DAY,
    val showHiddenFiles: Boolean = false,
    val protectHiddenWithBiometric: Boolean = false,
    val whitelistModeEnabled: Boolean = false,
    val memoriesEnabled: Boolean = true,
    val faceAiEnabled: Boolean = true,
    val faceClusterThreshold: Float = 0.40f,
    val faceScanOnlyChargingOrHighBattery: Boolean = true,
    val faceScanThermalProtection: Boolean = true,
    val shuffleSeed: Int = 42_1337
)

data class FolderSummary(
    val path: String,
    val name: String,
    val coverPath: String?,
    val coverUri: String?,
    val mediaCount: Int,
    val totalSizeBytes: Long,
    val lastModified: Long,
    val hasNoMedia: Boolean,
    val isHiddenDot: Boolean,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val exclusionMode: FolderExclusionMode,
    val excludeFromFaceAi: Boolean,
    val sortCriterion: SortCriterion?,
    val sortAscending: Boolean?
)

data class FileTreeNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isHidden: Boolean,
    val hasNoMedia: Boolean,
    val isLocked: Boolean,
    val isPinned: Boolean,
    val directMediaCount: Int,
    val subfolderCount: Int,
    val sizeBytes: Long,
    val lastModified: Long
)

data class DuplicateGroup(
    val groupId: Int,
    val anchorHash: Long,
    val items: List<DuplicateMediaItem>,
    val recommendedKeepId: Long,
    val reclaimableBytes: Long
)

data class DuplicateMediaItem(
    val id: Long,
    val path: String,
    val uri: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateTaken: Long,
    val hammingDistance: Int
)

data class FaceIndexStatus(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val detectedFacesCount: Int = 0,
    val statusNote: String = "Prêt"
)

@Serializable
data class BackupFolderRule(
    val path: String,
    val isPinned: Boolean,
    val isLocked: Boolean,
    val exclusionMode: FolderExclusionMode,
    val excludeFromFaceAi: Boolean,
    val sortCriterion: SortCriterion? = null,
    val sortAscending: Boolean? = null
)

@Serializable
data class BackupPersonLabel(
    val name: String,
    val isHidden: Boolean,
    val centroidFloats: List<Float>
)

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportedAt: Long,
    val settings: AppSettings,
    val folderRules: List<BackupFolderRule>,
    val favoritePaths: List<String>,
    val personLabels: List<BackupPersonLabel>
)
