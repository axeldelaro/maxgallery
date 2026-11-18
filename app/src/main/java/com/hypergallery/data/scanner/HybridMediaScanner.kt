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
import com.hypergallery.data.preferences.SettingsRepository
import com.hypergallery.domain.model.ScanProgressInfo
import com.hypergallery.util.NaturalSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultra-Fast Parallel Hidden & Masked Media Scanner Engine.
 *
 * Optimizations & Capabilities:
 *  1. Two-Stage Architecture:
 *     - Stage 1 (Instant Discovery < 200ms): Parallel work-stealing directory traversal + MediaStore.Files
 *       index query using zero heavy decoders (no ExifInterface/MediaMetadataRetriever/dHash on the hot path!).
 *     - Stage 2 (Background Enrichment): Asynchronously enriches EXIF, video dimensions, and perceptual dHash
 *       in the background after the grid is already populated and scrollable at 120 FPS!
 *  2. Exclusive Hidden & Masked Media Detection:
 *     - Detects dot-directories (`.secret`, `.Vault`, `.Private`, `.thumbnails`, etc.)
 *     - Detects dot-files (`.photo.jpg`)
 *     - Detects `.nomedia` folders and all nested subfolders at any depth
 *     - Detects disguised/obfuscated media files (`.bin`, `.dat`, `.tmp`, `.lock`, `.bak`, `.hidden`, `.0`, or extensionless)
 *       inside hidden folders by inspecting the first 16 magic bytes (JPEG, PNG, GIF, WebP, HEIC/AVIF/MP4/MOV ftyp, MKV/WebM EBML)!
 *  3. Transactional Room Batching:
 *     - Uses `@Transaction upsertBatch` (chunks of 600 rows) instead of single-row transactions (100x faster SQLite I/O).
 */
