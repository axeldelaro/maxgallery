package com.hypergallery.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hypergallery.domain.model.AppSettings
import com.hypergallery.domain.model.GroupMode
import com.hypergallery.domain.model.SortCriterion
import com.hypergallery.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hypergallery_prefs")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val LIST_VIEW_MODE = booleanPreferencesKey("list_view_mode")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val DEFAULT_SORT_ASC = booleanPreferencesKey("default_sort_asc")
        val FOLDER_SORT = stringPreferencesKey("folder_sort")
        val FOLDER_SORT_ASC = booleanPreferencesKey("folder_sort_asc")
        val GROUP_MODE = stringPreferencesKey("group_mode")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val ONLY_HIDDEN_MODE = booleanPreferencesKey("only_hidden_media_mode")
        val DEEP_SCAN_DISGUISED = booleanPreferencesKey("deep_scan_disguised_media")
        val PROTECT_HIDDEN_BIO = booleanPreferencesKey("protect_hidden_bio")
        val WHITELIST_ENABLED = booleanPreferencesKey("whitelist_enabled")
        val MEMORIES_ENABLED = booleanPreferencesKey("memories_enabled")
        val FACE_AI_ENABLED = booleanPreferencesKey("face_ai_enabled")
        val FACE_THRESHOLD = floatPreferencesKey("face_threshold")
        val FACE_BATTERY_GUARD = booleanPreferencesKey("face_battery_guard")
        val FACE_THERMAL_GUARD = booleanPreferencesKey("face_thermal_guard")
        val SHUFFLE_SEED = intPreferencesKey("shuffle_seed")
        val PIN_HASH = stringPreferencesKey("security_pin_hash")
        val PIN_SALT = stringPreferencesKey("security_pin_salt")
        val FACE_WORKER_PAUSED = booleanPreferencesKey("face_worker_paused")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.AMOLED_BLACK,
            dynamicColors = prefs[Keys.DYNAMIC_COLORS] ?: false,
            gridColumns = (prefs[Keys.GRID_COLUMNS] ?: 4).coerceIn(1, 8),
            listViewMode = prefs[Keys.LIST_VIEW_MODE] ?: false,
            defaultSort = prefs[Keys.DEFAULT_SORT]?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
                ?: SortCriterion.DATE_TAKEN,
            defaultSortAsc = prefs[Keys.DEFAULT_SORT_ASC] ?: false,
            folderListSort = prefs[Keys.FOLDER_SORT]?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() }
                ?: SortCriterion.ITEM_COUNT,
            folderListSortAsc = prefs[Keys.FOLDER_SORT_ASC] ?: false,
            groupMode = prefs[Keys.GROUP_MODE]?.let { runCatching { GroupMode.valueOf(it) }.getOrNull() }
                ?: GroupMode.DAY,
            showHiddenFiles = prefs[Keys.SHOW_HIDDEN] ?: true,
            onlyHiddenMediaMode = prefs[Keys.ONLY_HIDDEN_MODE] ?: true,
            deepScanDisguisedMedia = prefs[Keys.DEEP_SCAN_DISGUISED] ?: true,
            protectHiddenWithBiometric = prefs[Keys.PROTECT_HIDDEN_BIO] ?: false,
            whitelistModeEnabled = prefs[Keys.WHITELIST_ENABLED] ?: false,
            memoriesEnabled = prefs[Keys.MEMORIES_ENABLED] ?: true,
            faceAiEnabled = prefs[Keys.FACE_AI_ENABLED] ?: true,
            faceClusterThreshold = (prefs[Keys.FACE_THRESHOLD] ?: 0.40f).coerceIn(0.20f, 0.65f),
            faceScanOnlyChargingOrHighBattery = prefs[Keys.FACE_BATTERY_GUARD] ?: false,
            faceScanThermalProtection = prefs[Keys.FACE_THERMAL_GUARD] ?: true,
            shuffleSeed = prefs[Keys.SHUFFLE_SEED] ?: 42_1337
        )
    }

    val isFaceWorkerPausedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FACE_WORKER_PAUSED] ?: false
    }

    suspend fun updateTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateDynamicColors(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLORS] = enabled }
    }

    suspend fun updateGridColumns(columns: Int) {
        context.dataStore.edit { it[Keys.GRID_COLUMNS] = columns.coerceIn(1, 8) }
    }

    suspend fun updateListViewMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIST_VIEW_MODE] = enabled }
    }

    suspend fun updateDefaultSort(criterion: SortCriterion, ascending: Boolean) {
        context.dataStore.edit {
            it[Keys.DEFAULT_SORT] = criterion.name
            it[Keys.DEFAULT_SORT_ASC] = ascending
            if (criterion == SortCriterion.SHUFFLE) {
                it[Keys.SHUFFLE_SEED] = Random.nextInt(10_000, 999_999_999)
            }
        }
    }

    suspend fun reshuffle() {
        context.dataStore.edit {
            it[Keys.SHUFFLE_SEED] = Random.nextInt(10_000, 999_999_999)
        }
    }

    suspend fun updateFolderSort(criterion: SortCriterion, ascending: Boolean) {
        context.dataStore.edit {
            it[Keys.FOLDER_SORT] = criterion.name
            it[Keys.FOLDER_SORT_ASC] = ascending
        }
    }

    suspend fun updateGroupMode(mode: GroupMode) {
        context.dataStore.edit { it[Keys.GROUP_MODE] = mode.name }
    }

    suspend fun updateShowHidden(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_HIDDEN] = show }
    }

    suspend fun updateOnlyHiddenMediaMode(onlyHidden: Boolean) {
        context.dataStore.edit {
            it[Keys.ONLY_HIDDEN_MODE] = onlyHidden
            if (onlyHidden) {
                it[Keys.SHOW_HIDDEN] = true
            }
        }
    }

    suspend fun updateDeepScanDisguisedMedia(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEEP_SCAN_DISGUISED] = enabled }
    }

    suspend fun updateProtectHiddenBiometric(protect: Boolean) {
        context.dataStore.edit { it[Keys.PROTECT_HIDDEN_BIO] = protect }
    }

    suspend fun updateWhitelistMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WHITELIST_ENABLED] = enabled }
    }

    suspend fun updateMemoriesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MEMORIES_ENABLED] = enabled }
    }

    suspend fun updateFaceAiSettings(
        enabled: Boolean? = null,
        threshold: Float? = null,
        batteryGuard: Boolean? = null,
        thermalGuard: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            enabled?.let { prefs[Keys.FACE_AI_ENABLED] = it }
            threshold?.let { prefs[Keys.FACE_THRESHOLD] = it.coerceIn(0.20f, 0.65f) }
            batteryGuard?.let { prefs[Keys.FACE_BATTERY_GUARD] = it }
            thermalGuard?.let { prefs[Keys.FACE_THERMAL_GUARD] = it }
        }
    }

    suspend fun setFaceWorkerPaused(paused: Boolean) {
        context.dataStore.edit { it[Keys.FACE_WORKER_PAUSED] = paused }
    }

    suspend fun restoreSettings(settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = settings.themeMode.name
            prefs[Keys.DYNAMIC_COLORS] = settings.dynamicColors
            prefs[Keys.GRID_COLUMNS] = settings.gridColumns.coerceIn(1, 8)
            prefs[Keys.LIST_VIEW_MODE] = settings.listViewMode
            prefs[Keys.DEFAULT_SORT] = settings.defaultSort.name
            prefs[Keys.DEFAULT_SORT_ASC] = settings.defaultSortAsc
            prefs[Keys.FOLDER_SORT] = settings.folderListSort.name
            prefs[Keys.FOLDER_SORT_ASC] = settings.folderListSortAsc
            prefs[Keys.GROUP_MODE] = settings.groupMode.name
            prefs[Keys.SHOW_HIDDEN] = settings.showHiddenFiles
            prefs[Keys.ONLY_HIDDEN_MODE] = settings.onlyHiddenMediaMode
            prefs[Keys.DEEP_SCAN_DISGUISED] = settings.deepScanDisguisedMedia
            prefs[Keys.PROTECT_HIDDEN_BIO] = settings.protectHiddenWithBiometric
            prefs[Keys.WHITELIST_ENABLED] = settings.whitelistModeEnabled
            prefs[Keys.MEMORIES_ENABLED] = settings.memoriesEnabled
            prefs[Keys.FACE_AI_ENABLED] = settings.faceAiEnabled
            prefs[Keys.FACE_THRESHOLD] = settings.faceClusterThreshold
            prefs[Keys.FACE_BATTERY_GUARD] = settings.faceScanOnlyChargingOrHighBattery
            prefs[Keys.FACE_THERMAL_GUARD] = settings.faceScanThermalProtection
        }
    }

    suspend fun savePinCredentials(hashBase64: String, saltBase64: String) {
        context.dataStore.edit {
            it[Keys.PIN_HASH] = hashBase64
            it[Keys.PIN_SALT] = saltBase64
        }
    }

    suspend fun getPinCredentials(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val hash = prefs[Keys.PIN_HASH] ?: return null
        val salt = prefs[Keys.PIN_SALT] ?: return null
        return hash to salt
    }

    suspend fun hasPinConfigured(): Boolean = getPinCredentials() != null
}
