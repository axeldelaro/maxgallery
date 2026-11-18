package com.hypergallery.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hypergallery.data.local.dao.FaceDao
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.data.preferences.SettingsRepository
import com.hypergallery.domain.model.FaceIndexStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Coordinates both immediate in-process high-speed Face AI indexing (zero WorkManager scheduling latency)
 * and WorkManager background persistence for large hidden libraries.
 */
@Singleton
class FaceIndexCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val settingsRepository: SettingsRepository,
    private val detector: BlazeFaceDetector,
    private val embedder: FaceEmbedder,
    private val clusterEngine: FaceClusterEngine
) {
    companion object {
        const val WORK_NAME = "hypergallery_face_ai_indexing"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()
    private val _isRunning = MutableStateFlow(false)
    private val _statusNote = MutableStateFlow("Prêt")

    private val cropsDir: File by lazy {
        File(context.filesDir, "face_crops").apply { if (!exists()) mkdirs() }
    }

    val statusFlow: Flow<FaceIndexStatus> = combine(
        _isRunning,
        settingsRepository.isFaceWorkerPausedFlow,
        mediaDao.observeProcessedForFaceAi(),
        mediaDao.observeTotalEligibleForFaceAi(),
        faceDao.observeTotalFaceCount()
    ) { running, paused, processed, total, faces ->
        FaceIndexStatus(
            isRunning = running,
            isPaused = paused,
            processedCount = processed,
            totalCount = total,
            detectedFacesCount = faces,
            statusNote = _statusNote.value
        )
    }

    /**
     * Starts immediate in-process coroutine indexing AND enqueues WorkManager backup job.
     */
    fun enqueueIndexing() {
        scope.launch {
            runDirectIndexingLoop()
        }
        val request = OneTimeWorkRequestBuilder<FaceIndexingWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    suspend fun pauseIndexing() {
        settingsRepository.setFaceWorkerPaused(true)
        _statusNote.value = "En pause"
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        _isRunning.value = false
    }

    suspend fun resumeIndexing() {
        settingsRepository.setFaceWorkerPaused(false)
        _statusNote.value = "Analyse IA haute vitesse..."
        enqueueIndexing()
    }

    suspend fun fullReindex() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        cropsDir.listFiles()?.forEach { it.delete() }
        faceDao.deleteAllFaces()
        faceDao.deleteAllPersons()
        mediaDao.resetAllFaceScanned()
        settingsRepository.setFaceWorkerPaused(false)
        enqueueIndexing()
    }

    suspend fun reclusterWithThreshold(threshold: Float) {
        settingsRepository.updateFaceAiSettings(threshold = threshold)
        clusterEngine.reclusterAll(threshold)
    }

    internal fun markRunning(running: Boolean, note: String) {
        _isRunning.value = running
        _statusNote.value = note
    }

    internal suspend fun runDirectIndexingLoop(): Boolean {
        if (engineMutex.isLocked) return true
        return engineMutex.withLock {
            val initialSettings = settingsRepository.settingsFlow.first()
            if (!initialSettings.faceAiEnabled) {
                markRunning(false, "IA désactivée")
                return@withLock true
            }

            markRunning(true, "Analyse IA locale haute vitesse...")
            try {
                var processedSinceCluster = 0
                while (true) {
                    if (settingsRepository.isFaceWorkerPausedFlow.first()) {
                        markRunning(false, "En pause")
                        return@withLock true
                    }

                    val settings = settingsRepository.settingsFlow.first()
                    if (!settings.faceAiEnabled) {
                        markRunning(false, "IA désactivée")
                        return@withLock true
                    }

                    if (settings.faceScanOnlyChargingOrHighBattery && !isBatteryHealthy()) {
                        markRunning(false, "En attente (Batterie < 25 % hors charge)")
                        return@withLock false
                    }

                    if (settings.faceScanThermalProtection && isDeviceOverheating()) {
                        markRunning(true, "Pause thermique de sécurité...")
                        delay(6_000L)
                        continue
                    }

                    val batch = mediaDao.getUnscannedForFaceAi(
                        batchSize = 48,
                        onlyHidden = settings.onlyHiddenMediaMode
                    )
                    if (batch.isEmpty()) {
                        clusterEngine.clusterIncremental(settings.faceClusterThreshold)
                        markRunning(false, "Indexation IA terminée")
                        return@withLock true
                    }

                    val scannedIds = ArrayList<Long>(batch.size)
                    val detectedFaceEntities = ArrayList<FaceEntity>(batch.size * 2)

                    for (media in batch) {
                        if (settingsRepository.isFaceWorkerPausedFlow.first()) break
                        val faces = extractFacesFromMedia(media)
                        if (faces.isNotEmpty()) {
                            detectedFaceEntities.addAll(faces)
                        }
                        scannedIds.add(media.id)
                        processedSinceCluster++
                    }

                    if (detectedFaceEntities.isNotEmpty()) {
                        faceDao.insertFaces(detectedFaceEntities)
                    }
                    if (scannedIds.isNotEmpty()) {
                        mediaDao.markFaceScannedBatch(scannedIds, true)
                    }

                    if (processedSinceCluster >= 32) {
                        clusterEngine.clusterIncremental(settings.faceClusterThreshold)
                        processedSinceCluster = 0
                    }
                }
                true
            } catch (_: Exception) {
                markRunning(false, "Prêt")
                false
            }
        }
    }

    private fun extractFacesFromMedia(media: MediaEntity): List<FaceEntity> {
        val file = File(media.path)
        if (!file.exists()) return emptyList()
        val bitmap = decodeAnalysisBitmap(media, file) ?: return emptyList()

        try {
            val detections = detector.detectFaces(bitmap)
            if (detections.isEmpty()) return emptyList()

            val entities = ArrayList<FaceEntity>(detections.size)
            for (det in detections) {
                val aligned112 = FaceAligner.alignTo112(bitmap, det.landmarks5Px)
                val embedding = try {
                    embedder.extractEmbedding(aligned112)
                } finally {
                    aligned112.recycle()
                }

                val cropFile = File(cropsDir, "face_${media.id}_${UUID.randomUUID()}.jpg")
                FaceAligner.saveAvatarMedallion(bitmap, det.boundingBoxPx, cropFile)

                entities.add(
                    FaceEntity(
                        mediaId = media.id,
                        personId = null,
                        leftNorm = det.normalizedBox.left,
                        topNorm = det.normalizedBox.top,
                        rightNorm = det.normalizedBox.right,
                        bottomNorm = det.normalizedBox.bottom,
                        cropPath = cropFile.absolutePath,
                        embeddingBlob = FaceEmbedder.floatArrayToBlob(embedding),
                        qualityScore = det.sharpnessScore * det.confidence
                    )
                )
            }
            return entities
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeAnalysisBitmap(media: MediaEntity, file: File): Bitmap? {
        return runCatching {
            if (media.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durMs = media.durationMs.coerceAtLeast(1000L)
                    retriever.getFrameAtTime((durMs * 250L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } else if (media.isRaw) {
                RawPreviewExtractor.decodePreviewBitmap(file, targetMaxSide = 960)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val maxSide = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxSide / (sample * 2) >= 960) {
                    sample *= 2
                }
                val rawBmp = BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                ) ?: return null

                val rotation = when (
                    ExifInterface(file).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (rotation == 0f) {
                    rawBmp
                } else {
                    val rotated = Bitmap.createBitmap(
                        rawBmp, 0, 0, rawBmp.width, rawBmp.height,
                        Matrix().apply { postRotate(rotation) },
                        true
                    )
                    if (rotated !== rawBmp) rawBmp.recycle()
                    rotated
                }
            }
        }.getOrNull()
    }

    private fun isBatteryHealthy(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return true
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (plugged) return true
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return true
        val pct = (level * 100) / scale
        return pct > 25
    }

    private fun isDeviceOverheating(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }
}

@HiltWorker
class FaceIndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: FaceIndexCoordinator
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val ok = coordinator.runDirectIndexingLoop()
        if (ok) Result.success() else Result.retry()
    }
}
