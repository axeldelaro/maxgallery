package com.hypergallery.ai

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.sqrt

/**
 * Hardware-accelerated MobileFaceNet embedding extractor (assets/models/mobilefacenet.tflite).
 * Input:  [1, 112, 112, 3] FLOAT32 normalized via (px - 127.5f) / 128.0f
 * Output: [1, 192] L2-normalized unit vector stored as little-endian BLOB in Room.
 */
@Singleton
class FaceEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_ASSET_PATH = "models/mobilefacenet.tflite"
        private const val INPUT_SIZE = 112

        fun floatArrayToBlob(vector: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vector) buffer.putFloat(v)
            return buffer.array()
        }

        fun blobToFloatArray(blob: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val count = blob.size / 4
            return FloatArray(count) { buffer.getFloat() }
        }

        fun l2Normalize(vec: FloatArray): FloatArray {
            var normSq = 0f
            for (v in vec) normSq += v * v
            val norm = sqrt(normSq).coerceAtLeast(1e-10f)
            return FloatArray(vec.size) { i -> vec[i] / norm }
        }

        /**
         * Cosine distance between two L2-normalized unit vectors: d(u, v) = 1 - (u · v) in [0, 2].
         */
        fun cosineDistance(unitA: FloatArray, unitB: FloatArray): Float {
            val n = minOf(unitA.size, unitB.size)
            var dot = 0f
            for (i in 0 until n) {
                dot += unitA[i] * unitB[i]
            }
            return (1f - dot.coerceIn(-1f, 1f)).coerceAtLeast(0f)
        }
    }

    private val lock = Any()
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var embeddingDim: Int = 192

    private val inputBuffer: ByteBuffer by lazy {
        ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
    }
    private val pixels112 = IntArray(INPUT_SIZE * INPUT_SIZE)

    private fun ensureInterpreter(): Interpreter {
        interpreter?.let { return it }
        val mapped = loadModelFile(MODEL_ASSET_PATH)

        runCatching {
            val gpu = GpuDelegate()
            val interp = Interpreter(mapped, Interpreter.Options().addDelegate(gpu).setNumThreads(4))
            gpuDelegate = gpu
            embeddingDim = interp.getOutputTensor(0).shape().last()
            interpreter = interp
            return interp
        }

        runCatching {
            val nnapi = NnApiDelegate()
            val interp = Interpreter(mapped, Interpreter.Options().addDelegate(nnapi).setNumThreads(4))
            nnApiDelegate = nnapi
            embeddingDim = interp.getOutputTensor(0).shape().last()
            interpreter = interp
            return interp
        }

        val interp = Interpreter(
            mapped,
            Interpreter.Options().setNumThreads(4).setUseXNNPACK(true)
        )
        embeddingDim = interp.getOutputTensor(0).shape().last()
        interpreter = interp
        return interp
    }

    fun extractEmbedding(aligned112: Bitmap): FloatArray = synchronized(lock) {
        val interp = ensureInterpreter()
        val ready = if (aligned112.width == INPUT_SIZE && aligned112.height == INPUT_SIZE) {
            aligned112
        } else {
            Bitmap.createScaledBitmap(aligned112, INPUT_SIZE, INPUT_SIZE, true)
        }

        ready.getPixels(pixels112, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (ready !== aligned112) ready.recycle()

        inputBuffer.rewind()
        for (px in pixels112) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            inputBuffer.putFloat((r - 127.5f) / 128.0f)
            inputBuffer.putFloat((g - 127.5f) / 128.0f)
            inputBuffer.putFloat((b - 127.5f) / 128.0f)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(embeddingDim) }
        interp.run(inputBuffer, output)
        return l2Normalize(output[0])
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        context.assets.openFd(assetPath).use { afd ->
            FileInputStream(afd.fileDescriptor).channel.use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }
}
