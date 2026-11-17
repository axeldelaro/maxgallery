package com.hypergallery.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.domain.model.DuplicateGroup
import com.hypergallery.domain.model.DuplicateMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class DuplicateDetector @Inject constructor(
    private val mediaDao: MediaDao
) {
    /**
     * Computes a 64-bit perceptual difference hash (dHash) by resizing the image to 9x8 grayscale
     * and comparing adjacent horizontal pixels (row[x] > row[x + 1]).
     */
    fun computeFileDHash(file: File): Long {
        if (!file.exists()) return 0L
        return runCatching {
            val ext = file.extension.lowercase()
            val bmp = if (RawPreviewExtractor.isRawExtension(ext)) {
                RawPreviewExtractor.decodePreviewBitmap(file, targetMaxSide = 128)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0L
                val maxDim = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxDim / (sample * 2) >= 64) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } ?: return 0L

            try {
                computeBitmapDHash(bmp)
            } finally {
                bmp.recycle()
            }
        }.getOrDefault(0L)
    }

    fun computeBitmapDHash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, 9, 8, true)
        val pixels = IntArray(9 * 8)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (scaled !== source) scaled.recycle()

        var hash = 0L
        var bitIndex = 0
        for (y in 0 until 8) {
            val rowOffset = y * 9
            var prevLuma = luma(pixels[rowOffset])
            for (x in 1..8) {
                val currLuma = luma(pixels[rowOffset + x])
                if (prevLuma > currLuma) {
                    hash = hash or (1L shl bitIndex)
                }
                prevLuma = currLuma
                bitIndex++
            }
        }
        // Avoid 0L sentinel collision
        return if (hash == 0L) 1L else hash
    }

    fun hammingDistance(hashA: Long, hashB: Long): Int =
        java.lang.Long.bitCount(hashA xor hashB)

    suspend fun findDuplicateGroups(
        maxHammingDistance: Int = 5,
        includeHidden: Boolean = false
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val candidates = mediaDao.getCandidatesForDuplicateScan(includeHidden)
        val valid = ArrayList<Pair<com.hypergallery.data.local.entity.MediaEntity, Long>>(candidates.size)

        for (item in candidates) {
            var h = item.dHash
            if (h == 0L) {
                h = computeFileDHash(File(item.path))
                if (h != 0L) {
                    mediaDao.updateDHash(item.id, h)
                }
            }
            if (h != 0L) {
                valid.add(item to h)
            }
        }

        val visited = BooleanArray(valid.size)
        val groups = mutableListOf<DuplicateGroup>()
        var nextGroupId = 1

        for (i in valid.indices) {
            if (visited[i]) continue
            val (anchorMedia, anchorHash) = valid[i]
            val cluster = mutableListOf<DuplicateMediaItem>()

            visited[i] = true
            cluster.add(
                DuplicateMediaItem(
                    id = anchorMedia.id,
                    path = anchorMedia.path,
                    uri = anchorMedia.uri,
                    fileName = anchorMedia.fileName,
                    width = anchorMedia.width,
                    height = anchorMedia.height,
                    sizeBytes = anchorMedia.sizeBytes,
                    dateTaken = anchorMedia.dateTaken,
                    hammingDistance = 0
                )
            )

            for (j in (i + 1) until valid.size) {
                if (visited[j]) continue
                val (otherMedia, otherHash) = valid[j]
                val dist = hammingDistance(anchorHash, otherHash)
                if (dist <= maxHammingDistance) {
                    visited[j] = true
                    cluster.add(
                        DuplicateMediaItem(
                            id = otherMedia.id,
                            path = otherMedia.path,
                            uri = otherMedia.uri,
                            fileName = otherMedia.fileName,
                            width = otherMedia.width,
                            height = otherMedia.height,
                            sizeBytes = otherMedia.sizeBytes,
                            dateTaken = otherMedia.dateTaken,
                            hammingDistance = dist
                        )
                    )
                }
            }

            if (cluster.size >= 2) {
                val sorted = cluster.sortedWith(
                    compareByDescending<DuplicateMediaItem> { it.width.toLong() * it.height.toLong() }
                        .thenByDescending { it.sizeBytes }
                )
                val best = sorted.first()
                val reclaimable = sorted.drop(1).sumOf { it.sizeBytes }
                groups.add(
                    DuplicateGroup(
                        groupId = nextGroupId++,
                        anchorHash = anchorHash,
                        items = sorted,
                        recommendedKeepId = best.id,
                        reclaimableBytes = reclaimable
                    )
                )
            }
        }

        groups.sortedByDescending { it.reclaimableBytes }
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
