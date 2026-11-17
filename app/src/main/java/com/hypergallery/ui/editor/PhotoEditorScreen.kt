package com.hypergallery.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hypergallery.data.files.GalleryFileManager
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.data.scanner.HybridMediaScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class CropAspectPreset(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    FREE("Libre (90%)", 0.0f),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    NINE_SIXTEEN("9:16", 9f / 16f)
}

enum class FilterPreset(val label: String) {
    NONE("Original"),
    VIVID("Vivide"),
    MONO("Noir & Blanc"),
    CINEMA("Cinéma"),
    WARM_FILM("Argentique"),
    COOL("Froid")
}

@Composable
fun PhotoEditorScreen(
    media: MediaEntity,
    fileManager: GalleryFileManager,
    scanner: HybridMediaScanner,
    onClose: () -> Unit,
    onSaved: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember(media.path) { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var activeTab by remember { mutableIntStateOf(0) }
    var cropPreset by remember { mutableStateOf(CropAspectPreset.ORIGINAL) }
    var freeCropInset by remember { mutableFloatStateOf(0f) }
    var quarterTurns by remember { mutableIntStateOf(0) }
    var fineStraightenDeg by remember { mutableFloatStateOf(0f) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }

    var brightness by remember { mutableFloatStateOf(0f) } // -100..100
    var contrast by remember { mutableFloatStateOf(1f) }   // 0.5..1.8
    var saturation by remember { mutableFloatStateOf(1f) } // 0..2.0
    var warmth by remember { mutableFloatStateOf(0f) }     // -50..50
    var sharpness by remember { mutableFloatStateOf(0f) }  // 0..1.0
    var filterPreset by remember { mutableStateOf(FilterPreset.NONE) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(media.path) {
        sourceBitmap = withContext(Dispatchers.IO) {
            val file = File(media.path)
            if (media.isRaw) {
                RawPreviewExtractor.decodePreviewBitmap(file, 2048)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }

    LaunchedEffect(
        sourceBitmap, cropPreset, freeCropInset, quarterTurns, fineStraightenDeg,
        flipH, flipV, brightness, contrast, saturation, warmth, sharpness, filterPreset
    ) {
        val src = sourceBitmap ?: return@LaunchedEffect
        previewBitmap = withContext(Dispatchers.Default) {
            renderEditedBitmap(
                source = src,
                cropPreset = cropPreset,
                freeCropInset = freeCropInset,
                quarterTurns = quarterTurns,
                fineStraightenDeg = fineStraightenDeg,
                flipH = flipH,
                flipV = flipV,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                warmth = warmth,
                sharpness = sharpness,
                filterPreset = filterPreset,
                applyHeavySharpen = sharpness > 0.05f
            )
        }
    }

    fun saveImage(overwriteOriginal: Boolean) {
        val currentSrc = sourceBitmap ?: return
        if (isSaving) return
        isSaving = true
        scope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                val finalBmp = renderEditedBitmap(
                    source = currentSrc,
                    cropPreset = cropPreset,
                    freeCropInset = freeCropInset,
                    quarterTurns = quarterTurns,
                    fineStraightenDeg = fineStraightenDeg,
                    flipH = flipH,
                    flipV = flipV,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    warmth = warmth,
                    sharpness = sharpness,
                    filterPreset = filterPreset,
                    applyHeavySharpen = true
                )
                val origFile = File(media.path)
                val targetFile = if (overwriteOriginal && !media.isRaw) {
                    origFile
                } else {
                    fileManager.resolveNonCollidingFile(
                        File(origFile.parentFile, "${origFile.nameWithoutExtension}_edit.jpg")
                    )
                }
                val ok = runCatching {
                    targetFile.outputStream().buffered().use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    true
                }.getOrDefault(false)
                if (ok) {
                    fileManager.notifyMediaScanner(listOf(targetFile.absolutePath))
                    scanner.scanAllMedia()
                    targetFile.name
                } else null
            }
            isSaving = false
            if (savedPath != null) {
                onSaved("Image enregistrée : $savedPath")
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Éditeur Pro", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                },
                actions = {
                    if (!media.isRaw) {
                        OutlinedButton(
                            onClick = { saveImage(overwriteOriginal = true) },
                            enabled = !isSaving,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text("Écraser", fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = { saveImage(overwriteOriginal = false) },
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Copie")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Aperçu édition",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }
            }

            Surface(
                color = Color(0xFF10141C),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    TabRow(
                        selectedTabIndex = activeTab,
                        containerColor = Color(0xFF10141C),
                        contentColor = Color.White
                    ) {
                        Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Recadrer") })
                        Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Ajuster") })
                        Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("Filtres") })
                    }

                    Spacer(Modifier.height(10.dp))

                    when (activeTab) {
                        0 -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CropAspectPreset.entries.forEach { preset ->
                                        FilterChip(
                                            selected = cropPreset == preset,
                                            onClick = { cropPreset = preset },
                                            label = { Text(preset.label) }
                                        )
                                    }
                                }
                                if (cropPreset == CropAspectPreset.FREE) {
                                    EditorSliderRow("Marge recadrage libre", freeCropInset, 0f..0.35f) {
                                        freeCropInset = it
                                    }
                                }
                                EditorSliderRow("Redressement fin (${fineStraightenDeg.toInt()}°)", fineStraightenDeg, -45f..45f) {
                                    fineStraightenDeg = it
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    OutlinedButton(onClick = { quarterTurns = (quarterTurns + 1) % 4 }) {
                                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("90°")
                                    }
                                    OutlinedButton(onClick = { flipH = !flipH }) {
                                        Icon(Icons.Filled.Flip, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Miroir H")
                                    }
                                    OutlinedButton(onClick = { flipV = !flipV }) {
                                        Icon(Icons.Filled.Flip, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Miroir V")
                                    }
                                }
                            }
                        }

                        1 -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                EditorSliderRow("Luminosité", brightness, -80f..80f) { brightness = it }
                                EditorSliderRow("Contraste", contrast, 0.5f..1.7f) { contrast = it }
                                EditorSliderRow("Saturation", saturation, 0f..2.0f) { saturation = it }
                                EditorSliderRow("Chaleur", warmth, -45f..45f) { warmth = it }
                                EditorSliderRow("Netteté", sharpness, 0f..1.0f) { sharpness = it }
                            }
                        }

                        2 -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilterPreset.entries.forEach { preset ->
                                    FilterChip(
                                        selected = filterPreset == preset,
                                        onClick = { filterPreset = preset },
                                        label = {
                                            Text(
                                                preset.label,
                                                fontWeight = if (filterPreset == preset) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(label, color = Color.White, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(28.dp)
        )
    }
}

private fun renderEditedBitmap(
    source: Bitmap,
    cropPreset: CropAspectPreset,
    freeCropInset: Float,
    quarterTurns: Int,
    fineStraightenDeg: Float,
    flipH: Boolean,
    flipV: Boolean,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    warmth: Float,
    sharpness: Float,
    filterPreset: FilterPreset,
    applyHeavySharpen: Boolean
): Bitmap {
    // 1. Geometric transformation (90° turns + fine rotation + mirror)
    val geoMatrix = Matrix().apply {
        val sx = if (flipH) -1f else 1f
        val sy = if (flipV) -1f else 1f
        postScale(sx, sy, source.width / 2f, source.height / 2f)
        postRotate(quarterTurns * 90f + fineStraightenDeg, source.width / 2f, source.height / 2f)
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, geoMatrix, true)

    // 2. Aspect ratio / free crop
    val rw = rotated.width
    val rh = rotated.height
    val cropped = when {
        cropPreset == CropAspectPreset.FREE && freeCropInset > 0.01f -> {
            val insetX = (rw * freeCropInset * 0.5f).toInt()
            val insetY = (rh * freeCropInset * 0.5f).toInt()
            Bitmap.createBitmap(
                rotated,
                insetX,
                insetY,
                (rw - 2 * insetX).coerceAtLeast(16),
                (rh - 2 * insetY).coerceAtLeast(16)
            )
        }
        cropPreset.ratio != null && cropPreset.ratio > 0f -> {
            val targetRatio = cropPreset.ratio
            val currentRatio = rw.toFloat() / rh.toFloat()
            if (currentRatio > targetRatio) {
                val newW = (rh * targetRatio).toInt().coerceIn(1, rw)
                Bitmap.createBitmap(rotated, (rw - newW) / 2, 0, newW, rh)
            } else {
                val newH = (rw / targetRatio).toInt().coerceIn(1, rh)
                Bitmap.createBitmap(rotated, 0, (rh - newH) / 2, rw, newH)
            }
        }
        else -> rotated
    }
    if (cropped !== rotated && rotated !== source) rotated.recycle()

    // 3. ColorMatrix (Brightness + Contrast + Saturation + Warmth + Preset Filter)
    val cm = ColorMatrix()
    val effectiveSat = when (filterPreset) {
        FilterPreset.MONO -> 0f
        FilterPreset.VIVID -> saturation * 1.35f
        FilterPreset.CINEMA -> saturation * 0.88f
        else -> saturation
    }
    cm.setSaturation(effectiveSat)

    val effContrast = when (filterPreset) {
        FilterPreset.VIVID -> contrast * 1.12f
        FilterPreset.MONO -> contrast * 1.18f
        FilterPreset.CINEMA -> contrast * 1.15f
        else -> contrast
    }
    val translate = (1f - effContrast) * 128f + brightness
    val rWarm = warmth + when (filterPreset) {
        FilterPreset.WARM_FILM -> 18f
        FilterPreset.COOL -> -16f
        else -> 0f
    }
    val bWarm = -warmth + when (filterPreset) {
        FilterPreset.COOL -> 20f
        FilterPreset.CINEMA -> 12f
        FilterPreset.WARM_FILM -> -14f
        else -> 0f
    }

    val adjustMatrix = ColorMatrix(
        floatArrayOf(
            effContrast, 0f, 0f, 0f, translate + rWarm,
            0f, effContrast, 0f, 0f, translate,
            0f, 0f, effContrast, 0f, translate + bWarm,
            0f, 0f, 0f, 1f, 0f
        )
    )
    cm.postConcat(adjustMatrix)

    val colored = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(colored)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(cm)
    }
    canvas.drawBitmap(cropped, 0f, 0f, paint)
    if (cropped !== source && cropped !== colored) cropped.recycle()

    // 4. Optional 3x3 Unsharp Convolution Kernel for Crisp Sharpness
    if (applyHeavySharpen && sharpness > 0.05f) {
        return applySharpenConvolution(colored, sharpness * 0.65f)
    }
    return colored
}

private fun applySharpenConvolution(src: Bitmap, amount: Float): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 4 || h < 4) return src
    val inPx = IntArray(w * h)
    val outPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)
    System.arraycopy(inPx, 0, outPx, 0, inPx.size)

    val centerWeight = 1f + 4f * amount
    val neighborWeight = -amount

    for (y in 1 until h - 1) {
        val row = y * w
        for (x in 1 until w - 1) {
            val i = row + x
            val c = inPx[i]
            val l = inPx[i - 1]
            val r = inPx[i + 1]
            val u = inPx[i - w]
            val d = inPx[i + w]

            val nr = (centerWeight * ((c shr 16) and 0xFF) +
                neighborWeight * (((l shr 16) and 0xFF) + ((r shr 16) and 0xFF) + ((u shr 16) and 0xFF) + ((d shr 16) and 0xFF)))
                .toInt().coerceIn(0, 255)
            val ng = (centerWeight * ((c shr 8) and 0xFF) +
                neighborWeight * (((l shr 8) and 0xFF) + ((r shr 8) and 0xFF) + ((u shr 8) and 0xFF) + ((d shr 8) and 0xFF)))
                .toInt().coerceIn(0, 255)
            val nb = (centerWeight * (c and 0xFF) +
                neighborWeight * ((l and 0xFF) + (r and 0xFF) + (u and 0xFF) + (d and 0xFF)))
                .toInt().coerceIn(0, 255)

            outPx[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(outPx, 0, w, 0, 0, w, h)
    src.recycle()
    return out
}
