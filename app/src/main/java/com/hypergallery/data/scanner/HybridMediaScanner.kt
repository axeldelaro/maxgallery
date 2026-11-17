package com.hypergallery.data.scanner

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.hypergallery.ai.DuplicateDetector
import com.hypergallery.data.local.dao.FolderRuleDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.MediaSnapshotRow
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.util.NaturalSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val folderRuleDao: FolderRuleDao,
    private val duplicateDetector: DuplicateDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var observerRegistered = false

    companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "avif", "heic", "heif", "gif", "svg", "bmp",
            "dng", "nef", "cr2", "cr3", "arw", "orf", "rw2", "pef", "raf"
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mts"
        )
        val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

        private val exifDateFormats = listOf(
            "yyyy:MM:dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
    }

    fun registerMediaObserver(onChanged: () -> Unit) {
        if (observerRegistered) return
        observerRegistered = true
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scope.launch {
                    scanAllMedia()
                    onChanged()
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
    }

    fun hasFullFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun discoverStorageVolumes(): List<File> {
        val roots = LinkedHashSet<File>()
        Environment.getExternalStorageDirectory()?.let { if (it.exists()) roots.add(it) }

        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (vol in sm.storageVolumes) {
                    vol.directory?.let { dir ->
                        if (dir.exists() && dir.canRead()) roots.add(dir)
                    }
                }
            }
        }

        // Also check /storage/* for SD cards & USB OTG mounts
        runCatching {
            File("/storage").listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.canRead() && dir.name != "self" && dir.name != "emulated") {
                    roots.add(dir)
                }
            }
        }
        return roots.toList()
    }

    suspend fun scanAllMedia(): Int = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            _isScanning.value = true
            try {
                val existingSnapshots = mediaDao.getAllPathSnapshots().associateBy { it.path }
                val lockedFolders = folderRuleDao.getAllRules()
                    .filter { it.isLocked }
                    .map { it.path }
                    .toHashSet()
                val completelyExcludedFolders = folderRuleDao.getAllRules()
                    .filter { it.exclusionMode == "EXCLUDE_COMPLETELY" }
                    .map { it.path }
                    .toHashSet()

                val discoveredPaths = HashSet<String>(existingSnapshots.size + 1024)
                val toUpsert = ArrayList<MediaEntity>(256)
                var totalUpserted = 0

                suspend fun flushBatch() {
                    if (toUpsert.isNotEmpty()) {
                        for (item in toUpsert) {
                            mediaDao.upsertOne(item)
                        }
                        totalUpserted += toUpsert.size
                        toUpsert.clear()
                    }
                }

                // Phase 1: Fast MediaStore sweep (Images + Videos)
                val mediaStoreEntries = queryMediaStore()
                for (entry in mediaStoreEntries) {
                    val parentPath = File(entry.path).parent ?: continue
                    if (parentPath in completelyExcludedFolders) continue
                    discoveredPaths.add(entry.path)
                    val prev = existingSnapshots[entry.path]
                    if (prev != null && prev.dateModified == entry.dateModified) {
                        continue
                    }
                    val entity = enrichAndBuildEntity(
                        file = File(entry.path),
                        mediaStoreId = entry.mediaStoreId,
                        contentUri = entry.uriString,
                        hintWidth = entry.width,
                        hintHeight = entry.height,
                        hintDurationMs = entry.durationMs,
                        hintMime = entry.mimeType,
                        hintDateTaken = entry.dateTaken,
                        hintDateAdded = entry.dateAdded,
                        previous = prev,
                        isLockedFolder = parentPath in lockedFolders
                    )
                    if (entity != null) {
                        toUpsert.add(entity)
                        if (toUpsert.size >= 150) flushBatch()
                    }
                }
                flushBatch()

                // Phase 2: Direct java.io.File walk when MANAGE_EXTERNAL_STORAGE (or API <= 29) allows discovering
                // .nomedia folders, dot-hidden folders, SD/USB OTG volumes, and RAW formats skipped by MediaStore!
                if (hasFullFilesAccess()) {
                    val roots = discoverStorageVolumes()
                    for (root in roots) {
                        walkDirectoryIterative(
                            root = root,
                            completelyExcluded = completelyExcludedFolders,
                            lockedFolders = lockedFolders,
                            discoveredPaths = discoveredPaths,
                            existingSnapshots = existingSnapshots,
                            onNewOrModified = { entity ->
                                toUpsert.add(entity)
                                if (toUpsert.size >= 150) flushBatch()
                            }
                        )
                    }
                    flushBatch()
                }

                // Remove deleted or completely-excluded files from Room index
                if (discoveredPaths.isNotEmpty() || existingSnapshots.isNotEmpty()) {
                    val stalePaths = existingSnapshots.keys.filter {
                        it !in discoveredPaths || !File(it).exists()
                    }
                    stalePaths.chunked(400).forEach { chunk ->
                        mediaDao.deleteByPaths(chunk)
                    }
                }

                totalUpserted
            } finally {
                _isScanning.value = false
            }
        }
    }

    private suspend fun walkDirectoryIterative(
        root: File,
        completelyExcluded: Set<String>,
        lockedFolders: Set<String>,
        discoveredPaths: MutableSet<String>,
        existingSnapshots: Map<String, MediaSnapshotRow>,
        onNewOrModified: suspend (MediaEntity) -> Unit
    ) {
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)

        while (stack.isNotEmpty()) {
            val (dir, inheritedNoMedia) = stack.removeLast()
            val dirPath = dir.absolutePath
            if (dirPath in completelyExcluded) continue
            // Skip Android private app sandboxes (/Android/data and /Android/obb)
            if (dirPath.contains("/Android/data") || dirPath.contains("/Android/obb")) continue

            val children = dir.listFiles() ?: continue
            val currentHasNoMedia = inheritedNoMedia || children.any { it.name.equals(".nomedia", ignoreCase = true) }

            for (child in children) {
                if (child.isDirectory) {
                    if (!child.name.equals(".Trash", ignoreCase = true)) {
                        stack.addLast(child to currentHasNoMedia)
                    }
                } else if (child.isFile) {
                    val ext = child.extension.lowercase(Locale.ROOT)
                    if (ext !in ALL_MEDIA_EXTENSIONS) continue
                    val fullPath = child.absolutePath
                    if (!discoveredPaths.add(fullPath)) continue

                    val lastMod = child.lastModified()
                    val prev = existingSnapshots[fullPath]
                    if (prev != null && prev.dateModified == lastMod) continue

                    val parentPath = dirPath
                    val entity = enrichAndBuildEntity(
                        file = child,
                        mediaStoreId = null,
                        contentUri = Uri.fromFile(child).toString(),
                        hintWidth = 0,
                        hintHeight = 0,
                        hintDurationMs = 0L,
                        hintMime = null,
                        hintDateTaken = 0L,
                        hintDateAdded = lastMod,
                        previous = prev,
                        isLockedFolder = parentPath in lockedFolders,
                        forcedNoMedia = currentHasNoMedia
                    )
                    if (entity != null) {
                        onNewOrModified(entity)
                    }
                }
            }
        }
    }

    private fun enrichAndBuildEntity(
        file: File,
        mediaStoreId: Long?,
        contentUri: String,
        hintWidth: Int,
        hintHeight: Int,
        hintDurationMs: Long,
        hintMime: String?,
        hintDateTaken: Long,
        hintDateAdded: Long,
        previous: MediaSnapshotRow?,
        isLockedFolder: Boolean,
        forcedNoMedia: Boolean? = null
    ): MediaEntity? {
        if (!file.exists()) return null
        val path = file.absolutePath
        val parentFile = file.parentFile ?: return null
        val parentPath = parentFile.absolutePath
        val folderName = parentFile.name.ifEmpty { "Stockage" }
        val fileName = file.name
        val ext = file.extension.lowercase(Locale.ROOT)
        val isVideo = ext in VIDEO_EXTENSIONS
        val isRaw = RawPreviewExtractor.isRawExtension(ext)
        val sizeBytes = file.length()
        val lastModified = file.lastModified()

        val mime = hintMime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (isVideo) "video/$ext" else "image/$ext"

        var width = hintWidth
        var height = hintHeight
        var durationMs = hintDurationMs
        var dateTaken = hintDateTaken
        var exifMake: String? = null
        var exifModel: String? = null
        var exifLens: String? = null
        var exifFocal: String? = null
        var exifAperture: String? = null
        var exifShutter: String? = null
        var exifIso: String? = null
        var exifFlash: String? = null
        var gpsLat: Double? = null
        var gpsLon: Double? = null

        if (!isVideo) {
            runCatching {
                val exif = ExifInterface(file)
                exifMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                exifModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                exifLens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
                exifFocal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                    .takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f mm", it) }
                exifAperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.trim()?.let { "f/$it" }
                exifShutter = formatExposureTime(exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0))
                exifIso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.trim()
                exifFlash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
                    .takeIf { it >= 0 }?.let { if ((it and 1) != 0) "Déclenché" else "Désactivé" }

                val latLong = exif.latLong
                if (latLong != null && latLong.size == 2) {
                    gpsLat = latLong[0]
                    gpsLon = latLong[1]
                }

                val exifDateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (exifDateStr != null) {
                    parseExifDate(exifDateStr)?.let { dateTaken = it }
                }

                if (width <= 0 || height <= 0) {
                    width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                }
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270
                ) {
                    val tmp = width
                    width = height
                    height = tmp
                }
            }

            if (width <= 0 || height <= 0) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        width = opts.outWidth
                        height = opts.outHeight
                    }
                }
            }
        } else if (width <= 0 || durationMs <= 0L) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    if (durationMs <= 0L) {
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    }
                    if (width <= 0 || height <= 0) {
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                    }
                } finally {
                    retriever.release()
                }
            }
        }

        if (dateTaken <= 0L) dateTaken = lastModified
        val dateAdded = if (hintDateAdded > 0L) hintDateAdded else lastModified
        val megapixels = if (width > 0 && height > 0) (width.toLong() * height.toLong()) / 1_000_000f else 0f

        val isAnimated = ext == "gif" || (ext == "webp" && isAnimatedWebp(file))
        val lowerPath = path.lowercase(Locale.ROOT)
        val isScreenshot = lowerPath.contains("screenshot") || lowerPath.contains("captures")
        val isHidden = path.split('/', '\\').any { it.startsWith(".") && it.length > 1 }
        val hasNoMedia = forcedNoMedia ?: hasAncestorNoMedia(parentFile)

        val dHash = if (previous != null && previous.dHash != 0L) {
            previous.dHash
        } else if (!isVideo && ext != "svg") {
            duplicateDetector.computeFileDHash(file)
        } else {
            0L
        }

        return MediaEntity(
            id = previous?.id ?: 0L,
            mediaStoreId = mediaStoreId,
            uri = contentUri,
            path = path,
            parentPath = parentPath,
            folderName = folderName,
            fileName = fileName,
            naturalSortKey = NaturalSort.buildKey(fileName),
            extension = ext,
            mimeType = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            megapixels = megapixels,
            durationMs = durationMs,
            dateTaken = dateTaken,
            dateModified = lastModified,
            dateAdded = dateAdded,
            isVideo = isVideo,
            isRaw = isRaw,
            isAnimated = isAnimated,
            isScreenshot = isScreenshot,
            isHidden = isHidden,
            hasNoMediaParent = hasNoMedia,
            isFavorite = previous?.isFavorite ?: false,
            isLockedFolder = isLockedFolder,
            exifMake = exifMake,
            exifModel = exifModel,
            exifLens = exifLens,
            exifFocalLength = exifFocal,
            exifAperture = exifAperture,
            exifShutterSpeed = exifShutter,
            exifIso = exifIso,
            exifFlash = exifFlash,
            gpsLat = gpsLat,
            gpsLon = gpsLon,
            faceScanned = previous?.faceScanned ?: false,
            dHash = dHash
        )
    }

    private fun hasAncestorNoMedia(dir: File?): Boolean {
        var current = dir
        var depth = 0
        while (current != null && depth < 10) {
            if (File(current, ".nomedia").exists()) return true
            if (current.name == "0" || current.name == "storage") break
            current = current.parentFile
            depth++
        }
        return false
    }

    private fun isAnimatedWebp(file: File): Boolean {
        return runCatching {
            val header = ByteArray(32)
            val read = file.inputStream().use { it.read(header) }
            if (read < 21) return false
            // RIFF....WEBPVP8X with animation flag bit (0x02) at byte 20
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            val webp = String(header, 8, 4, Charsets.US_ASCII)
            val vp8x = String(header, 12, 4, Charsets.US_ASCII)
            riff == "RIFF" && webp == "WEBP" && vp8x == "VP8X" && ((header[20].toInt() and 0x02) != 0)
        }.getOrDefault(false)
    }

    private fun parseExifDate(raw: String): Long? {
        for (pattern in exifDateFormats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(raw)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return null
    }

    private fun formatExposureTime(seconds: Double): String? {
        if (seconds <= 0.0) return null
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
            "1/$denom s"
        }
    }

    private data class RawMediaStoreRow(
        val mediaStoreId: Long,
        val uriString: String,
        val path: String,
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val dateTaken: Long,
        val dateModified: Long,
        val dateAdded: Long
    )

    private fun queryMediaStore(): List<RawMediaStoreRow> {
        val results = ArrayList<RawMediaStoreRow>(2048)
        val sources = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
        )

        for ((collectionUri, isVideo) in sources) {
            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.DATA)
                add(MediaStore.MediaColumns.MIME_TYPE)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
                add(MediaStore.MediaColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.DATE_MODIFIED)
                add(MediaStore.MediaColumns.DATE_ADDED)
                if (isVideo) add(MediaStore.Video.VideoColumns.DURATION)
            }.toTypedArray()

            runCatching {
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val wCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val hCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    val takenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val addCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val durCol = if (isVideo) cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (path.isNullOrBlank()) continue
                        val file = File(path)
                        if (!file.exists()) continue
                        val uri = ContentUris.withAppendedId(collectionUri, id).toString()
                        results.add(
                            RawMediaStoreRow(
                                mediaStoreId = id,
                                uriString = uri,
                                path = path,
                                mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                                width = if (wCol >= 0) cursor.getInt(wCol) else 0,
                                height = if (hCol >= 0) cursor.getInt(hCol) else 0,
                                durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                                dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L,
                                dateModified = file.lastModified().takeIf { it > 0L }
                                    ?: (if (modCol >= 0) cursor.getLong(modCol) * 1000L else 0L),
                                dateAdded = if (addCol >= 0) cursor.getLong(addCol) * 1000L else file.lastModified()
                            )
                        )
                    }
                }
            }
        }
        return results
    }
}
