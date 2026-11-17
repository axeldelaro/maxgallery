package com.hypergallery.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

object RawPreviewExtractor {

    val RAW_EXTENSIONS = setOf("dng", "nef", "cr2", "cr3", "arw", "orf", "rw2", "pef", "raf")

    fun isRawExtension(ext: String): Boolean = ext.lowercase() in RAW_EXTENSIONS

    /**
     * Extracts the highest-definition embedded JPEG preview from Pro RAW files
     * (.dng, .nef, .cr2, .cr3, .arw, .orf, .rw2) using ExifInterface + binary TIFF/ISO-BMFF JPEG marker scanning.
     */
    fun extractBestJpegBytes(file: File): ByteArray? {
        if (!file.exists() || file.length() < 512L) return null

        var exifThumb: ByteArray? = null
        runCatching {
            val exif = ExifInterface(file)
            exifThumb = exif.thumbnailBytes
        }

        // Scan first 24 MB of the RAW container (TIFF SubIFDs / Canon CR3 'prvw' box) for the largest embedded JPEG
        val scannedLargest = scanLargestEmbeddedJpeg(file, maxScanBytes = 24 * 1024 * 1024)
        return when {
            scannedLargest != null && (exifThumb == null || scannedLargest.size > exifThumb!!.size) -> scannedLargest
            exifThumb != null -> exifThumb
            else -> null
        }
    }

    fun decodePreviewBitmap(file: File, targetMaxSide: Int = 1080): Bitmap? {
        val bytes = extractBestJpegBytes(file)
        val rotationDegrees = runCatching {
            when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val decoded = if (bytes != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / (sample * 2) >= targetMaxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = if (targetMaxSide <= 512) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } else {
            // Direct fallback for DNG supported natively by Android BitmapFactory
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (maxDim / (sample * 2) >= targetMaxSide) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        } ?: return null

        if (rotationDegrees == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrDefault(decoded)
    }

    private fun scanLargestEmbeddedJpeg(file: File, maxScanBytes: Int): ByteArray? {
        return runCatching {
            val len = file.length().coerceAtMost(maxScanBytes.toLong()).toInt()
            val buffer = ByteArray(len)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(buffer, 0, len)
            }

            var bestStart = -1
            var bestLength = 0
            var i = 16 // Skip primary TIFF magic header at offset 0
            while (i < len - 4) {
                // Look for JPEG SOI + APP0/APP1/DQT marker: FF D8 FF (E0|E1|DB|EE|C0|C4)
                if (buffer[i] == 0xFF.toByte() &&
                    buffer[i + 1] == 0xD8.toByte() &&
                    buffer[i + 2] == 0xFF.toByte()
                ) {
                    val m = buffer[i + 3].toInt() and 0xFF
                    if (m == 0xE0 || m == 0xE1 || m == 0xDB || m == 0xEE || m == 0xC0 || m == 0xC4) {
                        val eoi = findJpegEoi(buffer, i + 4, len)
                        if (eoi > i) {
                            val candidateLen = eoi - i + 2
                            if (candidateLen > bestLength && candidateLen >= 8_192) {
                                // Validate candidate dimensions quickly
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(buffer, i, candidateLen, opts)
                                if (opts.outWidth >= 160 && opts.outHeight >= 120) {
                                    bestStart = i
                                    bestLength = candidateLen
                                }
                            }
                            i = eoi + 2
                            continue
                        }
                    }
                }
                i++
            }
            if (bestStart >= 0 && bestLength > 0) {
                buffer.copyOfRange(bestStart, bestStart + bestLength)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun findJpegEoi(buf: ByteArray, start: Int, limit: Int): Int {
        var pos = start
        while (pos < limit - 1) {
            if (buf[pos] == 0xFF.toByte() && buf[pos + 1] == 0xD9.toByte()) {
                return pos
            }
            pos++
        }
        return -1
    }
}

class RawThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val targetPx = when (val w = options.size.width) {
            is Dimension.Pixels -> w.px.coerceIn(256, 2560)
            Dimension.Undefined -> 1440
        }
        val bitmap = RawPreviewExtractor.decodePreviewBitmap(file, targetPx) ?: return null
        return ImageFetchResult(
            image = bitmap.asImage(shareable = true),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            val path = data.path ?: return null
            val ext = path.substringAfterLast('.', "").lowercase()
            if (!RawPreviewExtractor.isRawExtension(ext)) return null
            val file = File(path)
            if (!file.exists()) return null
            return RawThumbnailFetcher(file, options)
        }
    }
}
