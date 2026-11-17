package com.hypergallery.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hypergallery.data.local.AppDatabase
import com.hypergallery.data.local.dao.FaceDao
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.TrashDao
import com.hypergallery.data.local.dao.VaultDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "hypergallery_ai.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.query("PRAGMA synchronous = NORMAL").close()
                    db.query("PRAGMA temp_store = MEMORY").close()
                    db.query("PRAGMA mmap_size = 268435456").close()
                }
            })
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMediaDao(db: AppDatabase): MediaDao = db.mediaDao()

    @Provides
    fun provideFolderRuleDao(db: AppDatabase): FolderRuleDao = db.folderRuleDao()

    @Provides
    fun provideFaceDao(db: AppDatabase): FaceDao = db.faceDao()

    @Provides
    fun provideVaultDao(db: AppDatabase): VaultDao = db.vaultDao()

    @Provides
    fun provideTrashDao(db: AppDatabase): TrashDao = db.trashDao()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
