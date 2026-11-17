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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class FaceIndexCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val settingsRepository: SettingsRepository,
    private val clusterEngine: FaceClusterEngine
) {
    companion object {
        const val WORK_NAME = "hypergallery_face_ai_indexing"
    }

    private val _isRunning = MutableStateFlow(false)
    private val _statusNote = MutableStateFlow("Prêt")

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

    fun enqueueIndexing() {
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
        _statusNote.value = "Analyse en cours..."
        enqueueIndexing()
    }

    suspend fun fullReindex() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        val cropsDir = File(context.filesDir, "face_crops")
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
}

@HiltWorker
class FaceIndexingWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val settingsRepository: SettingsRepository,
    private val detector: BlazeFaceDetector,
    private val embedder: FaceEmbedder,
    private val clusterEngine: FaceClusterEngine,
    private val coordinator: FaceIndexCoordinator
) : CoroutineWorker(appContext, workerParams) {

    private val cropsDir: File by lazy {
        File(appContext.filesDir, "face_crops").apply { if (!exists()) mkdirs() }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val initialSettings = settingsRepository.settingsFlow.first()
        if (!initialSettings.faceAiEnabled) {
            coordinator.markRunning(false, "IA désactivée")
            return@withContext Result.success()
        }

        coordinator.markRunning(true, "Analyse IA locale active")
        try {
            var processedSinceCluster = 0
            while (!isStopped) {
                val paused = settingsRepository.isFaceWorkerPausedFlow.first()
                if (paused) {
                    coordinator.markRunning(false, "En pause")
                    return@withContext Result.success()
                }

                val settings = settingsRepository.settingsFlow.first()
                if (!settings.faceAiEnabled) {
                    coordinator.markRunning(false, "IA désactivée")
                    return@withContext Result.success()
                }

                // Battery guard (> 25% or charging)
                if (settings.faceScanOnlyChargingOrHighBattery && !isBatteryHealthy()) {
                    coordinator.markRunning(false, "En attente (Batterie < 25 % hors charge)")
                    return@withContext Result.retry()
                }

                // Thermal guard
                if (settings.faceScanThermalProtection && isDeviceOverheating()) {
                    coordinator.markRunning(true, "Pause thermique de sécurité...")
                    delay(8_000L)
                    continue
                }

                val batch = mediaDao.getUnscannedForFaceAi(batchSize = 20)
                if (batch.isEmpty()) {
                    clusterEngine.clusterIncremental(settings.faceClusterThreshold)
                    coordinator.markRunning(false, "Indexation terminée")
                    return@withContext Result.success()
                }

                for (media in batch) {
                    if (isStopped || settingsRepository.isFaceWorkerPausedFlow.first()) break
                    processSingleMedia(media)
                    mediaDao.markFaceScanned(media.id, true)
                    processedSinceCluster++
                }

                if (processedSinceCluster >= 20) {
                    clusterEngine.clusterIncremental(settings.faceClusterThreshold)
                    processedSinceCluster = 0
                }
            }
            Result.success()
        } catch (_: Exception) {
            coordinator.markRunning(false, "Prêt")
            Result.retry()
        }
    }

    private suspend fun processSingleMedia(media: MediaEntity) {
        val file = File(media.path)
        if (!file.exists()) return
        val bitmap = decodeAnalysisBitmap(media, file) ?: return

        try {
            val detections = detector.detectFaces(bitmap)
            if (detections.isEmpty()) return

            val entities = mutableListOf<FaceEntity>()
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
            if (entities.isNotEmpty()) {
                faceDao.insertFaces(entities)
            }
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
                RawPreviewExtractor.decodePreviewBitmap(file, targetMaxSide = 1280)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val maxSide = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxSide / (sample * 2) >= 1280) {
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
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
