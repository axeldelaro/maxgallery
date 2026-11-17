package com.hypergallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DetectedFace(
    val boundingBoxPx: RectF,
    val normalizedBox: RectF,
    val landmarks5Px: Array<PointF>,
    val confidence: Float,
    val sharpnessScore: Float
)

/**
 * Hardware-accelerated BlazeFace TFLite detector (assets/models/blaze_face_short_range.tflite).
 * Features:
 *  - Exact 896 SSD anchors decoding ([1, 896, 16] regressors + [1, 896, 1] classificators)
 *  - Multi-scale full-frame + 2x2 overlapping high-res tile pyramid for group photos
 *  - Weighted Non-Maximum Suppression (Weighted NMS)
 *  - Minimum 42x42 px size filter & Laplacian variance blur rejection
 */
@Singleton
class BlazeFaceDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_ASSET_PATH = "models/blaze_face_short_range.tflite"
        private const val INPUT_SIZE = 128
        private const val NUM_ANCHORS = 896
        private const val MIN_SCORE = 0.63f
        private const val MIN_FACE_SIZE_PX = 42f
        private const val MIN_LAPLACIAN_SHARPNESS = 18.0f
        private const val NMS_IOU_THRESHOLD = 0.35f
    }

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private val anchors: FloatArray by lazy { generateBlazeFaceAnchors() }
    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }
    private val pixels128 = IntArray(INPUT_SIZE * INPUT_SIZE)

    private fun ensureInterpreter(): Interpreter {
        interpreter?.let { return it }
        val mapped = loadModelFile(MODEL_ASSET_PATH)

        // Hardware acceleration waterfall: GPU Delegate -> NNAPI -> XNNPACK 4-thread CPU
        runCatching {
            val gpu = GpuDelegate()
            val opts = Interpreter.Options().addDelegate(gpu).setNumThreads(4)
            val interp = Interpreter(mapped, opts)
            gpuDelegate = gpu
            interpreter = interp
            return interp
        }

        runCatching {
            val nnapi = NnApiDelegate()
            val opts = Interpreter.Options().addDelegate(nnapi).setNumThreads(4)
            val interp = Interpreter(mapped, opts)
            nnApiDelegate = nnapi
            interpreter = interp
            return interp
        }

        val cpuOpts = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        return Interpreter(mapped, cpuOpts).also { interpreter = it }
    }

    fun detectFaces(bitmap: Bitmap): List<DetectedFace> = synchronized(lock) {
        if (bitmap.width < 42 || bitmap.height < 42) return emptyList()
        val interp = ensureInterpreter()
        val rawDetections = mutableListOf<RawDetection>()

        // Pass 1: Full-frame letterboxed scan
        rawDetections.addAll(
            runTileInference(
                interp = interp,
                source = bitmap,
                tileRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                minScore = MIN_SCORE
            )
        )

        // Pass 2: Overlapping 2x2 + center tiles for high-resolution / group photos
        if (bitmap.width >= 800 && bitmap.height >= 800) {
            val w = bitmap.width.toFloat()
            val h = bitmap.height.toFloat()
            val tileW = w * 0.62f
            val tileH = h * 0.62f
            val tiles = listOf(
                RectF(0f, 0f, tileW, tileH),
                RectF(w - tileW, 0f, w, tileH),
                RectF(0f, h - tileH, tileW, h),
                RectF(w - tileW, h - tileH, w, h),
                RectF(w * 0.19f, h * 0.19f, w * 0.81f, h * 0.81f)
            )
            for (tile in tiles) {
                rawDetections.addAll(
                    runTileInference(
                        interp = interp,
                        source = bitmap,
                        tileRect = tile,
                        minScore = MIN_SCORE + 0.05f
                    )
                )
            }
        }

        if (rawDetections.isEmpty()) return emptyList()

        val merged = weightedNms(rawDetections)
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()

        merged.mapNotNull { det ->
            val clamped = RectF(
                det.box.left.coerceIn(0f, imgW - 1f),
                det.box.top.coerceIn(0f, imgH - 1f),
                det.box.right.coerceIn(1f, imgW),
                det.box.bottom.coerceIn(1f, imgH)
            )
            if (clamped.width() < MIN_FACE_SIZE_PX || clamped.height() < MIN_FACE_SIZE_PX) {
                return@mapNotNull null
            }

            val sharpness = computeLaplacianSharpness(bitmap, clamped)
            if (sharpness < MIN_LAPLACIAN_SHARPNESS) {
                return@mapNotNull null
            }

            // Ensure leftEye is image-left of rightEye relative to head orientation, and derive 5 ArcFace points
            val kp0 = det.kp0 // BlazeFace right eye (subject's right = image left)
            val kp1 = det.kp1 // BlazeFace left eye (subject's left = image right)
            val nose = det.kp2
            val mouthCenter = det.kp3

            val eyeDx = kp1.x - kp0.x
            val eyeDy = kp1.y - kp0.y
            val eyeDist = hypot(eyeDx, eyeDy)
            if (eyeDist < 8f) return@mapNotNull null

            // Canonical ArcFace mouth width is ~0.828 * inter-eye vector
            val halfMouthDx = eyeDx * 0.414f
            val halfMouthDy = eyeDy * 0.414f
            val mouthLeft = PointF(mouthCenter.x - halfMouthDx, mouthCenter.y - halfMouthDy)
            val mouthRight = PointF(mouthCenter.x + halfMouthDx, mouthCenter.y + halfMouthDy)

            DetectedFace(
                boundingBoxPx = clamped,
                normalizedBox = RectF(
                    clamped.left / imgW,
                    clamped.top / imgH,
                    clamped.right / imgW,
                    clamped.bottom / imgH
                ),
                landmarks5Px = arrayOf(kp0, kp1, nose, mouthLeft, mouthRight),
                confidence = det.score,
                sharpnessScore = sharpness
            )
        }
    }

    private data class RawDetection(
        val box: RectF,
        val kp0: PointF,
        val kp1: PointF,
        val kp2: PointF,
        val kp3: PointF,
        val score: Float
    )

    private fun runTileInference(
        interp: Interpreter,
        source: Bitmap,
        tileRect: RectF,
        minScore: Float
    ): List<RawDetection> {
        val tw = tileRect.width().toInt().coerceAtLeast(1)
        val th = tileRect.height().toInt().coerceAtLeast(1)
        val maxSide = max(tw, th).toFloat()
        val scale = INPUT_SIZE / maxSide
        val scaledW = (tw * scale).toInt().coerceIn(1, INPUT_SIZE)
        val scaledH = (th * scale).toInt().coerceIn(1, INPUT_SIZE)
        val padX = (INPUT_SIZE - scaledW) / 2f
        val padY = (INPUT_SIZE - scaledH) / 2f

        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.BLACK)
        val srcRect = android.graphics.Rect(
            tileRect.left.toInt(),
            tileRect.top.toInt(),
            tileRect.right.toInt(),
            tileRect.bottom.toInt()
        )
        val dstRect = RectF(padX, padY, padX + scaledW, padY + scaledH)
        canvas.drawBitmap(source, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

        letterboxed.getPixels(pixels128, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        letterboxed.recycle()

        inputBuffer.rewind()
        for (px in pixels128) {
            val r = ((px shr 16) and 0xFF)
            val g = ((px shr 8) and 0xFF)
            val b = (px and 0xFF)
            inputBuffer.putFloat((r - 127.5f) / 127.5f)
            inputBuffer.putFloat((g - 127.5f) / 127.5f)
            inputBuffer.putFloat((b - 127.5f) / 127.5f)
        }
        inputBuffer.rewind()

        val regressors = Array(1) { Array(NUM_ANCHORS) { FloatArray(16) } }
        val classificators = Array(1) { Array(NUM_ANCHORS) { FloatArray(1) } }

        val out0Shape = interp.getOutputTensor(0).shape()
        val outputs: Map<Int, Any> = if (out0Shape.last() == 16) {
            mapOf(0 to regressors, 1 to classificators)
        } else {
            mapOf(0 to classificators, 1 to regressors)
        }

        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val results = mutableListOf<RawDetection>()
        for (i in 0 until NUM_ANCHORS) {
            val rawLogit = classificators[0][i][0].coerceIn(-80f, 80f)
            val score = 1f / (1f + exp(-rawLogit))
            if (score < minScore) continue

            val anchorX = anchors[i * 2] * INPUT_SIZE
            val anchorY = anchors[i * 2 + 1] * INPUT_SIZE
            val reg = regressors[0][i]

            val cx = anchorX + reg[0]
            val cy = anchorY + reg[1]
            val w = reg[2]
            val h = reg[3]

            fun mapX(x128: Float): Float = tileRect.left + (x128 - padX) / scale
            fun mapY(y128: Float): Float = tileRect.top + (y128 - padY) / scale

            val left = mapX(cx - w * 0.5f)
            val top = mapY(cy - h * 0.5f)
            val right = mapX(cx + w * 0.5f)
            val bottom = mapY(cy + h * 0.5f)

            if (right <= left || bottom <= top) continue

            val kp0 = PointF(mapX(anchorX + reg[4]), mapY(anchorY + reg[5]))
            val kp1 = PointF(mapX(anchorX + reg[6]), mapY(anchorY + reg[7]))
            val kp2 = PointF(mapX(anchorX + reg[8]), mapY(anchorY + reg[9]))
            val kp3 = PointF(mapX(anchorX + reg[10]), mapY(anchorY + reg[11]))

            results.add(
                RawDetection(
                    box = RectF(left, top, right, bottom),
                    kp0 = kp0,
                    kp1 = kp1,
                    kp2 = kp2,
                    kp3 = kp3,
                    score = score
                )
            )
        }
        return results
    }

    private fun weightedNms(candidates: List<RawDetection>): List<RawDetection> {
        val remaining = candidates.sortedByDescending { it.score }.toMutableList()
        val output = mutableListOf<RawDetection>()

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val overlapping = mutableListOf(seed)
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val other = iter.next()
                if (iou(seed.box, other.box) > NMS_IOU_THRESHOLD) {
                    overlapping.add(other)
                    iter.remove()
                }
            }
            var totalWeight = 0f
            var l = 0f; var t = 0f; var r = 0f; var b = 0f
            var k0x = 0f; var k0y = 0f
            var k1x = 0f; var k1y = 0f
            var k2x = 0f; var k2y = 0f
            var k3x = 0f; var k3y = 0f
            for (d in overlapping) {
                val w = d.score
                totalWeight += w
                l += d.box.left * w
                t += d.box.top * w
                r += d.box.right * w
                b += d.box.bottom * w
                k0x += d.kp0.x * w; k0y += d.kp0.y * w
                k1x += d.kp1.x * w; k1y += d.kp1.y * w
                k2x += d.kp2.x * w; k2y += d.kp2.y * w
                k3x += d.kp3.x * w; k3y += d.kp3.y * w
            }
            output.add(
                RawDetection(
                    box = RectF(l / totalWeight, t / totalWeight, r / totalWeight, b / totalWeight),
                    kp0 = PointF(k0x / totalWeight, k0y / totalWeight),
                    kp1 = PointF(k1x / totalWeight, k1y / totalWeight),
                    kp2 = PointF(k2x / totalWeight, k2y / totalWeight),
                    kp3 = PointF(k3x / totalWeight, k3y / totalWeight),
                    score = seed.score
                )
            )
        }
        return output
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        if (interR <= interL || interB <= interT) return 0f
        val interArea = (interR - interL) * (interB - interT)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * Computes variance of discrete 3x3 Laplacian on a 48x48 grayscale crop to reject blurry faces.
     */
    private fun computeLaplacianSharpness(source: Bitmap, box: RectF): Float {
        val l = box.left.toInt().coerceIn(0, source.width - 1)
        val t = box.top.toInt().coerceIn(0, source.height - 1)
        val w = box.width().toInt().coerceIn(1, source.width - l)
        val h = box.height().toInt().coerceIn(1, source.height - t)
        val crop = Bitmap.createBitmap(source, l, t, w, h)
        val small = Bitmap.createScaledBitmap(crop, 48, 48, true)
        if (crop !== source && crop !== small) crop.recycle()

        val px = IntArray(48 * 48)
        small.getPixels(px, 0, 48, 0, 0, 48, 48)
        if (small !== source) small.recycle()

        val gray = FloatArray(48 * 48) { idx ->
            val c = px[idx]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until 47) {
            for (x in 1 until 47) {
                val idx = y * 48 + x
                val lap = -4f * gray[idx] +
                    gray[idx - 1] + gray[idx + 1] +
                    gray[idx - 48] + gray[idx + 48]
                sum += lap
                sumSq += (lap * lap)
                count++
            }
        }
        val mean = sum / count
        return (sumSq / count - mean * mean).toFloat().coerceAtLeast(0f)
    }

    /**
     * Generates the exact 896 MediaPipe BlazeFace Short-Range SSD normalized anchor centers:
     *  - Stride 8 (16x16 grid), 2 anchors per cell -> 512 anchors
     *  - Stride 16 (8x8 grid), 6 anchors per cell -> 384 anchors
     */
    private fun generateBlazeFaceAnchors(): FloatArray {
        val out = FloatArray(NUM_ANCHORS * 2)
        var idx = 0
        // Layer 0: 16x16, 2 anchors
        for (y in 0 until 16) {
            val cy = (y + 0.5f) / 16f
            for (x in 0 until 16) {
                val cx = (x + 0.5f) / 16f
                repeat(2) {
                    out[idx++] = cx
                    out[idx++] = cy
                }
            }
        }
        // Layer 1: 8x8, 6 anchors
        for (y in 0 until 8) {
            val cy = (y + 0.5f) / 8f
            for (x in 0 until 8) {
                val cx = (x + 0.5f) / 8f
                repeat(6) {
                    out[idx++] = cx
                    out[idx++] = cy
                }
            }
        }
        return out
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
