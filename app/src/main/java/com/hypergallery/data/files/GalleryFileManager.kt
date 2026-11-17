package com.hypergallery.data.files

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.TrashDao
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.local.entity.TrashItemEntity
import com.hypergallery.data.scanner.HybridMediaScanner
import com.hypergallery.domain.model.FileTreeNode
import com.hypergallery.util.NaturalSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GalleryFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val trashDao: TrashDao,
    private val folderRuleDao: FolderRuleDao,
    private val scanner: HybridMediaScanner
) {
    private val trashDir: File by lazy {
        File(context.filesDir, "trash").apply { if (!exists()) mkdirs() }
    }

    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }

    suspend fun purgeExpiredTrash() = withContext(Dispatchers.IO) {
        val expired = trashDao.getExpiredItems(System.currentTimeMillis())
        if (expired.isNotEmpty()) {
            expired.forEach { File(it.trashFilePath).delete() }
            trashDao.deleteTrashItems(expired.map { it.trashId })
        }
    }

    suspend fun moveToTrash(mediaIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val items = mediaDao.getByIds(mediaIds)
        var moved = 0
        val now = System.currentTimeMillis()
        val deletedIds = mutableListOf<Long>()

        for (item in items) {
            val src = File(item.path)
            if (!src.exists()) {
                deletedIds.add(item.id)
                continue
            }
            val dest = File(trashDir, "${UUID.randomUUID()}_${src.name}")
            val copied = runCatching {
                src.copyTo(dest, overwrite = true)
                true
            }.getOrDefault(false)

            if (copied) {
                deletePublicFile(src, item.mediaStoreId, item.isVideo)
                trashDao.insertTrashItem(
                    TrashItemEntity(
                        trashFilePath = dest.absolutePath,
                        originalPath = item.path,
                        fileName = item.fileName,
                        mimeType = item.mimeType,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        width = item.width,
                        height = item.height,
                        durationMs = item.durationMs,
                        dateTaken = item.dateTaken,
                        deletedAt = now,
                        expiresAt = now + THIRTY_DAYS_MS
                    )
                )
                deletedIds.add(item.id)
                moved++
            }
        }
        if (deletedIds.isNotEmpty()) {
            mediaDao.deleteByIds(deletedIds)
        }
        moved
    }

    suspend fun deletePermanently(mediaIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val items = mediaDao.getByIds(mediaIds)
        var deleted = 0
        for (item in items) {
            val src = File(item.path)
            if (deletePublicFile(src, item.mediaStoreId, item.isVideo) || !src.exists()) {
                deleted++
            }
        }
        mediaDao.deleteByIds(mediaIds)
        deleted
    }

    suspend fun restoreFromTrash(trashIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val items = trashDao.getByIds(trashIds)
        var restored = 0
        val restoredTrashIds = mutableListOf<Long>()
        val scanPaths = mutableListOf<String>()

        for (item in items) {
            val trashFile = File(item.trashFilePath)
            if (!trashFile.exists()) {
                restoredTrashIds.add(item.trashId)
                continue
            }
            val target = resolveNonCollidingFile(File(item.originalPath))
            target.parentFile?.mkdirs()
            val ok = runCatching {
                trashFile.copyTo(target, overwrite = false)
                trashFile.delete()
                true
            }.getOrDefault(false)

            if (ok) {
                restoredTrashIds.add(item.trashId)
                scanPaths.add(target.absolutePath)
                restored++
            }
        }
        if (restoredTrashIds.isNotEmpty()) {
            trashDao.deleteTrashItems(restoredTrashIds)
        }
        if (scanPaths.isNotEmpty()) {
            notifyMediaScanner(scanPaths)
            scanner.scanAllMedia()
        }
        restored
    }

    suspend fun emptyTrashItems(trashIds: List<Long>) = withContext(Dispatchers.IO) {
        val items = trashDao.getByIds(trashIds)
        items.forEach { File(it.trashFilePath).delete() }
        trashDao.deleteTrashItems(trashIds)
    }

    suspend fun copyOrMoveMedia(
        mediaIds: List<Long>,
        targetFolderPath: String,
        move: Boolean
    ): Int = withContext(Dispatchers.IO) {
        val targetDir = File(targetFolderPath).apply { if (!exists()) mkdirs() }
        val items = mediaDao.getByIds(mediaIds)
        val touchedPaths = mutableListOf<String>()
        val removedIds = mutableListOf<Long>()
        var count = 0

        for (item in items) {
            val src = File(item.path)
            if (!src.exists() || src.parent == targetDir.absolutePath) continue
            val dest = resolveNonCollidingFile(File(targetDir, src.name))
            val ok = runCatching {
                src.copyTo(dest, overwrite = false)
                true
            }.getOrDefault(false)

            if (ok) {
                touchedPaths.add(dest.absolutePath)
                if (move) {
                    deletePublicFile(src, item.mediaStoreId, item.isVideo)
                    removedIds.add(item.id)
                }
                count++
            }
        }
        if (removedIds.isNotEmpty()) {
            mediaDao.deleteByIds(removedIds)
        }
        if (touchedPaths.isNotEmpty()) {
            notifyMediaScanner(touchedPaths)
            scanner.scanAllMedia()
        }
        count
    }

    /**
     * Batch Pattern Rename supporting tokens: {date}, {index}, {name}, {folder}
     * Example pattern: "Vacances_{date}_{index}" -> "Vacances_20261117_001.jpg"
     */
    suspend fun batchPatternRename(
        mediaIds: List<Long>,
        pattern: String
    ): Int = withContext(Dispatchers.IO) {
        val items = mediaDao.getByIds(mediaIds).sortedBy { it.dateTaken }
        val dateFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val padWidth = items.size.toString().length.coerceAtLeast(3)
        val touchedPaths = mutableListOf<String>()
        val oldIds = mutableListOf<Long>()
        var renamedCount = 0

        items.forEachIndexed { idx, item ->
            val src = File(item.path)
            if (!src.exists()) return@forEachIndexed
            val baseName = src.nameWithoutExtension
            val ext = src.extension
            val dateStr = dateFmt.format(Date(item.dateTaken))
            val indexStr = (idx + 1).toString().padStart(padWidth, '0')
            val newStem = pattern
                .replace("{date}", dateStr)
                .replace("{index}", indexStr)
                .replace("{name}", baseName)
                .replace("{folder}", item.folderName)
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifEmpty { "${baseName}_$indexStr" }

            val newFileName = if (ext.isNotEmpty()) "$newStem.$ext" else newStem
            val target = resolveNonCollidingFile(File(src.parentFile, newFileName))
            if (src.renameTo(target)) {
                oldIds.add(item.id)
                touchedPaths.add(target.absolutePath)
                renamedCount++
            }
        }
        if (oldIds.isNotEmpty()) {
            mediaDao.deleteByIds(oldIds)
            notifyMediaScanner(touchedPaths)
            scanner.scanAllMedia()
        }
        renamedCount
    }

    suspend fun createNewFolder(parentPath: String, folderName: String): File? = withContext(Dispatchers.IO) {
        val clean = folderName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (clean.isEmpty()) return@withContext null
        val dir = File(parentPath, clean)
        if (dir.exists() || dir.mkdirs()) dir else null
    }

    suspend fun toggleNoMedia(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext false
        val noMediaFile = File(dir, ".nomedia")
        val nowHasNoMedia = if (noMediaFile.exists()) {
            noMediaFile.delete()
            false
        } else {
            runCatching { noMediaFile.createNewFile() }.getOrDefault(false)
        }
        mediaDao.setFolderNoMediaFlag(folderPath, nowHasNoMedia)
        notifyMediaScanner(listOf(folderPath))
        nowHasNoMedia
    }

    suspend fun stripExifMetadata(media: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        val file = File(media.path)
        if (!file.exists() || media.isVideo) return@withContext false
        val stripped = runCatching {
            val exif = ExifInterface(file)
            val sensitiveTags = listOf(
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_SERIAL_NUMBER
            )
            sensitiveTags.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            true
        }.getOrDefault(false)

        if (stripped) {
            mediaDao.updateOne(
                media.copy(
                    exifMake = null,
                    exifModel = null,
                    exifLens = null,
                    gpsLat = null,
                    gpsLon = null,
                    dateModified = file.lastModified()
                )
            )
        }
        stripped
    }

    suspend fun setAsWallpaper(media: MediaEntity): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = BitmapFactory.decodeFile(media.path) ?: return@runCatching false
            WallpaperManager.getInstance(context).setBitmap(bmp)
            true
        }.getOrDefault(false)
    }

    fun printPhoto(activityContext: Context, media: MediaEntity) {
        runCatching {
            val bmp = BitmapFactory.decodeFile(media.path) ?: return
            PrintHelper(activityContext).apply {
                scaleMode = PrintHelper.SCALE_MODE_FIT
            }.printBitmap(media.fileName, bmp)
        }
    }

    fun shareMedia(activityContext: Context, items: List<MediaEntity>) {
        if (items.isEmpty()) return
        val authority = "${context.packageName}.fileprovider"
        val uris = items.mapNotNull { item ->
            val f = File(item.path)
            if (f.exists()) FileProvider.getUriForFile(context, authority, f) else null
        }
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items.first().mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        activityContext.startActivity(Intent.createChooser(intent, "Partager"))
    }

    suspend fun listDirectoryNodes(
        directoryPath: String,
        showHidden: Boolean
    ): List<FileTreeNode> = withContext(Dispatchers.IO) {
        val dir = File(directoryPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val rulesMap = folderRuleDao.getAllRules().associateBy { it.path }
        val children = dir.listFiles() ?: return@withContext emptyList()

        children.mapNotNull { child ->
            val isHiddenDot = child.name.startsWith(".")
            if (!showHidden && isHiddenDot) return@mapNotNull null

            if (child.isDirectory) {
                val subChildren = child.listFiles().orEmpty()
                val hasNoMedia = subChildren.any { it.name.equals(".nomedia", ignoreCase = true) }
                if (!showHidden && hasNoMedia) return@mapNotNull null

                val rule = rulesMap[child.absolutePath]
                if (rule?.exclusionMode == "EXCLUDE_COMPLETELY") return@mapNotNull null

                var directMedia = 0
                var subDirs = 0
                for (sc in subChildren) {
                    if (sc.isDirectory) subDirs++
                    else if (sc.extension.lowercase(Locale.ROOT) in HybridMediaScanner.ALL_MEDIA_EXTENSIONS) directMedia++
                }
                FileTreeNode(
                    path = child.absolutePath,
                    name = child.name,
                    isDirectory = true,
                    isHidden = isHiddenDot,
                    hasNoMedia = hasNoMedia,
                    isLocked = rule?.isLocked ?: false,
                    isPinned = rule?.isPinned ?: false,
                    directMediaCount = directMedia,
                    subfolderCount = subDirs,
                    sizeBytes = 0L,
                    lastModified = child.lastModified()
                )
            } else {
                val ext = child.extension.lowercase(Locale.ROOT)
                if (ext !in HybridMediaScanner.ALL_MEDIA_EXTENSIONS) return@mapNotNull null
                FileTreeNode(
                    path = child.absolutePath,
                    name = child.name,
                    isDirectory = false,
                    isHidden = isHiddenDot,
                    hasNoMedia = false,
                    isLocked = false,
                    isPinned = false,
                    directMediaCount = 1,
                    subfolderCount = 0,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified()
                )
            }
        }.sortedWith(
            compareByDescending<FileTreeNode> { it.isPinned }
                .thenByDescending { it.isDirectory }
                .thenComparator { a, b -> NaturalSort.comparator.compare(a.name, b.name) }
        )
    }

    fun deletePublicFile(file: File, mediaStoreId: Long?, isVideo: Boolean): Boolean {
        var deleted = false
        if (file.exists() && file.delete()) {
            deleted = true
        }
        if (mediaStoreId != null) {
            runCatching {
                val baseUri = if (isVideo) {
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val itemUri = android.content.ContentUris.withAppendedId(baseUri, mediaStoreId)
                if (context.contentResolver.delete(itemUri, null, null) > 0) {
                    deleted = true
                }
            }
        }
        return deleted
    }

    fun resolveNonCollidingFile(desired: File): File {
        if (!desired.exists()) return desired
        val parent = desired.parentFile ?: return desired
        val stem = desired.nameWithoutExtension
        val ext = desired.extension
        var counter = 1
        while (true) {
            val candidateName = if (ext.isNotEmpty()) "${stem}_$counter.$ext" else "${stem}_$counter"
            val candidate = File(parent, candidateName)
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    fun notifyMediaScanner(paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }
}
