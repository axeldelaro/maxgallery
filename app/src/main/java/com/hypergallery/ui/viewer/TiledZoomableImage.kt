package com.hypergallery.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.security.EncryptedVaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class TileKey(
    val sampleSize: Int,
    val tileX: Int,
    val tileY: Int
)

/**
 * High-resolution sub-sampled tiled image viewer powered by [BitmapRegionDecoder].
 *  - Supports surgical zoom up to 25x on 50–200 MP photos & panoramas without OutOfMemoryError
 *  - 3-tier smart double-tap zoom (1x -> 3x -> 8x -> 1x)
 *  - Optional two-finger rotation
 *  - Vertical swipe-to-dismiss & HorizontalPager compatibility at 1x scale
 */
@Composable
fun TiledZoomableImage(
    media: MediaEntity,
    vaultManager: EncryptedVaultManager,
    enableRotationGesture: Boolean,
    onSingleTap: () -> Unit,
    onSwipeVerticalDismiss: (Float) -> Unit,
    onSwipeVerticalEnd: (Boolean) -> Unit,
    onSwipeUpInspector: () -> Unit,
    modifier: Modifier = Modifier
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var baseBitmap by remember(media.path) { mutableStateOf<Bitmap?>(null) }
    var regionDecoder by remember(media.path) { mutableStateOf<BitmapRegionDecoder?>(null) }
    var fullWidth by remember(media.path) { mutableStateOf(1) }
    var fullHeight by remember(media.path) { mutableStateOf(1) }

    var scale by remember(media.path) { mutableFloatStateOf(1f) }
    var offsetX by remember(media.path) { mutableFloatStateOf(0f) }
    var offsetY by remember(media.path) { mutableFloatStateOf(0f) }
    var rotationDeg by remember(media.path) { mutableFloatStateOf(0f) }

    val decodedTiles = remember(media.path) { mutableStateMapOf<TileKey, Bitmap>() }

    DisposableEffect(media.path) {
        onDispose {
            decodedTiles.values.forEach { if (!it.isRecycled) it.recycle() }
            decodedTiles.clear()
            runCatching { regionDecoder?.recycle() }
            regionDecoder = null
        }
    }

    // Load base preview + initialize BitmapRegionDecoder off main thread
    LaunchedEffect(media.path) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (media.uri.startsWith("hgvault://")) {
                    val bytes = vaultManager.decryptImageBytesInMemory(File(media.path))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    fullWidth = bounds.outWidth.coerceAtLeast(1)
                    fullHeight = bounds.outHeight.coerceAtLeast(1)

                    var sample = 1
                    while (max(fullWidth, fullHeight) / (sample * 2) >= 1600) sample *= 2
                    baseBitmap = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                    regionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes))
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
                    }
                } else if (media.isRaw) {
                    val jpegBytes = RawPreviewExtractor.extractBestJpegBytes(File(media.path))
                    if (jpegBytes != null) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
                        fullWidth = bounds.outWidth.coerceAtLeast(1)
                        fullHeight = bounds.outHeight.coerceAtLeast(1)
                        var sample = 1
                        while (max(fullWidth, fullHeight) / (sample * 2) >= 1600) sample *= 2
                        baseBitmap = BitmapFactory.decodeByteArray(
                            jpegBytes, 0, jpegBytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                        regionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BitmapRegionDecoder.newInstance(ByteArrayInputStream(jpegBytes))
                        } else {
                            @Suppress("DEPRECATION")
                            BitmapRegionDecoder.newInstance(ByteArrayInputStream(jpegBytes), false)
                        }
                    } else {
                        val bmp = RawPreviewExtractor.decodePreviewBitmap(File(media.path), 2048)
                        baseBitmap = bmp
                        fullWidth = bmp?.width ?: 1
                        fullHeight = bmp?.height ?: 1
                    }
                } else {
                    val file = File(media.path)
                    if (!file.exists()) return@runCatching
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    fullWidth = bounds.outWidth.coerceAtLeast(1)
                    fullHeight = bounds.outHeight.coerceAtLeast(1)

                    var sample = 1
                    while (max(fullWidth, fullHeight) / (sample * 2) >= 1600) sample *= 2
                    baseBitmap = BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    )

                    if (media.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")) {
                        regionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            BitmapRegionDecoder.newInstance(file.absolutePath)
                        } else {
                            @Suppress("DEPRECATION")
                            BitmapRegionDecoder.newInstance(file.absolutePath, false)
                        }
                    }
                }
            }
        }
    }

    // Decode visible high-resolution tiles dynamically when zoomed > 1.35x
    val decoder = regionDecoder
    LaunchedEffect(scale, offsetX, offsetY, viewportSize, decoder) {
        if (decoder == null || scale <= 1.35f || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return@LaunchedEffect
        }
        val fitScale = min(
            viewportSize.width.toFloat() / fullWidth.toFloat(),
            viewportSize.height.toFloat() / fullHeight.toFloat()
        )
        val effectivePixelRatio = 1f / (fitScale * scale)
        var sample = 1
        while (sample * 2 <= effectivePixelRatio) {
            sample *= 2
        }
        sample = sample.coerceIn(1, 16)
        val tileSrcSpan = 512 * sample

        val drawnW = fullWidth * fitScale * scale
        val drawnH = fullHeight * fitScale * scale
        val imgLeftOnScreen = (viewportSize.width - drawnW) / 2f + offsetX
        val imgTopOnScreen = (viewportSize.height - drawnH) / 2f + offsetY

        val visLeftSrc = ((-imgLeftOnScreen / (fitScale * scale)).toInt()).coerceIn(0, fullWidth)
        val visTopSrc = ((-imgTopOnScreen / (fitScale * scale)).toInt()).coerceIn(0, fullHeight)
        val visRightSrc = (((viewportSize.width - imgLeftOnScreen) / (fitScale * scale)).toInt()).coerceIn(0, fullWidth)
        val visBottomSrc = (((viewportSize.height - imgTopOnScreen) / (fitScale * scale)).toInt()).coerceIn(0, fullHeight)

        val startTileX = visLeftSrc / tileSrcSpan
        val endTileX = visRightSrc / tileSrcSpan
        val startTileY = visTopSrc / tileSrcSpan
        val endTileY = visBottomSrc / tileSrcSpan

        withContext(Dispatchers.IO) {
            for (ty in startTileY..endTileY) {
                for (tx in startTileX..endTileX) {
                    val key = TileKey(sample, tx, ty)
                    if (decodedTiles.containsKey(key)) continue
                    val rectLeft = tx * tileSrcSpan
                    val rectTop = ty * tileSrcSpan
                    if (rectLeft >= fullWidth || rectTop >= fullHeight) continue
                    val rect = Rect(
                        rectLeft,
                        rectTop,
                        min(rectLeft + tileSrcSpan, fullWidth),
                        min(rectTop + tileSrcSpan, fullHeight)
                    )
                    if (rect.width() <= 0 || rect.height() <= 0) continue
                    val bmp = synchronized(decoder) {
                        runCatching {
                            decoder.decodeRegion(
                                rect,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                }
                            )
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        if (decodedTiles.size > 48) {
                            val evict = decodedTiles.keys.firstOrNull { it.sampleSize != sample }
                                ?: decodedTiles.keys.firstOrNull()
                            evict?.let { decodedTiles.remove(it) }
                        }
                        decodedTiles[key] = bmp
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(media.path) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { tapOffset ->
                        // 3-tier smart zoom: 1x -> 3x -> 8x -> 1x
                        val nextScale = when {
                            scale < 2.2f -> 3f
                            scale < 6.5f -> 8f
                            else -> 1f
                        }
                        if (nextScale == 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            rotationDeg = 0f
                        } else {
                            val cx = viewportSize.width / 2f
                            val cy = viewportSize.height / 2f
                            val factor = nextScale / scale
                            offsetX = (offsetX - (tapOffset.x - cx)) * factor + (tapOffset.x - cx)
                            offsetY = (offsetY - (tapOffset.y - cy)) * factor + (tapOffset.y - cy)
                            scale = nextScale
                        }
                    }
                )
            }
            .pointerInput(media.path, enableRotationGesture) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalDismissDrag = 0f
                    var isDismissGesture = false

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val rotChange = if (enableRotationGesture) event.calculateRotation() else 0f

                            val newScale = (scale * zoomChange).coerceIn(1f, 25f)
                            scale = newScale
                            if (enableRotationGesture && newScale > 1.05f) {
                                rotationDeg += rotChange
                            }
                            offsetX += panChange.x
                            offsetY += panChange.y
                            clampOffsets(
                                viewportSize = viewportSize,
                                fullW = fullWidth,
                                fullH = fullHeight,
                                scale = scale,
                                currentX = offsetX,
                                currentY = offsetY
                            ).let { (cx, cy) ->
                                offsetX = cx
                                offsetY = cy
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val pan = event.calculatePan()
                            if (scale > 1.02f) {
                                offsetX += pan.x
                                offsetY += pan.y
                                val (clampedX, clampedY) = clampOffsets(
                                    viewportSize, fullWidth, fullHeight, scale, offsetX, offsetY
                                )
                                val hitHorizontalEdge = abs(clampedX - offsetX) > 0.5f && abs(pan.x) > abs(pan.y)
                                offsetX = clampedX
                                offsetY = clampedY
                                if (!hitHorizontalEdge) {
                                    event.changes.forEach { it.consume() }
                                }
                            } else {
                                // At 1x zoom: allow HorizontalPager to swipe horizontally,
                                // while handling vertical swipe down (dismiss) & swipe up (EXIF inspector)!
                                if (abs(pan.y) > abs(pan.x) * 1.35f) {
                                    verticalDismissDrag += pan.y
                                    if (verticalDismissDrag > 18f) {
                                        isDismissGesture = true
                                        onSwipeVerticalDismiss(verticalDismissDrag)
                                        event.changes.forEach { it.consume() }
                                    } else if (verticalDismissDrag < -95f) {
                                        verticalDismissDrag = 0f
                                        onSwipeUpInspector()
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isDismissGesture) {
                        onSwipeVerticalEnd(verticalDismissDrag > 210f)
                    }
                    if (scale <= 1.02f) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                        rotationDeg = 0f
                    }
                }
            }
    ) {
        val base = baseBitmap
        if (base != null && viewportSize.width > 0 && viewportSize.height > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val fitScale = min(
                    size.width / fullWidth.toFloat(),
                    size.height / fullHeight.toFloat()
                )
                val destW = fullWidth * fitScale
                val destH = fullHeight * fitScale
                val left0 = (size.width - destW) / 2f
                val top0 = (size.height - destH) / 2f

                withTransform({
                    translate(left = offsetX, top = offsetY)
                    scale(scaleX = scale, scaleY = scale, pivot = Offset(size.width / 2f, size.height / 2f))
                    if (rotationDeg != 0f) {
                        rotate(degrees = rotationDeg, pivot = Offset(size.width / 2f, size.height / 2f))
                    }
                }) {
                    // Base full-frame layer
                    drawImage(
                        image = base.asImageBitmap(),
                        dstOffset = IntOffset(left0.roundToInt(), top0.roundToInt()),
                        dstSize = IntSize(destW.roundToInt().coerceAtLeast(1), destH.roundToInt().coerceAtLeast(1))
                    )

                    // Crisp high-res tiles on top when zoomed in
                    if (scale > 1.35f && decodedTiles.isNotEmpty()) {
                        val effectivePixelRatio = 1f / (fitScale * scale)
                        var targetSample = 1
                        while (targetSample * 2 <= effectivePixelRatio) targetSample *= 2
                        targetSample = targetSample.coerceIn(1, 16)
                        val tileSrcSpan = 512 * targetSample

                        for ((key, tileBmp) in decodedTiles) {
                            if (key.sampleSize != targetSample || tileBmp.isRecycled) continue
                            val srcL = key.tileX * tileSrcSpan
                            val srcT = key.tileY * tileSrcSpan
                            val srcR = min(srcL + tileSrcSpan, fullWidth)
                            val srcB = min(srcT + tileSrcSpan, fullHeight)

                            val dstL = left0 + srcL * fitScale
                            val dstT = top0 + srcT * fitScale
                            val dstW = (srcR - srcL) * fitScale
                            val dstH = (srcB - srcT) * fitScale

                            drawImage(
                                image = tileBmp.asImageBitmap(),
                                dstOffset = IntOffset(dstL.roundToInt(), dstT.roundToInt()),
                                dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), dstH.roundToInt().coerceAtLeast(1))
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun clampOffsets(
    viewportSize: IntSize,
    fullW: Int,
    fullH: Int,
    scale: Float,
    currentX: Float,
    currentY: Float
): Pair<Float, Float> {
    if (viewportSize.width <= 0 || viewportSize.height <= 0) return 0f to 0f
    val fitScale = min(
        viewportSize.width.toFloat() / fullW.toFloat(),
        viewportSize.height.toFloat() / fullH.toFloat()
    )
    val scaledW = fullW * fitScale * scale
    val scaledH = fullH * fitScale * scale
    val maxX = ((scaledW - viewportSize.width) / 2f).coerceAtLeast(0f)
    val maxY = ((scaledH - viewportSize.height) / 2f).coerceAtLeast(0f)
    return currentX.coerceIn(-maxX, maxX) to currentY.coerceIn(-maxY, maxY)
}
