package com.hypergallery.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["parentPath"]),
        Index(value = ["dateTaken"]),
        Index(value = ["dateModified"]),
        Index(value = ["dateAdded"]),
        Index(value = ["naturalSortKey"]),
        Index(value = ["sizeBytes"]),
        Index(value = ["megapixels"]),
        Index(value = ["durationMs"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isHidden"]),
        Index(value = ["isLockedFolder"]),
        Index(value = ["faceScanned"]),
        Index(value = ["dHash"])
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mediaStoreId: Long? = null,
    val uri: String,
    val path: String,
    val parentPath: String,
    val folderName: String,
    val fileName: String,
    val naturalSortKey: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val megapixels: Float,
    val durationMs: Long,
    val dateTaken: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val isRaw: Boolean,
    val isAnimated: Boolean,
    val isScreenshot: Boolean,
    val isHidden: Boolean,
    val hasNoMediaParent: Boolean,
    val isFavorite: Boolean = false,
    val isLockedFolder: Boolean = false,
    val exifMake: String? = null,
    val exifModel: String? = null,
    val exifLens: String? = null,
    val exifFocalLength: String? = null,
    val exifAperture: String? = null,
    val exifShutterSpeed: String? = null,
    val exifIso: String? = null,
    val exifFlash: String? = null,
    val gpsLat: Double? = null,
    val gpsLon: Double? = null,
    val faceScanned: Boolean = false,
    val dHash: Long = 0L
)

@Entity(tableName = "folder_rules")
data class FolderRuleEntity(
    @PrimaryKey val path: String,
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val exclusionMode: String = "NONE",
    val excludeFromFaceAi: Boolean = false,
    val sortCriterion: String? = null,
    val sortAscending: Boolean? = null
)

@Entity(
    tableName = "person_clusters",
    indices = [
        Index(value = ["faceCount"]),
        Index(value = ["name"])
    ]
)
data class PersonClusterEntity(
    @PrimaryKey(autoGenerate = true) val personId: Long = 0L,
    val name: String = "",
    val coverFaceId: Long? = null,
    val coverCropPath: String? = null,
    val faceCount: Int = 0,
    val centroidBlob: ByteArray,
    val isHidden: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonClusterEntity) return false
        return personId == other.personId &&
            name == other.name &&
            coverFaceId == other.coverFaceId &&
            coverCropPath == other.coverCropPath &&
            faceCount == other.faceCount &&
            isHidden == other.isHidden &&
            centroidBlob.contentEquals(other.centroidBlob)
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + faceCount
        result = 31 * result + centroidBlob.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["personId"])
    ]
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val faceId: Long = 0L,
    val mediaId: Long,
    val personId: Long? = null,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val cropPath: String,
    val embeddingBlob: ByteArray,
    val qualityScore: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return faceId == other.faceId &&
            mediaId == other.mediaId &&
            personId == other.personId &&
            cropPath == other.cropPath &&
            embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = faceId.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}

@Entity(
    tableName = "cannot_link_constraints",
    primaryKeys = ["mediaId", "personId"]
)
data class CannotLinkConstraintEntity(
    val mediaId: Long,
    val personId: Long
)

@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey(autoGenerate = true) val vaultId: Long = 0L,
    val encryptedFilePath: String,
    val originalPath: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTaken: Long,
    val addedAt: Long
)

@Entity(tableName = "trash_items")
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val trashId: Long = 0L,
    val trashFilePath: String,
    val originalPath: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val dateTaken: Long,
    val deletedAt: Long,
    val expiresAt: Long
)
