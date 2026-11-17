package com.hypergallery.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hypergallery.data.local.dao.FaceDao
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.TrashDao
import com.hypergallery.data.local.dao.VaultDao
import com.hypergallery.data.local.entity.CannotLinkConstraintEntity
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.FolderRuleEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.local.entity.PersonClusterEntity
import com.hypergallery.data.local.entity.TrashItemEntity
import com.hypergallery.data.local.entity.VaultItemEntity

@Database(
    entities = [
        MediaEntity::class,
        FolderRuleEntity::class,
        PersonClusterEntity::class,
        FaceEntity::class,
        CannotLinkConstraintEntity::class,
        VaultItemEntity::class,
        TrashItemEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun folderRuleDao(): FolderRuleDao
    abstract fun faceDao(): FaceDao
    abstract fun vaultDao(): VaultDao
    abstract fun trashDao(): TrashDao
}
