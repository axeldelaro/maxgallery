package com.hypergallery.data.local.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.hypergallery.domain.model.QuickFilter
import com.hypergallery.domain.model.SortCriterion

data class MediaQuerySpec(
    val folderPath: String? = null,
    val isUnlockedSessionForFolder: Boolean = false,
    val showHidden: Boolean = false,
    val whitelistEnabled: Boolean = false,
    val filters: Set<QuickFilter> = emptySet(),
    val searchQuery: String = "",
    val personIds: List<Long> = emptyList(),
    val sortCriterion: SortCriterion = SortCriterion.DATE_TAKEN,
    val sortAscending: Boolean = false,
    val shuffleSeed: Int = 42_1337,
    val limit: Int? = null
)

object MediaQueryBuilder {

    fun build(spec: MediaQuerySpec): SupportSQLiteQuery {
        val sb = StringBuilder()
        val args = mutableListOf<Any>()

        sb.append(
            """
            SELECT m.* FROM media_items m
            LEFT JOIN folder_rules r ON m.parentPath = r.path
            WHERE 1 = 1
            """.trimIndent()
        )

        if (spec.folderPath != null) {
            sb.append(" AND m.parentPath = ?")
            args.add(spec.folderPath)
            if (!spec.isUnlockedSessionForFolder) {
                sb.append(" AND m.isLockedFolder = 0 AND COALESCE(r.isLocked, 0) = 0")
            }
        } else {
            // Public timeline / search / person filter: hide locked & excluded folders
            sb.append(" AND m.isLockedFolder = 0 AND COALESCE(r.isLocked, 0) = 0")
            sb.append(" AND COALESCE(r.exclusionMode, 'NONE') NOT IN ('EXCLUDE_TIMELINE', 'EXCLUDE_COMPLETELY')")
            if (spec.whitelistEnabled) {
                sb.append(" AND COALESCE(r.exclusionMode, 'NONE') = 'WHITELIST_INCLUDED'")
            }
        }

        if (!spec.showHidden) {
            sb.append(" AND m.isHidden = 0 AND m.hasNoMediaParent = 0")
        }

        // Cumulable quick filters
        if (QuickFilter.PHOTOS in spec.filters && QuickFilter.VIDEOS !in spec.filters) {
            sb.append(" AND m.isVideo = 0")
        } else if (QuickFilter.VIDEOS in spec.filters && QuickFilter.PHOTOS !in spec.filters) {
            sb.append(" AND m.isVideo = 1")
        }
        if (QuickFilter.FAVORITES in spec.filters) {
            sb.append(" AND m.isFavorite = 1")
        }
        if (QuickFilter.RAW in spec.filters) {
            sb.append(" AND m.isRaw = 1")
        }
        if (QuickFilter.ANIMATED in spec.filters) {
            sb.append(" AND m.isAnimated = 1")
        }
        if (QuickFilter.SCREENSHOTS in spec.filters) {
            sb.append(" AND m.isScreenshot = 1")
        }
        if (QuickFilter.LARGE_FILES in spec.filters) {
            sb.append(" AND m.sizeBytes >= 52428800")
        }

        // Combined Multi-Face Co-occurrence filter (1, 2, 3+ people simultaneously)
        if (spec.personIds.isNotEmpty()) {
            val placeholders = spec.personIds.joinToString(",") { "?" }
            sb.append(
                " AND m.id IN (SELECT f.mediaId FROM faces f WHERE f.personId IN ($placeholders) " +
                    "GROUP BY f.mediaId HAVING COUNT(DISTINCT f.personId) = ?)"
            )
            args.addAll(spec.personIds)
            args.add(spec.personIds.distinct().size)
        }

        // Global search (filename, folder, camera EXIF, extension, OR AI recognized person name!)
        val trimmed = spec.searchQuery.trim()
        if (trimmed.isNotEmpty()) {
            val pattern = "%$trimmed%"
            sb.append(
                """
                AND (
                    m.fileName LIKE ?
                    OR m.folderName LIKE ?
                    OR COALESCE(m.exifModel, '') LIKE ?
                    OR m.extension LIKE ?
                    OR EXISTS (
                        SELECT 1 FROM faces fs
                        INNER JOIN person_clusters pc ON fs.personId = pc.personId
                        WHERE fs.mediaId = m.id AND pc.name LIKE ?
                    )
                )
                """.trimIndent()
            )
            repeat(5) { args.add(pattern) }
        }

        val dir = if (spec.sortAscending) "ASC" else "DESC"
        val orderClause = when (spec.sortCriterion) {
            SortCriterion.DATE_TAKEN -> "m.dateTaken $dir, m.id DESC"
            SortCriterion.DATE_MODIFIED -> "m.dateModified $dir, m.id DESC"
            SortCriterion.DATE_ADDED -> "m.dateAdded $dir, m.id DESC"
            SortCriterion.NAME_NATURAL -> "m.naturalSortKey $dir, m.id ASC"
            SortCriterion.FILE_SIZE -> "m.sizeBytes $dir, m.id DESC"
            SortCriterion.MEGAPIXELS -> "m.megapixels $dir, m.sizeBytes $dir"
            SortCriterion.DURATION -> "m.durationMs $dir, m.dateTaken DESC"
            SortCriterion.EXTENSION -> "m.extension $dir, m.naturalSortKey ASC"
            SortCriterion.ITEM_COUNT -> "m.dateTaken $dir, m.id DESC"
            SortCriterion.SHUFFLE -> {
                args.add(spec.shuffleSeed.toLong())
                "((m.id * 1103515245 + ?) & 2147483647) $dir"
            }
        }
        sb.append(" ORDER BY $orderClause")

        if (spec.limit != null) {
            sb.append(" LIMIT ?")
            args.add(spec.limit)
        }

        return SimpleSQLiteQuery(sb.toString(), args.toTypedArray())
    }
}
