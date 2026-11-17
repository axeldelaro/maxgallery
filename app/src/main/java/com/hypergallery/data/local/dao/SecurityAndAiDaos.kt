package com.hypergallery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hypergallery.data.local.entity.CannotLinkConstraintEntity
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.local.entity.PersonClusterEntity
import com.hypergallery.data.local.entity.TrashItemEntity
import com.hypergallery.data.local.entity.VaultItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderRuleDao {

    @Query("SELECT * FROM folder_rules")
    fun observeAllRules(): Flow<List<FolderRuleEntity>>

    @Query("SELECT * FROM folder_rules")
    suspend fun getAllRules(): List<FolderRuleEntity>

    @Query("SELECT * FROM folder_rules WHERE path = :path LIMIT 1")
    suspend fun getRule(path: String): FolderRuleEntity?

    @Query("SELECT * FROM folder_rules WHERE path = :path LIMIT 1")
    fun observeRule(path: String): Flow<FolderRuleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: FolderRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rules: List<FolderRuleEntity>)

    @Query("DELETE FROM folder_rules WHERE path = :path")
    suspend fun deleteRule(path: String)
}

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>): List<Long>

    @Query("SELECT * FROM faces")
    suspend fun getAllFaces(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE personId IS NULL")
    suspend fun getUnassignedFaces(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE mediaId = :mediaId")
    fun observeFacesForMedia(mediaId: Long): Flow<List<FaceEntity>>

    @Query("SELECT * FROM faces WHERE mediaId = :mediaId")
    suspend fun getFacesForMedia(mediaId: Long): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE personId = :personId ORDER BY qualityScore DESC")
    suspend fun getFacesForPerson(personId: Long): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE personId = :personId ORDER BY qualityScore DESC")
    fun observeFacesForPerson(personId: Long): Flow<List<FaceEntity>>

    @Query("UPDATE faces SET personId = :personId WHERE faceId IN (:faceIds)")
    suspend fun assignFacesToPerson(faceIds: List<Long>, personId: Long?)

    @Query("UPDATE faces SET personId = :targetPersonId WHERE personId = :sourcePersonId")
    suspend fun moveAllFacesBetweenPersons(sourcePersonId: Long, targetPersonId: Long)

    @Query("UPDATE faces SET personId = NULL WHERE mediaId IN (:mediaIds) AND personId = :personId")
    suspend fun detachMediaFromPerson(mediaIds: List<Long>, personId: Long)

    @Query("DELETE FROM faces")
    suspend fun deleteAllFaces()

    @Query("SELECT COUNT(*) FROM faces")
    fun observeTotalFaceCount(): Flow<Int>

    // Person clusters
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: PersonClusterEntity): Long

    @Query("SELECT * FROM person_clusters WHERE faceCount > 0 ORDER BY faceCount DESC, personId ASC")
    fun observeActivePersons(): Flow<List<PersonClusterEntity>>

    @Query("SELECT * FROM person_clusters ORDER BY faceCount DESC, personId ASC")
    fun observeAllPersons(): Flow<List<PersonClusterEntity>>

    @Query("SELECT * FROM person_clusters")
    suspend fun getAllPersons(): List<PersonClusterEntity>

    @Query("SELECT * FROM person_clusters WHERE personId = :personId LIMIT 1")
    suspend fun getPersonById(personId: Long): PersonClusterEntity?

    @Query("SELECT * FROM person_clusters WHERE personId = :personId LIMIT 1")
    fun observePersonById(personId: Long): Flow<PersonClusterEntity?>

    @Query("UPDATE person_clusters SET name = :name WHERE personId = :personId")
    suspend fun updatePersonName(personId: Long, name: String)

    @Query("UPDATE person_clusters SET isHidden = :hidden WHERE personId = :personId")
    suspend fun setPersonHidden(personId: Long, hidden: Boolean)

    @Query("UPDATE person_clusters SET coverFaceId = :faceId, coverCropPath = :cropPath WHERE personId = :personId")
    suspend fun setPersonCover(personId: Long, faceId: Long, cropPath: String)

    @Query("DELETE FROM person_clusters WHERE personId = :personId")
    suspend fun deletePerson(personId: Long)

    @Query("DELETE FROM person_clusters")
    suspend fun deleteAllPersons()

    // Cannot-link constraints
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCannotLink(constraint: CannotLinkConstraintEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCannotLinks(constraints: List<CannotLinkConstraintEntity>)

    @Query("SELECT * FROM cannot_link_constraints")
    suspend fun getAllCannotLinks(): List<CannotLinkConstraintEntity>
}

@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun observeVaultItems(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items WHERE vaultId = :vaultId LIMIT 1")
    suspend fun getById(vaultId: Long): VaultItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVaultItem(item: VaultItemEntity): Long

    @Query("DELETE FROM vault_items WHERE vaultId = :vaultId")
    suspend fun deleteVaultItem(vaultId: Long)

    @Query("SELECT COUNT(*) FROM vault_items")
    fun observeVaultCount(): Flow<Int>
}

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC")
    fun observeTrashItems(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_items WHERE expiresAt <= :nowMillis")
    suspend fun getExpiredItems(nowMillis: Long): List<TrashItemEntity>

    @Query("SELECT * FROM trash_items WHERE trashId IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrashItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashItem(item: TrashItemEntity): Long

    @Query("DELETE FROM trash_items WHERE trashId IN (:ids)")
    suspend fun deleteTrashItems(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM trash_items")
    fun observeTrashCount(): Flow<Int>
}
