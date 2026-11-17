package com.hypergallery.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.local.entity.PersonClusterEntity
import kotlinx.coroutines.flow.Flow

data class FolderAggregationRow(
    val parentPath: String,
    val folderName: String,
    val coverPath: String?,
    val coverUri: String?,
    val mediaCount: Int,
    val totalSizeBytes: Long,
    val lastModified: Long,
    val hasNoMediaParent: Boolean,
    val isHidden: Boolean
)

@Dao
interface MediaDao {

    @RawQuery(
        observedEntities = [
            MediaEntity::class,
            FolderRuleEntity::class,
            FaceEntity::class,
            PersonClusterEntity::class
        ]
    )
    fun pagingMedia(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(
        observedEntities = [
            MediaEntity::class,
            FolderRuleEntity::class,
            FaceEntity::class,
            PersonClusterEntity::class
        ]
    )
    suspend fun queryMediaList(query: SupportSQLiteQuery): List<MediaEntity>

    @RawQuery(
        observedEntities = [
            MediaEntity::class,
            FolderRuleEntity::class,
            FaceEntity::class,
            PersonClusterEntity::class
        ]
    )
    fun observeMediaList(query: SupportSQLiteQuery): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<MediaEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOne(item: MediaEntity): Long

    @Update
    suspend fun updateOne(item: MediaEntity)

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<MediaEntity?>

    @Query("SELECT * FROM media_items WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): MediaEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT path, dateModified, isFavorite, faceScanned, dHash, id FROM media_items")
    suspend fun getAllPathSnapshots(): List<MediaSnapshotRow>

    @Query("DELETE FROM media_items WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE media_items SET isFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    @Query("UPDATE media_items SET isFavorite = 1 WHERE path IN (:paths)")
    suspend fun restoreFavoritesByPaths(paths: List<String>)

    @Query("SELECT path FROM media_items WHERE isFavorite = 1")
    suspend fun getFavoritePaths(): List<String>

    @Query("UPDATE media_items SET isLockedFolder = :locked WHERE parentPath = :folderPath")
    suspend fun setFolderLockedFlag(folderPath: String, locked: Boolean)

    @Query("UPDATE media_items SET hasNoMediaParent = :hasNoMedia WHERE parentPath = :folderPath")
    suspend fun setFolderNoMediaFlag(folderPath: String, hasNoMedia: Boolean)

    @Query("UPDATE media_items SET faceScanned = :scanned WHERE id = :mediaId")
    suspend fun markFaceScanned(mediaId: Long, scanned: Boolean)

    @Query("UPDATE media_items SET faceScanned = 0")
    suspend fun resetAllFaceScanned()

    @Query("UPDATE media_items SET dHash = :dHash WHERE id = :mediaId")
    suspend fun updateDHash(mediaId: Long, dHash: Long)

    @Query(
        """
        SELECT m.* FROM media_items m
        LEFT JOIN folder_rules r ON m.parentPath = r.path
        WHERE m.faceScanned = 0
          AND m.isLockedFolder = 0
          AND COALESCE(r.isLocked, 0) = 0
          AND COALESCE(r.excludeFromFaceAi, 0) = 0
          AND COALESCE(r.exclusionMode, 'NONE') != 'EXCLUDE_COMPLETELY'
        ORDER BY m.dateTaken DESC
        LIMIT :batchSize
        """
    )
    suspend fun getUnscannedForFaceAi(batchSize: Int): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media_items m
        LEFT JOIN folder_rules r ON m.parentPath = r.path
        WHERE m.isLockedFolder = 0
          AND COALESCE(r.isLocked, 0) = 0
          AND COALESCE(r.excludeFromFaceAi, 0) = 0
          AND COALESCE(r.exclusionMode, 'NONE') != 'EXCLUDE_COMPLETELY'
        """
    )
    fun observeTotalEligibleForFaceAi(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM media_items m
        LEFT JOIN folder_rules r ON m.parentPath = r.path
        WHERE m.faceScanned = 1
          AND m.isLockedFolder = 0
          AND COALESCE(r.isLocked, 0) = 0
          AND COALESCE(r.excludeFromFaceAi, 0) = 0
          AND COALESCE(r.exclusionMode, 'NONE') != 'EXCLUDE_COMPLETELY'
        """
    )
    fun observeProcessedForFaceAi(): Flow<Int>

    @Query(
        """
        SELECT
            m.parentPath AS parentPath,
            m.folderName AS folderName,
            (SELECT m2.path FROM media_items m2 WHERE m2.parentPath = m.parentPath ORDER BY m2.dateTaken DESC LIMIT 1) AS coverPath,
            (SELECT m2.uri FROM media_items m2 WHERE m2.parentPath = m.parentPath ORDER BY m2.dateTaken DESC LIMIT 1) AS coverUri,
            COUNT(*) AS mediaCount,
            SUM(m.sizeBytes) AS totalSizeBytes,
            MAX(m.dateModified) AS lastModified,
            MAX(CASE WHEN m.hasNoMediaParent = 1 THEN 1 ELSE 0 END) AS hasNoMediaParent,
            MAX(CASE WHEN m.isHidden = 1 THEN 1 ELSE 0 END) AS isHidden
        FROM media_items m
        GROUP BY m.parentPath
        """
    )
    fun observeFolderAggregations(): Flow<List<FolderAggregationRow>>

    @Query(
        """
        SELECT m.* FROM media_items m
        LEFT JOIN folder_rules r ON m.parentPath = r.path
        WHERE m.isVideo = 0
          AND m.isLockedFolder = 0
          AND COALESCE(r.isLocked, 0) = 0
          AND COALESCE(r.exclusionMode, 'NONE') != 'EXCLUDE_COMPLETELY'
          AND (:includeHidden = 1 OR (m.isHidden = 0 AND m.hasNoMediaParent = 0))
        ORDER BY m.sizeBytes DESC
        """
    )
    suspend fun getCandidatesForDuplicateScan(includeHidden: Boolean): List<MediaEntity>

    @Query(
        """
        SELECT m.* FROM media_items m
        LEFT JOIN folder_rules r ON m.parentPath = r.path
        WHERE m.isLockedFolder = 0
          AND COALESCE(r.isLocked, 0) = 0
          AND m.isHidden = 0
          AND m.hasNoMediaParent = 0
          AND COALESCE(r.exclusionMode, 'NONE') NOT IN ('EXCLUDE_TIMELINE', 'EXCLUDE_COMPLETELY')
          AND strftime('%m-%d', m.dateTaken / 1000, 'unixepoch', 'localtime') = :monthDay
          AND CAST(strftime('%Y', m.dateTaken / 1000, 'unixepoch', 'localtime') AS INTEGER) < :currentYear
        ORDER BY m.dateTaken DESC
        LIMIT 30
        """
    )
    fun observeMemoriesForDay(monthDay: String, currentYear: Int): Flow<List<MediaEntity>>

    @Query("SELECT COUNT(*) FROM media_items")
    fun observeTotalIndexedCount(): Flow<Int>
}

data class MediaSnapshotRow(
    val id: Long,
    val path: String,
    val dateModified: Long,
    val isFavorite: Boolean,
    val faceScanned: Boolean,
    val dHash: Long
)
