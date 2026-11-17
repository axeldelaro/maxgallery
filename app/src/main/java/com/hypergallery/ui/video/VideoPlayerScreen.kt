package com.hypergallery.ui.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Rational
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hypergallery.data.files.GalleryFileManager
import com.hypergallery.data.local.entity.MediaEntity
import com.hypergallery.domain.model.VideoAspectMode
import com.hypergallery.security.AesGcmVaultDataSource
import com.hypergallery.security.EncryptedVaultManager
import com.hypergallery.util.ByteFormatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    media: MediaEntity,
    vaultManager: EncryptedVaultManager,
    fileManager: GalleryFileManager,
    embeddedInPager: Boolean = false,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }
    var childLocked by remember { mutableStateOf(false) }
    var backgroundAudioEnabled by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(media.durationMs.coerceAtLeast(1L)) }
    var normalSpeed by remember { mutableFloatStateOf(1.0f) }
    var isHolding2x by remember { mutableStateOf(false) }

    // Pinch-to-Zoom up to 4x & Pan + Aspect Mode
    var videoZoom by remember { mutableFloatStateOf(1f) }
    var videoPanX by remember { mutableFloatStateOf(0f) }
    var videoPanY by remember { mutableFloatStateOf(0f) }
    var aspectMode by remember { mutableStateOf(VideoAspectMode.FIT) }

    // A-B Loop markers
    var loopPointA by remember { mutableStateOf<Long?>(null) }
    var loopPointB by remember { mutableStateOf<Long?>(null) }

    // Gesture HUD overlays
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIconType by remember { mutableIntStateOf(0) } // 1=brightness, 2=volume, 3=seek, 4=2x
    var seekPreviewTarget by remember { mutableStateOf<Long?>(null) }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var externalSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val exoPlayer = remember(media.path, externalSubtitleUri) {
        ExoPlayer.Builder(context).build().apply {
            val isVault = media.uri.startsWith("hgvault://")
            if (isVault) {
                val vaultSource = ProgressiveMediaSource.Factory(
                    AesGcmVaultDataSource.Factory(vaultManager)
                ).createMediaSource(MediaItem.fromUri(Uri.parse(media.uri)))
                setMediaSource(vaultSource)
            } else {
                val itemBuilder = MediaItem.Builder().setUri(Uri.fromFile(File(media.path)))
                externalSubtitleUri?.let { subUri ->
                    val mime = when {
                        subUri.toString().endsWith(".ass", true) || subUri.toString().endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
                        subUri.toString().endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(mime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    itemBuilder.setSubtitleConfigurations(listOf(subConfig))
                }
                val defaultSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(itemBuilder.build())
                setMediaSource(defaultSource)
            }
            prepare()
            playWhenReady = true
        }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            externalSubtitleUri = uri
            onMessage("Sous-titres externes chargés")
        }
    }

    DisposableEffect(exoPlayer) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer.duration > 0) durationMs = exoPlayer.duration
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Progress & A-B Loop ticker
    LaunchedEffect(exoPlayer, loopPointA, loopPointB) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0) durationMs = exoPlayer.duration
            val a = loopPointA
            val b = loopPointB
            if (a != null && b != null && b > a && currentPosMs >= b) {
                exoPlayer.seekTo(a)
            }
            delay(100L)
        }
    }

    // Auto-clear HUD indicator
    LaunchedEffect(hudText, isHolding2x) {
        if (hudText != null && !isHolding2x && seekPreviewTarget == null) {
            delay(900L)
            hudText = null
        }
    }

    fun captureCurrentFramePng() {
        val currentUs = exoPlayer.currentPosition * 1000L
        scope.launch {
            val savedName = withContext(Dispatchers.IO) {
                val bmp: Bitmap? = textureViewRef?.bitmap ?: runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(media.path)
                        retriever.getFrameAtTime(currentUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()

                bmp ?: return@withContext null
                val picturesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "HyperGallery"
                ).apply { if (!exists()) mkdirs() }
                val out = fileManager.resolveNonCollidingFile(
                    File(picturesDir, "Frame_${media.fileName.substringBeforeLast('.')}_${exoPlayer.currentPosition}ms.png")
                )
                out.outputStream().buffered().use { stream ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                fileManager.notifyMediaScanner(listOf(out.absolutePath))
                out.name
            }
            if (savedName != null) {
                onMessage("Frame PNG capturée : $savedName")
            } else {
                onMessage("Capture impossible sur ce flux")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportSize = it }
            // 2-finger pinch-to-zoom up to 4x & pan during video playback
            .pointerInput(childLocked) {
                if (childLocked) return@pointerInput
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            videoZoom = (videoZoom * z).coerceIn(1f, 4f)
                            if (videoZoom > 1.02f) {
                                videoPanX += p.x
                                videoPanY += p.y
                            } else {
                                videoPanX = 0f
                                videoPanY = 0f
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Tap, Double-Tap ±10s, and Long-Press Hold 2.0x speed!
            .pointerInput(childLocked, normalSpeed) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { pos ->
                        if (childLocked) return@detectTapGestures
                        val isLeft = pos.x < viewportSize.width / 2f
                        val delta = if (isLeft) -10_000L else 10_000L
                        val target = (exoPlayer.currentPosition + delta).coerceIn(0L, durationMs)
                        exoPlayer.seekTo(target)
                        hudIconType = 3
                        hudText = if (isLeft) "⏪ -10s" else "+10s ⏩"
                    },
                    onLongPress = {
                        if (childLocked) return@detectTapGestures
                        isHolding2x = true
                        exoPlayer.setPlaybackSpeed(2.0f)
                        hudIconType = 4
                        hudText = "⚡ Vitesse 2.0× maintenue"
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (isHolding2x) {
                            isHolding2x = false
                            exoPlayer.setPlaybackSpeed(normalSpeed)
                            hudText = null
                        }
                    }
                )
            }
            // Vertical drag: Left half = Brightness, Right half = Volume
            .pointerInput(childLocked) {
                if (childLocked) return@pointerInput
                var startX = 0f
                detectVerticalDragGestures(
                    onDragStart = { startX = it.x },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val deltaFraction = -dragAmount / (viewportSize.height.coerceAtLeast(400).toFloat())
                        if (startX < viewportSize.width / 2f) {
                            // Brightness
                            val lp = activity?.window?.attributes
                            if (lp != null) {
                                val curr = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                val next = (curr + deltaFraction).coerceIn(0.02f, 1.0f)
                                lp.screenBrightness = next
                                activity.window.attributes = lp
                                hudIconType = 1
                                hudText = "Luminosité ${(next * 100).toInt()} %"
                            }
                        } else {
                            // Audio Volume
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val currVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val step = if (deltaFraction > 0.02f) 1 else if (deltaFraction < -0.02f) -1 else 0
                            if (step != 0) {
                                val nextVol = (currVol + step).coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                hudIconType = 2
                                hudText = "Volume ${(nextVol * 100) / maxVol} %"
                            }
                        }
                    }
                )
            }
            // Horizontal drag: Fine temporal scrubbing with delta preview
            .pointerInput(childLocked, durationMs) {
                if (childLocked || embeddedInPager) return@pointerInput
                var scrubStartPos = 0L
                var scrubDeltaMs = 0L
                detectHorizontalDragGestures(
                    onDragStart = {
                        scrubStartPos = exoPlayer.currentPosition
                        scrubDeltaMs = 0L
                    },
                    onDragEnd = {
                        seekPreviewTarget?.let { exoPlayer.seekTo(it) }
                        seekPreviewTarget = null
                        hudText = null
                    },
                    onDragCancel = {
                        seekPreviewTarget = null
                        hudText = null
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val msPerPx = (durationMs.coerceAtMost(180_000L).toFloat() / viewportSize.width.coerceAtLeast(400))
                        scrubDeltaMs += (dragAmount * msPerPx).toLong()
                        val target = (scrubStartPos + scrubDeltaMs).coerceIn(0L, durationMs)
                        seekPreviewTarget = target
                        val sign = if (scrubDeltaMs >= 0) "+" else "-"
                        hudIconType = 3
                        hudText = "$sign${ByteFormatting.formatDuration(kotlin.math.abs(scrubDeltaMs))} → ${ByteFormatting.formatDuration(target)}"
                    }
                )
            }
    ) {
        // Video Surface (TextureView enables reliable hardware zoom/pan & exact frame capture!)
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    textureViewRef = tv
                    exoPlayer.setVideoTextureView(tv)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val aspectScaleX = when (aspectMode) {
                        VideoAspectMode.FIT -> 1f
                        VideoAspectMode.FILL_CROP -> 1.22f
                        VideoAspectMode.STRETCH -> 1.15f
                    }
                    val aspectScaleY = when (aspectMode) {
                        VideoAspectMode.FIT -> 1f
                        VideoAspectMode.FILL_CROP -> 1.22f
                        VideoAspectMode.STRETCH -> 1.0f
                    }
                    scaleX = videoZoom * aspectScaleX
                    scaleY = videoZoom * aspectScaleY
                    translationX = videoPanX
                    translationY = videoPanY
                }
        )

        // Live Gesture HUD Pill (Brightness / Volume / Scrub / 2.0x Speed)
        hudText?.let { label ->
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (hudIconType) {
                        1 -> Icons.Filled.Brightness6
                        2 -> Icons.AutoMirrored.Filled.VolumeUp
                        else -> Icons.Filled.Speed
                    }
                    Icon(icon, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        // Child Lock Minimal Unlock Pill
        if (childLocked && controlsVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp)
                    .clickable { childLocked = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = "Déverrouiller", tint = Color(0xFF00E5FF))
                    Spacer(Modifier.width(6.dp))
                    Text("Déverrouiller contrôles", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        // Full Pro Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible && !childLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!embeddedInPager) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White)
                        }
                    }
                    Text(
                        text = media.fileName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    // Capture exact frame PNG
                    IconButton(onClick = { captureCurrentFramePng() }) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = "Capturer image PNG", tint = Color.White)
                    }
                    // Audio & Subtitles Tracks
                    IconButton(onClick = { showTracksSheet = true }) {
                        Icon(Icons.Filled.Subtitles, contentDescription = "Pistes Audio & Sous-titres", tint = Color.White)
                    }
                    // Picture-in-Picture (PiP)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && !media.uri.startsWith("hgvault://")) {
                        IconButton(onClick = {
                            val ratio = if (media.width > 0 && media.height > 0) {
                                Rational(media.width.coerceIn(100, 2390), media.height.coerceIn(100, 2390))
                            } else {
                                Rational(16, 9)
                            }
                            activity.enterPictureInPictureMode(
                                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
                            )
                        }) {
                            Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                        }
                    }
                }

                // Center Transport Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "-10s", tint = Color.White)
                    }

                    IconButton(
                        onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Lecture/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = { exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(durationMs)) },
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Filled.FastForward, contentDescription = "+10s", tint = Color.White)
                    }
                }

                // Bottom Timeline & Pro Feature Strip
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ByteFormatting.formatDuration(currentPosMs),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = (currentPosMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                val target = (frac * durationMs).toLong()
                                currentPosMs = target
                                exoPlayer.seekTo(target)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(
                            text = ByteFormatting.formatDuration(durationMs),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Child Lock
                        IconButton(onClick = { childLocked = true }) {
                            Icon(Icons.Filled.LockOpen, contentDescription = "Verrouiller écran", tint = Color.White)
                        }

                        // Speed (0.25x..3.0x)
                        TextButton(onClick = { showSpeedSheet = true }) {
                            Icon(Icons.Filled.Speed, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${normalSpeed}×", color = Color.White, fontSize = 12.sp)
                        }

                        // A-B Loop
                        TextButton(onClick = {
                            when {
                                loopPointA == null -> {
                                    loopPointA = exoPlayer.currentPosition
                                    onMessage("Marqueur A défini (${ByteFormatting.formatDuration(loopPointA!!)})")
                                }
                                loopPointB == null -> {
                                    val pos = exoPlayer.currentPosition
                                    if (pos > loopPointA!!) {
                                        loopPointB = pos
                                        onMessage("Boucle A-B activée !")
                                    } else {
                                        loopPointA = null
                                    }
                                }
                                else -> {
                                    loopPointA = null
                                    loopPointB = null
                                    onMessage("Boucle A-B désactivée")
                                }
                            }
                        }) {
                            Icon(
                                Icons.Filled.Repeat,
                                contentDescription = null,
                                tint = if (loopPointB != null) Color(0xFF00E676) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = when {
                                    loopPointA == null -> "A-B"
                                    loopPointB == null -> "A→?"
                                    else -> "A↔B"
                                },
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }

                        // Background Audio toggle
                        IconButton(onClick = {
                            backgroundAudioEnabled = !backgroundAudioEnabled
                            onMessage(
                                if (backgroundAudioEnabled) "Lecture audio en arrière-plan activée"
                                else "Lecture audio en arrière-plan désactivée"
                            )
                        }) {
                            Icon(
                                Icons.Filled.Headphones,
                                contentDescription = "Audio arrière-plan",
                                tint = if (backgroundAudioEnabled) Color(0xFF00E676) else Color.White
                            )
                        }

                        // Aspect Mode & Reset Zoom
                        IconButton(onClick = {
                            videoZoom = 1f
                            videoPanX = 0f
                            videoPanY = 0f
                            val nextIdx = (aspectMode.ordinal + 1) % VideoAspectMode.entries.size
                            aspectMode = VideoAspectMode.entries[nextIdx]
                            hudIconType = 3
                            hudText = "Format : ${aspectMode.label}"
                        }) {
                            Icon(Icons.Filled.AspectRatio, contentDescription = "Format image", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Speed Selection Sheet (0.25x to 3.0x)
    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Vitesse de lecture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { spd ->
                        FilterChip(
                            selected = normalSpeed == spd,
                            onClick = {
                                normalSpeed = spd
                                exoPlayer.setPlaybackSpeed(spd)
                                showSpeedSheet = false
                            },
                            label = { Text("${spd}×") }
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Audio & Subtitle Tracks Sheet
    if (showTracksSheet) {
        ModalBottomSheet(onDismissRequest = { showTracksSheet = false }) {
            val tracksGroups = exoPlayer.currentTracks.groups
            val audioGroups = tracksGroups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val textGroups = tracksGroups.filter { it.type == C.TRACK_TYPE_TEXT }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Pistes Audio & Sous-titres", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text("Pistes Audio (${audioGroups.size})", fontWeight = FontWeight.SemiBold)
                if (audioGroups.isEmpty()) {
                    Text("Piste audio par défaut", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        audioGroups.forEachIndexed { idx, group ->
                            val fmt = group.getTrackFormat(0)
                            val label = fmt.label ?: fmt.language ?: "Audio #${idx + 1}"
                            FilterChip(
                                selected = group.isSelected,
                                onClick = {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                        .build()
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Text("Sous-titres (${textGroups.size})", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = textGroups.none { it.isSelected },
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        },
                        label = { Text("Désactivés") }
                    )
                    textGroups.forEachIndexed { idx, group ->
                        val fmt = group.getTrackFormat(0)
                        val label = fmt.label ?: fmt.language ?: "Sous-titre #${idx + 1}"
                        FilterChip(
                            selected = group.isSelected,
                            onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                    .build()
                            },
                            label = { Text(label) }
                        )
                    }
                }

                TextButton(
                    onClick = {
                        showTracksSheet = false
                        subtitleLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text("Charger un fichier de sous-titres externe (.srt, .ass, .vtt)")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