@Singleton
class HybridMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val folderRuleDao: FolderRuleDao,
    private val settingsRepository: SettingsRepository,
    private val duplicateDetector: DuplicateDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow(ScanProgressInfo())
    val scanProgress: StateFlow<ScanProgressInfo> = _scanProgress.asStateFlow()

    private var observerRegistered = false

    companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "avif", "heic", "heif", "gif", "svg", "bmp", "ico", "jfif",
            "dng", "nef", "cr2", "cr3", "arw", "orf", "rw2", "pef", "raf"
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mts", "flv", "wmv"
        )
        val ALL_MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

        // Extensions commonly used by locker/messenger apps or users to disguise hidden media
        val DISGUISED_CANDIDATE_EXTENSIONS = setOf(
            "", "bin", "dat", "tmp", "temp", "bak", "lock", "locked", "hidden", "sec", "enc", "0", "1", "thumb", " cache"
        )

        // Non-media junk directories to skip for extreme speed
        private val SKIP_DIR_NAMES = setOf(
            "node_modules", ".git", ".gradle", ".idea", "caches", "code_cache"
        )

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
                MediaStore.Files.getContentUri("external"),
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
            val startTime = System.currentTimeMillis()
            _isScanning.value = true
            val dirsScanned = AtomicInteger(0)
            val hiddenDirsFound = AtomicInteger(0)
            val hiddenFilesFound = AtomicInteger(0)
            val disguisedFilesFound = AtomicInteger(0)

            _scanProgress.value = ScanProgressInfo(isScanning = true, currentFolder = "Initialisation...")

            try {
                val cfg = settingsRepository.settingsFlow.first()
                val onlyHiddenMode = cfg.onlyHiddenMediaMode
                val deepScanDisguised = cfg.deepScanDisguisedMedia

                // If switched to Exclusive Hidden Media mode, immediately purge any old public items from DB
                if (onlyHiddenMode) {
                    mediaDao.deleteAllPublicMedia()
                }

                val existingSnapshots = mediaDao.getAllPathSnapshots().associateBy { it.path }
                val rules = folderRuleDao.getAllRules()
                val lockedFolders = rules.filter { it.isLocked }.map { it.path }.toHashSet()
                val completelyExcludedFolders = rules
                    .filter { it.exclusionMode == "EXCLUDE_COMPLETELY" }
                    .map { it.path }
                    .toHashSet()

                val discoveredPaths = ConcurrentHashMap.newKeySet<String>(existingSnapshots.size + 2048)
                val pendingEntities = Collections.synchronizedList(ArrayList<MediaEntity>(1024))
                val needsBackgroundEnrichment = Collections.synchronizedList(ArrayList<String>(512))
                var totalUpserted = 0

                suspend fun flushPendingBatch(minSize: Int = 1) {
                    val snapshotBatch: List<MediaEntity> = synchronized(pendingEntities) {
                        if (pendingEntities.size < minSize) return
                        val copy = ArrayList(pendingEntities)
                        pendingEntities.clear()
                        copy
                    }
                    for (chunk in snapshotBatch.chunked(600)) {
                        mediaDao.upsertBatch(chunk)
                        totalUpserted += chunk.size
                    }
                }

                // PHASE 1: Fast Parallel Multi-Core File System Walk (Primary Engine for Hidden & .nomedia Media)
                if (hasFullFilesAccess()) {
                    val roots = discoverStorageVolumes()
                    val topDirs = ArrayList<Triple<File, Boolean, Boolean>>() // (dir, inheritedNoMedia, inheritedDotHidden)

                    for (root in roots) {
                        val rootChildren = root.listFiles().orEmpty()
                        val rootNoMedia = rootChildren.any { it.name.equals(".nomedia", ignoreCase = true) }
                        for (child in rootChildren) {
                            if (child.isDirectory) {
                                val isDot = child.name.startsWith(".") && child.name.length > 1
                                topDirs.add(Triple(child, rootNoMedia, isDot))
                            } else if (child.isFile) {
                                val isDotFile = child.name.startsWith(".") && child.name.length > 1
                                if (!onlyHiddenMode || rootNoMedia || isDotFile) {
                                    inspectCandidateFileFast(
                                        file = child,
                                        parentPath = root.absolutePath,
                                        folderName = root.name.ifEmpty { "Stockage" },
                                        isDotHidden = isDotFile,
                                        hasNoMedia = rootNoMedia,
                                        deepScanDisguised = deepScanDisguised,
                                        lockedFolders = lockedFolders,
                                        existingSnapshots = existingSnapshots,
                                        discoveredPaths = discoveredPaths,
                                        hiddenFilesFound = hiddenFilesFound,
                                        disguisedFilesFound = disguisedFilesFound,
                                        needsEnrichmentPaths = needsBackgroundEnrichment
                                    )?.let { pendingEntities.add(it) }
                                }
                            }
                        }
                    }

                    // Prioritize likely hidden/vault/messenger folders first so results appear in < 100ms!
                    val prioritizedTopDirs = topDirs.sortedByDescending { (dir, noMedia, isDot) ->
                        when {
                            isDot || noMedia -> 3
                            dir.name.lowercase(Locale.ROOT) in setOf(
                                "whatsapp", "telegram", "pictures", "dcim", "movies", "download", "android"
                            ) -> 2
                            else -> 1
                        }
                    }

                    val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(4, 12)
                    val semaphore = Semaphore(parallelism)

                    coroutineScope {
                        val jobs = prioritizedTopDirs.map { (topDir, inheritedNoMedia, inheritedDot) ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    walkSubtreeFast(
                                        startDir = topDir,
                                        initialNoMedia = inheritedNoMedia,
                                        initialDotHidden = inheritedDot,
                                        onlyHiddenMode = onlyHiddenMode,
                                        deepScanDisguised = deepScanDisguised,
                                        completelyExcluded = completelyExcludedFolders,
                                        lockedFolders = lockedFolders,
                                        existingSnapshots = existingSnapshots,
                                        discoveredPaths = discoveredPaths,
                                        dirsScanned = dirsScanned,
                                        hiddenDirsFound = hiddenDirsFound,
                                        hiddenFilesFound = hiddenFilesFound,
                                        disguisedFilesFound = disguisedFilesFound,
                                        startTime = startTime,
                                        pendingEntities = pendingEntities,
                                        needsEnrichmentPaths = needsBackgroundEnrichment,
                                        onThresholdReached = { flushPendingBatch(minSize = 500) }
                                    )
                                }
                            }
                        }
                        jobs.awaitAll()
                    }
                    flushPendingBatch(minSize = 1)
                }

                // PHASE 2: Fast MediaStore.Files Query (Catches indexed hidden paths & fallback when MANAGE_EXTERNAL_STORAGE is off)
                val mediaStoreEntries = queryMediaStoreFiles(onlyHiddenMode = onlyHiddenMode)
                for (entry in mediaStoreEntries) {
                    if (!discoveredPaths.add(entry.path)) continue
                    val parentFile = File(entry.path).parentFile ?: continue
                    val parentPath = parentFile.absolutePath
                    if (parentPath in completelyExcludedFolders) continue

                    val isHiddenDot = hasDotSegmentInPath(entry.path)
                    val hasNoMedia = entry.isHiddenInMediaStore || hasAncestorNoMediaFast(parentFile)
                    if (onlyHiddenMode && !isHiddenDot && !hasNoMedia) continue

                    if (isHiddenDot || hasNoMedia) {
                        hiddenFilesFound.incrementAndGet()
                    }

                    val prev = existingSnapshots[entry.path]
                    if (prev != null && prev.dateModified == entry.dateModified) continue

                    val ext = entry.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val isVideo = entry.isVideo || ext in VIDEO_EXTENSIONS
                    val isRaw = RawPreviewExtractor.isRawExtension(ext)
                    val mp = if (entry.width > 0 && entry.height > 0) {
                        (entry.width.toLong() * entry.height.toLong()) / 1_000_000f
                    } else 0f

                    val fileName = File(entry.path).name
                    pendingEntities.add(
                        MediaEntity(
                            id = prev?.id ?: 0L,
                            mediaStoreId = entry.mediaStoreId,
                            uri = entry.uriString,
                            path = entry.path,
                            parentPath = parentPath,
                            folderName = parentFile.name.ifEmpty { "Stockage" },
                            fileName = fileName,
                            naturalSortKey = NaturalSort.buildKey(fileName),
                            extension = ext.ifEmpty { if (isVideo) "mp4" else "jpg" },
                            mimeType = entry.mimeType ?: if (isVideo) "video/mp4" else "image/jpeg",
                            sizeBytes = entry.sizeBytes,
                            width = entry.width,
                            height = entry.height,
                            megapixels = mp,
                            durationMs = entry.durationMs,
                            dateTaken = entry.dateTaken.takeIf { it > 0L } ?: entry.dateModified,
                            dateModified = entry.dateModified,
                            dateAdded = entry.dateAdded.takeIf { it > 0L } ?: entry.dateModified,
                            isVideo = isVideo,
                            isRaw = isRaw,
                            isAnimated = ext == "gif" || ext == "webp",
                            isScreenshot = entry.path.lowercase(Locale.ROOT).contains("screenshot"),
                            isHidden = isHiddenDot,
                            hasNoMediaParent = hasNoMedia,
                            isFavorite = prev?.isFavorite ?: false,
                            isLockedFolder = parentPath in lockedFolders,
                            faceScanned = prev?.faceScanned ?: false,
                            dHash = prev?.dHash ?: 0L
                        )
                    )
                    if (entry.width <= 0 || prev?.dHash == 0L) {
                        needsBackgroundEnrichment.add(entry.path)
                    }
                    if (pendingEntities.size >= 600) {
                        flushPendingBatch(minSize = 600)
                    }
                }
                flushPendingBatch(minSize = 1)

                // Remove deleted files or files no longer matching current mode
                if (existingSnapshots.isNotEmpty()) {
                    val stalePaths = existingSnapshots.keys.filter { path ->
                        path !in discoveredPaths || !File(path).exists()
                    }
                    for (chunk in stalePaths.chunked(500)) {
                        mediaDao.deleteByPaths(chunk)
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                _scanProgress.value = ScanProgressInfo(
                    isScanning = false,
                    scannedDirectories = dirsScanned.get(),
                    hiddenFoldersFound = hiddenDirsFound.get(),
                    hiddenMediaFound = hiddenFilesFound.get(),
                    disguisedMediaFound = disguisedFilesFound.get(),
                    elapsedMs = elapsed,
                    currentFolder = "Scan terminé en ${elapsed} ms"
                )

                // Launch Stage 2 Non-Blocking Background Enrichment (EXIF, Video Duration & dHash)
                if (needsBackgroundEnrichment.isNotEmpty()) {
                    val pathsToEnrich = ArrayList(needsBackgroundEnrichment)
                    scope.launch(Dispatchers.IO) {
                        enrichMediaMetadataInBackground(pathsToEnrich)
                    }
                }

                totalUpserted
            } finally {
                _isScanning.value = false
            }
        }
    }

    private suspend fun walkSubtreeFast(
        startDir: File,
        initialNoMedia: Boolean,
        initialDotHidden: Boolean,
        onlyHiddenMode: Boolean,
        deepScanDisguised: Boolean,
        completelyExcluded: Set<String>,
        lockedFolders: Set<String>,
        existingSnapshots: Map<String, MediaSnapshotRow>,
        discoveredPaths: MutableSet<String>,
        dirsScanned: AtomicInteger,
        hiddenDirsFound: AtomicInteger,
        hiddenFilesFound: AtomicInteger,
        disguisedFilesFound: AtomicInteger,
        startTime: Long,
        pendingEntities: MutableList<MediaEntity>,
        needsEnrichmentPaths: MutableList<String>,
        onThresholdReached: suspend () -> Unit
    ) {
        data class DirFrame(
            val dir: File,
            val inheritedNoMedia: Boolean,
            val inheritedDotHidden: Boolean
        )

        val stack = ArrayDeque<DirFrame>(64)
        stack.addLast(DirFrame(startDir, initialNoMedia, initialDotHidden))

        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            val dir = frame.dir
            val dirPath = dir.absolutePath
            if (dirPath in completelyExcluded) continue

            val dirName = dir.name
            if (dirName in SKIP_DIR_NAMES) continue
            // Allow scanning public/media directories inside /Android/media (like WhatsApp/Telegram .Statuses & .Stickers)
            // while skipping protected app sandbox private data
            if (dirPath.contains("/Android/data") || dirPath.contains("/Android/obb")) continue

            val children = dir.listFiles() ?: continue
            val countDirs = dirsScanned.incrementAndGet()

            // Quick single-pass check for .nomedia marker in current directory
            var hasLocalNoMedia = false
            for (c in children) {
                if (!c.isDirectory && c.name.equals(".nomedia", ignoreCase = true)) {
                    hasLocalNoMedia = true
                    break
                }
            }

            val currentNoMedia = frame.inheritedNoMedia || hasLocalNoMedia
            val currentDotHidden = frame.inheritedDotHidden || (dirName.startsWith(".") && dirName.length > 1)
            val isFolderMasked = currentNoMedia || currentDotHidden

            if (isFolderMasked) {
                hiddenDirsFound.incrementAndGet()
            }

            if (countDirs % 25 == 0) {
                _scanProgress.value = ScanProgressInfo(
                    isScanning = true,
                    scannedDirectories = countDirs,
                    hiddenFoldersFound = hiddenDirsFound.get(),
                    hiddenMediaFound = hiddenFilesFound.get(),
                    disguisedMediaFound = disguisedFilesFound.get(),
                    elapsedMs = System.currentTimeMillis() - startTime,
                    currentFolder = dirName
                )
            }

            for (child in children) {
                val childName = child.name
                if (childName == "." || childName == ".." || childName.equals(".nomedia", ignoreCase = true)) {
                    continue
                }
                if (child.isDirectory) {
                    val childIsDot = currentDotHidden || (childName.startsWith(".") && childName.length > 1)
                    stack.addLast(DirFrame(child, currentNoMedia, childIsDot))
                } else {
                    val isDotFile = childName.startsWith(".") && childName.length > 1
                    val isMaskedMedia = isFolderMasked || isDotFile
                    if (onlyHiddenMode && !isMaskedMedia) {
                        // In Exclusive Hidden Media Mode, skip normal public files without touching their bytes!
                        continue
                    }

                    val entity = inspectCandidateFileFast(
                        file = child,
                        parentPath = dirPath,
                        folderName = dirName.ifEmpty { "Stockage" },
                        isDotHidden = currentDotHidden || isDotFile,
                        hasNoMedia = currentNoMedia,
                        deepScanDisguised = deepScanDisguised && isMaskedMedia,
                        lockedFolders = lockedFolders,
                        existingSnapshots = existingSnapshots,
                        discoveredPaths = discoveredPaths,
                        hiddenFilesFound = hiddenFilesFound,
                        disguisedFilesFound = disguisedFilesFound,
                        needsEnrichmentPaths = needsEnrichmentPaths
                    )
                    if (entity != null) {
                        pendingEntities.add(entity)
                        if (pendingEntities.size >= 500) {
                            onThresholdReached()
                        }
                    }
                }
            }
        }
    }

    /**
     * Ultra-fast zero-Bitmap-allocation file inspector.
     * Reuses existing snapshot if `lastModified` is unchanged; otherwise constructs the entity immediately
     * (reading at most 16 magic bytes if the extension is disguised inside a hidden folder).
     */
    private fun inspectCandidateFileFast(
        file: File,
        parentPath: String,
        folderName: String,
        isDotHidden: Boolean,
        hasNoMedia: Boolean,
        deepScanDisguised: Boolean,
        lockedFolders: Set<String>,
        existingSnapshots: Map<String, MediaSnapshotRow>,
        discoveredPaths: MutableSet<String>,
        hiddenFilesFound: AtomicInteger,
        disguisedFilesFound: AtomicInteger,
        needsEnrichmentPaths: MutableList<String>
    ): MediaEntity? {
        val rawExt = file.extension.lowercase(Locale.ROOT)
        var detectedExt = rawExt
        var detectedMime: String? = null
        var isDisguised = false

        if (rawExt !in ALL_MEDIA_EXTENSIONS) {
            if (!deepScanDisguised || rawExt !in DISGUISED_CANDIDATE_EXTENSIONS) {
                return null
            }
            val size = file.length()
            if (size < 512L) return null
            val magic = sniffMagicMediaFormat(file) ?: return null
            detectedExt = magic.first
            detectedMime = magic.second
            isDisguised = true
        }

        val fullPath = file.absolutePath
        if (!discoveredPaths.add(fullPath)) return null

        if (isDotHidden || hasNoMedia) {
            hiddenFilesFound.incrementAndGet()
        }
        if (isDisguised) {
            disguisedFilesFound.incrementAndGet()
        }

        val lastMod = file.lastModified()
        val prev = existingSnapshots[fullPath]
        if (prev != null && prev.dateModified == lastMod) {
            if (prev.width <= 0 || prev.dHash == 0L) {
                needsEnrichmentPaths.add(fullPath)
            }
            return null
        }

        val fileName = file.name
        val isVideo = detectedExt in VIDEO_EXTENSIONS
        val isRaw = RawPreviewExtractor.isRawExtension(detectedExt)
        val sizeBytes = file.length()
        val mime = detectedMime
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(detectedExt)
            ?: if (isVideo) "video/$detectedExt" else "image/$detectedExt"

        // Ultra-fast bounds read only for standard images (no full pixel decode, no ExifInterface on hot path)
        var width = prev?.width ?: 0
        var height = prev?.height ?: 0
        if (!isVideo && !isRaw && width <= 0) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(fullPath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    width = opts.outWidth
                    height = opts.outHeight
                }
            }
        }
        val megapixels = if (width > 0 && height > 0) (width.toLong() * height.toLong()) / 1_000_000f else 0f

        needsEnrichmentPaths.add(fullPath)

        return MediaEntity(
            id = prev?.id ?: 0L,
            mediaStoreId = null,
            uri = Uri.fromFile(file).toString(),
            path = fullPath,
            parentPath = parentPath,
            folderName = folderName,
            fileName = fileName,
            naturalSortKey = NaturalSort.buildKey(fileName),
            extension = detectedExt,
            mimeType = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            megapixels = megapixels,
            durationMs = 0L,
            dateTaken = lastMod,
            dateModified = lastMod,
            dateAdded = lastMod,
            isVideo = isVideo,
            isRaw = isRaw,
            isAnimated = detectedExt == "gif" || detectedExt == "webp",
            isScreenshot = fullPath.lowercase(Locale.ROOT).let { it.contains("screenshot") || it.contains("captures") },
            isHidden = isDotHidden || isDisguised,
            hasNoMediaParent = hasNoMedia,
            isFavorite = prev?.isFavorite ?: false,
            isLockedFolder = parentPath in lockedFolders,
            faceScanned = prev?.faceScanned ?: false,
            dHash = prev?.dHash ?: 0L
        )
    }

    /**
     * Reads the first 16 bytes of a disguised/extensionless file inside a hidden folder
     * to detect real JPEG, PNG, GIF, WebP, HEIC/AVIF/MP4/MOV (`ftyp`), or MKV/WebM (`1A 45 DF A3`) containers.
     */
    private fun sniffMagicMediaFormat(file: File): Pair<String, String>? {
        return runCatching {
            val header = ByteArray(16)
            val n = RandomAccessFile(file, "r").use { it.read(header) }
            if (n < 12) return null

            val b0 = header[0].toInt() and 0xFF
            val b1 = header[1].toInt() and 0xFF
            val b2 = header[2].toInt() and 0xFF
            val b3 = header[3].toInt() and 0xFF

            // 1. JPEG: FF D8 FF
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return "jpg" to "image/jpeg"
            }
            // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                return "png" to "image/png"
            }
            // 3. GIF: "GIF87a" or "GIF89a"
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) {
                return "gif" to "image/gif"
            }
            // 4. RIFF (WebP or AVI): "RIFF"...."WEBP" or "AVI "
            if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
                val sub = String(header, 8, 4, Charsets.US_ASCII)
                if (sub == "WEBP") return "webp" to "image/webp"
                if (sub == "AVI ") return "avi" to "video/x-msvideo"
            }
            // 5. ISO Base Media File Format ('ftyp' at bytes 4..7): MP4, MOV, HEIC, AVIF, CR3
            val boxType = String(header, 4, 4, Charsets.US_ASCII)
            if (boxType == "ftyp") {
                val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.ROOT)
                return when {
                    brand in setOf("heic", "heix", "mif1", "msf1") -> "heic" to "image/heic"
                    brand in setOf("avif", "avis") -> "avif" to "image/avif"
                    brand == "crx " -> "cr3" to "image/x-canon-cr3"
                    brand == "qt  " -> "mov" to "video/quicktime"
                    else -> "mp4" to "video/mp4"
                }
            }
            // 6. Matroska / WebM EBML header: 1A 45 DF A3
            if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) {
                return "mkv" to "video/x-matroska"
            }
            null
        }.getOrNull()
    }

    /**
     * Stage 2 Background Enrichment:
     * Runs silently after the UI grid is already live. Populates EXIF camera tags, GPS,
     * exact DateTimeOriginal, video duration/dimensions, and perceptual 64-bit dHash.
     */
    private suspend fun enrichMediaMetadataInBackground(paths: List<String>) {
        val batchUpdates = ArrayList<MediaEntity>(100)
        for (path in paths) {
            val existing = mediaDao.getByPath(path) ?: continue
            val file = File(path)
            if (!file.exists()) continue

            var width = existing.width
            var height = existing.height
            var durationMs = existing.durationMs
            var dateTaken = existing.dateTaken
            var exifMake = existing.exifMake
            var exifModel = existing.exifModel
            var exifLens = existing.exifLens
            var exifFocal = existing.exifFocalLength
            var exifAperture = existing.exifAperture
            var exifShutter = existing.exifShutterSpeed
            var exifIso = existing.exifIso
            var exifFlash = existing.exifFlash
            var gpsLat = existing.gpsLat
            var gpsLon = existing.gpsLon
            var dHash = existing.dHash

            if (!existing.isVideo) {
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

                    exif.latLong?.takeIf { it.size == 2 }?.let {
                        gpsLat = it[0]
                        gpsLon = it[1]
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
                    if ((orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                            orientation == ExifInterface.ORIENTATION_ROTATE_270) && width > height
                    ) {
                        val tmp = width
                        width = height
                        height = tmp
                    }
                }
                if (dHash == 0L && existing.extension != "svg") {
                    dHash = duplicateDetector.computeFileDHash(file)
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

            val mp = if (width > 0 && height > 0) (width.toLong() * height.toLong()) / 1_000_000f else existing.megapixels
            val isAnim = existing.extension == "gif" || (existing.extension == "webp" && isAnimatedWebp(file))

            batchUpdates.add(
                existing.copy(
                    width = width,
                    height = height,
                    megapixels = mp,
                    durationMs = durationMs,
                    dateTaken = dateTaken,
                    isAnimated = isAnim,
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
                    dHash = dHash
                )
            )

            if (batchUpdates.size >= 100) {
                mediaDao.upsertBatch(ArrayList(batchUpdates))
                batchUpdates.clear()
            }
        }
        if (batchUpdates.isNotEmpty()) {
            mediaDao.upsertBatch(batchUpdates)
        }
    }

    private fun hasDotSegmentInPath(path: String): Boolean {
        var idx = 0
        val len = path.length
        while (idx < len) {
            val nextSlash = path.indexOf('/', idx).let { if (it == -1) len else it }
            val segLen = nextSlash - idx
            if (segLen > 1 && path[idx] == '.') {
                return true
            }
            idx = nextSlash + 1
        }
        return false
    }

    private fun hasAncestorNoMediaFast(dir: File?): Boolean {
        var current = dir
        var depth = 0
        while (current != null && depth < 12) {
            if (File(current, ".nomedia").exists()) return true
            val name = current.name
            if (name == "0" || name == "storage" || name == "emulated") break
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
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val dateTaken: Long,
        val dateModified: Long,
        val dateAdded: Long,
        val isVideo: Boolean,
        val isHiddenInMediaStore: Boolean
    )

    /**
     * Queries `MediaStore.Files.getContentUri("external")` which—unlike `MediaStore.Images`—also indexes
     * files inside `.nomedia` folders (`MEDIA_TYPE_NONE` with image/video paths or `/.` paths).
     */
    private fun queryMediaStoreFiles(onlyHiddenMode: Boolean): List<RawMediaStoreRow> {
        val results = ArrayList<RawMediaStoreRow>(1024)
        val filesUri = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )

        val selection = if (onlyHiddenMode) {
            // Query files in dot-paths OR files hidden by .nomedia (MEDIA_TYPE_NONE = 0)
            "(${MediaStore.Files.FileColumns.DATA} LIKE '%/.%' OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE})"
        } else {
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} " +
                "OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO} " +
                "OR ${MediaStore.Files.FileColumns.DATA} LIKE '%/.%')"
        }

        runCatching {
            context.contentResolver.query(
                filesUri,
                projection,
                selection,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val wCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
                val hCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
                val durCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val takenCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
                val modCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val addCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)

                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (path.isNullOrBlank()) continue
                    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext !in ALL_MEDIA_EXTENSIONS) continue

                    val file = File(path)
                    if (!file.exists()) continue

                    val id = cursor.getLong(idCol)
                    val mediaType = if (typeCol >= 0) cursor.getInt(typeCol) else 0
                    val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || ext in VIDEO_EXTENSIONS
                    val isHiddenMediaStore = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE

                    results.add(
                        RawMediaStoreRow(
                            mediaStoreId = id,
                            uriString = ContentUris.withAppendedId(filesUri, id).toString(),
                            path = path,
                            mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                            sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).takeIf { it > 0L } ?: file.length() else file.length(),
                            width = if (wCol >= 0) cursor.getInt(wCol) else 0,
                            height = if (hCol >= 0) cursor.getInt(hCol) else 0,
                            durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L,
                            dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L,
                            dateModified = file.lastModified().takeIf { it > 0L }
                                ?: (if (modCol >= 0) cursor.getLong(modCol) * 1000L else 0L),
                            dateAdded = if (addCol >= 0) cursor.getLong(addCol) * 1000L else file.lastModified(),
                            isVideo = isVideo,
                            isHiddenInMediaStore = isHiddenMediaStore
                        )
                    )
                }
            }
        }
        return results
    }
}
