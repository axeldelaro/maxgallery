package com.hypergallery.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.MediaQueryBuilder
import com.hypergallery.data.local.dao.MediaQuerySpec
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.domain.model.FolderExclusionMode
import com.hypergallery.domain.model.FolderSummary
import com.hypergallery.domain.model.GroupMode
import com.hypergallery.domain.model.SortCriterion
import com.hypergallery.util.NaturalSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TimelineGridItem {
    val stableKey: String
    val contentType: Int

    data class SectionHeader(
        val groupKey: String,
        val title: String,
        val representativeTimestamp: Long
    ) : TimelineGridItem {
        override val stableKey: String = "hdr_$groupKey"
        override val contentType: Int = 0
    }

    data class MediaCell(
        val media: MediaEntity,
        val groupKey: String
    ) : TimelineGridItem {
        override val stableKey: String = "med_${media.id}"
        override val contentType: Int = 1
    }
}

@Singleton
class GalleryRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val folderRuleDao: FolderRuleDao
) {
    fun pagedGroupedMedia(
        spec: MediaQuerySpec,
        groupMode: GroupMode
    ): Flow<PagingData<TimelineGridItem>> {
        val sql = MediaQueryBuilder.build(spec)
        return Pager(
            config = PagingConfig(
                pageSize = 90,
                prefetchDistance = 45,
                enablePlaceholders = false,
                initialLoadSize = 150,
                maxSize = 600
            ),
            pagingSourceFactory = { mediaDao.pagingMedia(sql) }
        ).flow.map { pagingData ->
            val effectiveGroup = if (spec.sortCriterion in setOf(
                    SortCriterion.DATE_TAKEN,
                    SortCriterion.DATE_MODIFIED,
                    SortCriterion.DATE_ADDED
                ) || groupMode == GroupMode.FOLDER || groupMode == GroupMode.NONE
            ) {
                groupMode
            } else {
                GroupMode.NONE
            }

            pagingData
                .map { media ->
                    val (gKey, _) = computeGroupInfo(media, effectiveGroup, spec.sortCriterion)
                    TimelineGridItem.MediaCell(media, gKey)
                }
                .insertSeparators { before: TimelineGridItem.MediaCell?, after: TimelineGridItem.MediaCell? ->
                    if (effectiveGroup == GroupMode.NONE || after == null) {
                        null
                    } else if (before == null || before.groupKey != after.groupKey) {
                        val (_, title) = computeGroupInfo(after.media, effectiveGroup, spec.sortCriterion)
                        TimelineGridItem.SectionHeader(
                            groupKey = after.groupKey,
                            title = title,
                            representativeTimestamp = after.media.dateTaken
                        )
                    } else {
                        null
                    }
                }
        }
    }

    suspend fun queryMatchingMediaList(spec: MediaQuerySpec): List<MediaEntity> {
        return mediaDao.queryMediaList(MediaQueryBuilder.build(spec))
    }

    fun observeMatchingMediaList(spec: MediaQuerySpec): Flow<List<MediaEntity>> {
        return mediaDao.observeMediaList(MediaQueryBuilder.build(spec))
    }

    fun observeMemoriesToday(onlyHidden: Boolean = true): Flow<List<MediaEntity>> {
        val cal = Calendar.getInstance()
        val monthDay = String.format(
            Locale.US,
            "%02d-%02d",
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val year = cal.get(Calendar.YEAR)
        return mediaDao.observeMemoriesForDay(monthDay, year, onlyHidden)
    }

    fun observeFolders(
        showHidden: Boolean,
        onlyHiddenMode: Boolean,
        whitelistEnabled: Boolean,
        sortCriterion: SortCriterion,
        ascending: Boolean
    ): Flow<List<FolderSummary>> {
        return combine(
            mediaDao.observeFolderAggregations(),
            folderRuleDao.observeAllRules()
        ) { rows, rules ->
            val ruleMap = rules.associateBy { it.path }
            val filtered = rows.mapNotNull { row ->
                val rule = ruleMap[row.parentPath]
                val mode = rule?.exclusionMode?.let {
                    runCatching { FolderExclusionMode.valueOf(it) }.getOrDefault(FolderExclusionMode.NONE)
                } ?: FolderExclusionMode.NONE

                if (mode == FolderExclusionMode.EXCLUDE_COMPLETELY) return@mapNotNull null
                if (whitelistEnabled && mode != FolderExclusionMode.WHITELIST_INCLUDED) return@mapNotNull null
                val isMaskedFolder = row.isHidden || row.hasNoMediaParent
                if (onlyHiddenMode && !isMaskedFolder) return@mapNotNull null
                if (!onlyHiddenMode && !showHidden && isMaskedFolder) return@mapNotNull null

                FolderSummary(
                    path = row.parentPath,
                    name = row.folderName,
                    coverPath = row.coverPath,
                    coverUri = row.coverUri,
                    mediaCount = row.mediaCount,
                    totalSizeBytes = row.totalSizeBytes,
                    lastModified = row.lastModified,
                    hasNoMedia = row.hasNoMediaParent,
                    isHiddenDot = row.isHidden,
                    isPinned = rule?.isPinned ?: false,
                    isLocked = rule?.isLocked ?: false,
                    exclusionMode = mode,
                    excludeFromFaceAi = rule?.excludeFromFaceAi ?: false,
                    sortCriterion = rule?.sortCriterion?.let { runCatching { SortCriterion.valueOf(it) }.getOrNull() },
                    sortAscending = rule?.sortAscending
                )
            }

            val baseComparator = when (sortCriterion) {
                SortCriterion.NAME_NATURAL -> Comparator<FolderSummary> { a, b ->
                    NaturalSort.comparator.compare(a.name, b.name)
                }
                SortCriterion.FILE_SIZE -> compareBy { it.totalSizeBytes }
                SortCriterion.DATE_MODIFIED, SortCriterion.DATE_TAKEN, SortCriterion.DATE_ADDED -> compareBy { it.lastModified }
                else -> compareBy { it.mediaCount }
            }
            val directed = if (ascending) baseComparator else baseComparator.reversed()

            filtered.sortedWith(
                compareByDescending<FolderSummary> { it.isPinned }.then(directed)
            )
        }
    }

    suspend fun togglePinFolder(path: String) {
        val current = folderRuleDao.getRule(path) ?: FolderRuleEntity(path = path)
        folderRuleDao.upsertRule(current.copy(isPinned = !current.isPinned))
    }

    suspend fun setFolderLocked(path: String, locked: Boolean) {
        val current = folderRuleDao.getRule(path) ?: FolderRuleEntity(path = path)
        folderRuleDao.upsertRule(current.copy(isLocked = locked))
        mediaDao.setFolderLockedFlag(path, locked)
    }

    suspend fun setFolderExclusionMode(path: String, mode: FolderExclusionMode) {
        val current = folderRuleDao.getRule(path) ?: FolderRuleEntity(path = path)
        folderRuleDao.upsertRule(current.copy(exclusionMode = mode.name))
    }

    suspend fun setFolderExcludeFromFaceAi(path: String, exclude: Boolean) {
        val current = folderRuleDao.getRule(path) ?: FolderRuleEntity(path = path)
        folderRuleDao.upsertRule(current.copy(excludeFromFaceAi = exclude))
    }

    suspend fun setFolderCustomSort(path: String, criterion: SortCriterion?, ascending: Boolean?) {
        val current = folderRuleDao.getRule(path) ?: FolderRuleEntity(path = path)
        folderRuleDao.upsertRule(
            current.copy(
                sortCriterion = criterion?.name,
                sortAscending = ascending
            )
        )
    }

    companion object {
        fun computeGroupInfo(
            media: MediaEntity,
            groupMode: GroupMode,
            sortCriterion: SortCriterion
        ): Pair<String, String> {
            val ts = when (sortCriterion) {
                SortCriterion.DATE_MODIFIED -> media.dateModified
                SortCriterion.DATE_ADDED -> media.dateAdded
                else -> media.dateTaken
            }
            val date = Date(ts)
            return when (groupMode) {
                GroupMode.DAY -> {
                    val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                    val title = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE)
                        .format(date)
                        .replaceFirstChar { it.uppercase(Locale.FRANCE) }
                    key to title
                }
                GroupMode.MONTH -> {
                    val key = SimpleDateFormat("yyyy-MM", Locale.US).format(date)
                    val title = SimpleDateFormat("MMMM yyyy", Locale.FRANCE)
                        .format(date)
                        .replaceFirstChar { it.uppercase(Locale.FRANCE) }
                    key to title
                }
                GroupMode.YEAR -> {
                    val key = SimpleDateFormat("yyyy", Locale.US).format(date)
                    key to key
                }
                GroupMode.FOLDER -> {
                    media.parentPath to media.folderName
                }
                GroupMode.NONE -> "" to ""
            }
        }
    }
}
