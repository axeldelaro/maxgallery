package com.hypergallery.ui.slideshow

import android.app.Activity
import android.net.Uri
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.hypergallery.data.local.entity.FaceEntity
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.domain.model.SlideshowConfig
import com.hypergallery.domain.model.SlideshowTransition
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SlideshowScreen(
    sourceItems: List<MediaEntity>,
    config: SlideshowConfig,
    onUpdateConfig: (SlideshowConfig) -> Unit,
    loadFacesForMedia: suspend (Long) -> List<FaceEntity>,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val playlist = remember(sourceItems, config.includeVideos, config.shuffle) {
        val filtered = if (config.includeVideos) sourceItems else sourceItems.filter { !it.isVideo }
        if (config.shuffle) filtered.shuffled() else filtered
    }

    var currentIndex by remember(playlist) { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }

    // Face centroid normalized target for smart Ken Burns framing!
    var faceFocusX by remember { mutableFloatStateOf(0.5f) }
    var faceFocusY by remember { mutableFloatStateOf(0.45f) }

    val currentMedia = playlist.getOrNull(currentIndex)

    LaunchedEffect(currentMedia?.id) {
        val m = currentMedia ?: return@LaunchedEffect
        if (!m.isVideo && m.id > 0) {
            val faces = loadFacesForMedia(m.id)
            if (faces.isNotEmpty()) {
                faceFocusX = faces.map { (it.leftNorm + it.rightNorm) * 0.5f }.average().toFloat().coerceIn(0.15f, 0.85f)
                faceFocusY = faces.map { (it.topNorm + it.bottomNorm) * 0.5f }.average().toFloat().coerceIn(0.15f, 0.85f)
            } else {
                faceFocusX = 0.5f
                faceFocusY = 0.5f
            }
        }
    }

    fun advanceNext() {
        if (playlist.isEmpty()) return
        if (currentIndex + 1 < playlist.size) {
            currentIndex++
        } else if (config.loop) {
            currentIndex = 0
        } else {
            onExit()
        }
    }

    fun goPrevious() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.lastIndex
    }

    // Automatic timer for static/animated photos (videos advance when ExoPlayer reaches STATE_ENDED)
    LaunchedEffect(currentIndex, isPaused, config.intervalMs, currentMedia?.isVideo) {
        if (currentMedia == null || isPaused || currentMedia.isVideo) return@LaunchedEffect
        delay(config.intervalMs.coerceAtLeast(500L))
        advanceNext()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showOverlay = !showOverlay }
    ) {
        if (currentMedia != null) {
            AnimatedContent(
                targetState = currentMedia,
                transitionSpec = {
                    buildSlideTransition(config.transition)
                },
                label = "slideshow_transition",
                modifier = Modifier.fillMaxSize()
            ) { item ->
                if (item.isVideo) {
                    SlideshowVideoSlide(
                        media = item,
                        muted = config.muteVideos,
                        isPaused = isPaused,
                        onEnded = { advanceNext() }
                    )
                } else {
                    val kenBurnsProgress = remember(item.id) { Animatable(0f) }
                    LaunchedEffect(item.id, config.transition, config.intervalMs, isPaused) {
                        if (config.transition == SlideshowTransition.KEN_BURNS && !isPaused) {
                            kenBurnsProgress.snapTo(0f)
                            kenBurnsProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = config.intervalMs.toInt().coerceAtLeast(1000),
                                    easing = LinearEasing
                                )
                            )
                        }
                    }

                    val cubeAngle = remember(item.id) { Animatable(if (config.transition == SlideshowTransition.CUBE_3D) 85f else 0f) }
                    LaunchedEffect(item.id, config.transition) {
                        if (config.transition == SlideshowTransition.CUBE_3D) {
                            cubeAngle.animateTo(0f, tween(550))
                        }
                    }

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(if (item.isRaw || item.uri.startsWith("hgvault://")) item.uri else File(item.path))
                            .build(),
                        contentDescription = item.fileName,
                        contentScale = if (config.transition == SlideshowTransition.KEN_BURNS) {
                            ContentScale.Crop
                        } else {
                            ContentScale.Fit
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (config.transition == SlideshowTransition.KEN_BURNS) {
                                    val p = kenBurnsProgress.value
                                    val zoom = 1.04f + 0.18f * p
                                    scaleX = zoom
                                    scaleY = zoom
                                    transformOrigin = TransformOrigin(faceFocusX, faceFocusY)
                                } else if (config.transition == SlideshowTransition.CUBE_3D) {
                                    cameraDistance = 16f * density
                                    rotationY = cubeAngle.value
                                }
                            }
                    )
                }
            }
        }

        // Controls Overlay
        if (showOverlay) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quitter", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentMedia?.fileName ?: "Diaporama",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "${currentIndex + 1} / ${playlist.size} • ${config.transition.label}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = { showConfigSheet = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Options du diaporama", tint = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { goPrevious() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Précédent", tint = Color.White)
                }
                IconButton(onClick = { isPaused = !isPaused }) {
                    Icon(
                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = "Pause/Reprendre",
                        tint = Color(0xFF00E5FF)
                    )
                }
                IconButton(onClick = { advanceNext() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Suivant", tint = Color.White)
                }
            }
        }
    }

    if (showConfigSheet) {
        ModalBottomSheet(onDismissRequest = { showConfigSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Configuration du Diaporama", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text("Durée par image : ${String.format("%.1f s", config.intervalMs / 1000f)}", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000L, 2000L, 3000L, 5000L, 10_000L, 15_000L, 30_000L).forEach { ms ->
                        FilterChip(
                            selected = config.intervalMs == ms,
                            onClick = { onUpdateConfig(config.copy(intervalMs = ms)) },
                            label = { Text("${ms / 1000}s") }
                        )
                    }
                }
                Slider(
                    value = (config.intervalMs / 1000f).coerceIn(1f, 30f),
                    onValueChange = { sec -> onUpdateConfig(config.copy(intervalMs = (sec * 1000).toLong())) },
                    valueRange = 1f..30f
                )

                Text("Transition 60/120 Hz", fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SlideshowTransition.entries.forEach { tr ->
                        FilterChip(
                            selected = config.transition == tr,
                            onClick = { onUpdateConfig(config.copy(transition = tr)) },
                            label = { Text(tr.label) }
                        )
                    }
                }

                SlideshowToggleRow("Ordre aléatoire (Shuffle)", config.shuffle) {
                    onUpdateConfig(config.copy(shuffle = it))
                }
                SlideshowToggleRow("Boucle infinie", config.loop) {
                    onUpdateConfig(config.copy(loop = it))
                }
                SlideshowToggleRow("Inclure les vidéos (lecture intégrale)", config.includeVideos) {
                    onUpdateConfig(config.copy(includeVideos = it))
                }
                if (config.includeVideos) {
                    SlideshowToggleRow("Couper le son des vidéos", config.muteVideos) {
                        onUpdateConfig(config.copy(muteVideos = it))
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun SlideshowToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SlideshowVideoSlide(
    media: MediaEntity,
    muted: Boolean,
    isPaused: Boolean,
    onEnded: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(media.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(media.path))))
            volume = if (muted) 0f else 1f
            prepare()
            playWhenReady = !isPaused
        }
    }

    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(isPaused) {
        if (isPaused) player.pause() else player.play()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv -> player.setVideoTextureView(tv) }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun buildSlideTransition(transition: SlideshowTransition): ContentTransform {
    return when (transition) {
        SlideshowTransition.KEN_BURNS, SlideshowTransition.CROSSFADE ->
            fadeIn(tween(700)) togetherWith fadeOut(tween(700))
        SlideshowTransition.SLIDE ->
            slideInHorizontally(tween(480)) { it } togetherWith slideOutHorizontally(tween(480)) { -it }
        SlideshowTransition.CUBE_3D ->
            (fadeIn(tween(450)) + slideInHorizontally(tween(450)) { it / 2 }) togetherWith
                (fadeOut(tween(450)) + slideOutHorizontally(tween(450)) { -it / 2 })
        SlideshowTransition.INSTANT_CUT ->
            EnterTransition.None togetherWith ExitTransition.None
    }
}
