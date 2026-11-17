package com.hypergallery.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Geometric 5-point Similarity Transform Face Aligner (ArcFace / MobileFaceNet canonical 112x112 coordinates).
 * Computes the closed-form 2D least-squares similarity matrix (scale + rotation + translation)
 * mapping [leftEye, rightEye, noseTip, mouthLeft, mouthRight] onto canonical ArcFace landmarks.
 */
object FaceAligner {

    const val ALIGNED_SIZE = 112

    // Canonical ArcFace 112x112 5-landmark coordinates
    private val ARCFACE_DST = arrayOf(
        PointF(38.2946f, 51.6963f), // Left eye (image left)
        PointF(73.5318f, 51.5014f), // Right eye (image right)
        PointF(56.0252f, 71.7366f), // Nose tip
        PointF(41.5493f, 92.3655f), // Left mouth corner
        PointF(70.7299f, 92.2041f)  // Right mouth corner
    )

    private val bilinearPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Aligns a detected face into a crisp 112x112 RGB bitmap using a 5-point similarity transform.
     */
    fun alignTo112(source: Bitmap, landmarks5: Array<PointF>): Bitmap {
        require(landmarks5.size == 5) { "Expected 5 facial landmarks" }
        val matrix = estimateSimilarityMatrix(landmarks5, ARCFACE_DST)
        val aligned = Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        canvas.drawBitmap(source, matrix, bilinearPaint)
        return aligned
    }

    /**
     * Saves a padded square avatar medallion (192x192 JPEG) centered on the face for the People tab UI.
     */
    fun saveAvatarMedallion(source: Bitmap, bboxPx: RectF, outputFile: File) {
        val cx = bboxPx.centerX()
        val cy = bboxPx.centerY()
        val halfSide = max(bboxPx.width(), bboxPx.height()) * 0.68f
        val left = (cx - halfSide).toInt().coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val top = (cy - halfSide).toInt().coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val right = (cx + halfSide).toInt().coerceIn(left + 1, source.width)
        val bottom = (cy + halfSide).toInt().coerceIn(top + 1, source.height)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(source, left, top, w, h)
        val medallion = Bitmap.createScaledBitmap(cropped, 192, 192, true)
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().buffered().use { out ->
            medallion.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (medallion !== cropped) medallion.recycle()
        if (cropped !== source) cropped.recycle()
    }

    /**
     * Solves min_{a,b,tx,ty} sum_i || [a -b tx; b a ty] * [x_i, y_i, 1]^T - [u_i, v_i]^T ||^2
     * Closed-form 2D Umeyama / Procrustes similarity transform.
     */
    fun estimateSimilarityMatrix(src: Array<PointF>, dst: Array<PointF>): Matrix {
        val n = min(src.size, dst.size)
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f

        for (i in 0 until n) {
            srcMeanX += src[i].x
            srcMeanY += src[i].y
            dstMeanX += dst[i].x
            dstMeanY += dst[i].y
        }
        srcMeanX /= n
        srcMeanY /= n
        dstMeanX /= n
        dstMeanY /= n

        var srcVar = 0f
        var sumA = 0f
        var sumB = 0f
        for (i in 0 until n) {
            val sx = src[i].x - srcMeanX
            val sy = src[i].y - srcMeanY
            val dx = dst[i].x - dstMeanX
            val dy = dst[i].y - dstMeanY
            srcVar += sx * sx + sy * sy
            sumA += sx * dx + sy * dy
            sumB += sx * dy - sy * dx
        }

        val matrix = Matrix()
        if (srcVar < 1e-6f) {
            return matrix
        }
        val a = sumA / srcVar
        val b = sumB / srcVar
        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)

        matrix.setValues(
            floatArrayOf(
                a, -b, tx,
                b, a, ty,
                0f, 0f, 1f
            )
        )
        return matrix
    }
}
