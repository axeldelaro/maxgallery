package com.hypergallery.ui.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.data.local.entity.PersonClusterEntity
import com.hypergallery.data.media.RawPreviewExtractor
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class HistogramData(
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
    val luma: FloatArray
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExifInspectorSheet(
    media: MediaEntity,
    facesOnPhoto: List<FaceEntity>,
    personsMap: Map<Long, PersonClusterEntity>,
    onDismiss: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateToPerson: (Long) -> Unit,
    onStripExif: () -> Unit
) {
    var histogram by remember(media.path) { mutableStateOf<HistogramData?>(null) }

    LaunchedEffect(media.path) {
        if (!media.isVideo && !media.uri.startsWith("hgvault://")) {
            histogram = withContext(Dispatchers.Default) {
                computeRgbLumaHistogram(media)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Inspecteur Technique Pro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Live RGB + Luminance Histogram
            val hist = histogram
            if (hist != null) {
                Surface(
                    color = Color(0xFF0A0D12),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Histogramme RVB & Luminance",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        ) {
                            fun drawCurve(bins: FloatArray, color: Color, strokeWidth: Float) {
                                val path = Path()
                                val stepX = size.width / 255f
                                path.moveTo(0f, size.height)
                                for (i in 0..255) {
                                    val x = i * stepX
                                    val y = size.height * (1f - bins[i].coerceIn(0f, 1f))
                                    path.lineTo(x, y)
                                }
                                drawPath(path, color = color, style = Stroke(width = strokeWidth))
                            }
                            drawCurve(hist.luma, Color.White.copy(alpha = 0.75f), 2.5f)
                            drawCurve(hist.r, Color(0xFFFF5252), 2f)
                            drawCurve(hist.g, Color(0xFF69F0AE), 2f)
                            drawCurve(hist.b, Color(0xFF448AFF), 2f)
                        }
                    }
                }
            }

            // File & Clickable Path
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!media.uri.startsWith("hgvault://")) {
                            onDismiss()
                            onNavigateToFolder(media.parentPath)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.fileName,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = media.path,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Technical Specs Badges
            val formattedDate = remember(media.dateTaken) {
                SimpleDateFormat("dd MMMM yyyy • HH:mm:ss", Locale.FRANCE).format(Date(media.dateTaken))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetaPill("Date", formattedDate)
                MetaPill("Taille", ByteFormatting.formatSize(media.sizeBytes))
                if (media.width > 0 && media.height > 0) {
                    MetaPill(
                        "Définition",
                        "${media.width}×${media.height} (${String.format(Locale.US, "%.1f MP", media.megapixels)})"
                    )
                }
                MetaPill("Format", media.mimeType.uppercase(Locale.ROOT))
                if (media.isVideo && media.durationMs > 0) {
                    MetaPill("Durée", ByteFormatting.formatDuration(media.durationMs))
                }
                media.exifMake?.takeIf { it.isNotBlank() }?.let { MetaPill("Marque", it) }
                media.exifModel?.takeIf { it.isNotBlank() }?.let { MetaPill("Boîtier", it) }
                media.exifLens?.takeIf { it.isNotBlank() }?.let { MetaPill("Objectif", it) }
                media.exifFocalLength?.let { MetaPill("Focale", it) }
                media.exifAperture?.let { MetaPill("Ouverture", it) }
                media.exifShutterSpeed?.let { MetaPill("Vitesse", it) }
                media.exifIso?.let { MetaPill("ISO", "ISO $it") }
                media.exifFlash?.let { MetaPill("Flash", it) }
                if (media.gpsLat != null && media.gpsLon != null) {
                    MetaPill(
                        "Coordonnées GPS",
                        String.format(Locale.US, "%.5f, %.5f", media.gpsLat, media.gpsLon)
                    )
                }
            }

            // Clickable AI Recognized Faces on this Photo
            if (facesOnPhoto.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Visages reconnus sur cette photo (${facesOnPhoto.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(facesOnPhoto, key = { it.faceId }) { face ->
                        val person = face.personId?.let { personsMap[it] }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                face.personId?.let { pid ->
                                    onDismiss()
                                    onNavigateToPerson(pid)
                                }
                            }
                        ) {
                            AsyncImage(
                                model = File(face.cropPath),
                                contentDescription = person?.name ?: "Visage",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = person?.name?.ifBlank { "Personne #${person.personId}" } ?: "Non classé",
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Clean EXIF / GPS Privacy Button
            if (!media.isVideo && !media.uri.startsWith("hgvault://")) {
                HorizontalDivider()
                FilledTonalButton(
                    onClick = onStripExif,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CleaningServices, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nettoyer les métadonnées EXIF / GPS")
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MetaPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun computeRgbLumaHistogram(media: MediaEntity): HistogramData? {
    return runCatching {
        val file = File(media.path)
        if (!file.exists()) return null
        val bmp = if (media.isRaw) {
            RawPreviewExtractor.decodePreviewBitmap(file, targetMaxSide = 384)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 384) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rBins = IntArray(256)
        val gBins = IntArray(256)
        val bBins = IntArray(256)
        val yBins = IntArray(256)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val y = ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
            rBins[r]++
            gBins[g]++
            bBins[b]++
            yBins[y]++
        }

        // Ignore extreme 0 and 255 clipping spikes for smooth visual normalization
        var peak = 1
        for (i in 2..253) {
            peak = maxOf(peak, rBins[i], gBins[i], bBins[i], yBins[i])
        }
        val denom = peak.toFloat()
        HistogramData(
            r = FloatArray(256) { (rBins[it] / denom).coerceAtMost(1f) },
            g = FloatArray(256) { (gBins[it] / denom).coerceAtMost(1f) },
            b = FloatArray(256) { (bBins[it] / denom).coerceAtMost(1f) },
            luma = FloatArray(256) { (yBins[it] / denom).coerceAtMost(1f) }
        )
    }.getOrNull()
}
