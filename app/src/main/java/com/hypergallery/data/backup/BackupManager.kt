package com.hypergallery.data.backup

import android.content.Context
import android.net.Uri
import com.hypergallery.ai.FaceEmbedder
import com.hypergallery.data.local.dao.FaceDao
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.preferences.SettingsRepository
import com.hypergallery.domain.model.BackupFolderRule
import com.hypergallery.domain.model.BackupPayload
import com.hypergallery.domain.model.BackupPersonLabel
import com.hypergallery.domain.model.FolderExclusionMode
import com.hypergallery.domain.model.SortCriterion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val folderRuleDao: FolderRuleDao,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val json: Json
) {
    suspend fun exportToUri(targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsRepository.settingsFlow.first()
            val rules = folderRuleDao.getAllRules().map { rule ->
                BackupFolderRule(
                    path = rule.path,
                    isPinned = rule.isPinned,
                    isLocked = rule.isLocked,
                    exclusionMode = runCatching { FolderExclusionMode.valueOf(rule.exclusionMode) }
                        .getOrDefault(FolderExclusionMode.NONE),
                    excludeFromFaceAi = rule.excludeFromFaceAi,
                    sortCriterion = rule.sortCriterion?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() },
                    sortAscending = rule.sortAscending
                )
            }
            val favorites = mediaDao.getFavoritePaths()
            val persons = faceDao.getAllPersons()
                .filter { it.name.isNotBlank() || it.isHidden }
                .map { person ->
                    BackupPersonLabel(
                        name = person.name,
                        isHidden = person.isHidden,
                        centroidFloats = FaceEmbedder.blobToFloatArray(person.centroidBlob).toList()
                    )
                }

            val payload = BackupPayload(
                exportedAt = System.currentTimeMillis(),
                settings = settings,
                folderRules = rules,
                favoritePaths = favorites,
                personLabels = persons
            )
            val serialized = json.encodeToString(payload)
            context.contentResolver.openOutputStream(targetUri)?.bufferedWriter()?.use { writer ->
                writer.write(serialized)
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    suspend fun importFromUri(sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(sourceUri)?.bufferedReader()?.use {
                it.readText()
            } ?: return@runCatching false
            val payload = json.decodeFromString<BackupPayload>(raw)

            settingsRepository.restoreSettings(payload.settings)

            val ruleEntities = payload.folderRules.map { r ->
                FolderRuleEntity(
                    path = r.path,
                    isPinned = r.isPinned,
                    isLocked = r.isLocked,
                    exclusionMode = r.exclusionMode.name,
                    excludeFromFaceAi = r.excludeFromFaceAi,
                    sortCriterion = r.sortCriterion?.name,
                    sortAscending = r.sortAscending
                )
            }
            folderRuleDao.upsertRules(ruleEntities)
            for (r in ruleEntities) {
                mediaDao.setFolderLockedFlag(r.path, r.isLocked)
            }

            if (payload.favoritePaths.isNotEmpty()) {
                payload.favoritePaths.chunked(300).forEach { chunk ->
                    mediaDao.restoreFavoritesByPaths(chunk)
                }
            }

            // Match person centroids by cosine distance and restore names
            val currentPersons = faceDao.getAllPersons()
            for (saved in payload.personLabels) {
                if (saved.centroidFloats.isEmpty()) continue
                val refVec = FaceEmbedder.l2Normalize(saved.centroidFloats.toFloatArray())
                val bestMatch = currentPersons
                    .map { p -> p to FaceEmbedder.cosineDistance(refVec, FaceEmbedder.blobToFloatArray(p.centroidBlob)) }
                    .filter { it.second <= 0.42f }
                    .minByOrNull { it.second }
                    ?.first
                if (bestMatch != null) {
                    if (saved.name.isNotBlank()) {
                        faceDao.updatePersonName(bestMatch.personId, saved.name)
                    }
                    faceDao.setPersonHidden(bestMatch.personId, saved.isHidden)
                }
            }
            true
        }.getOrDefault(false)
    }
}
