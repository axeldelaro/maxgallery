package com.hypergallery.ui.viewer

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.editor.PhotoEditorScreen
import com.hypergallery.ui.video.VideoPlayerScreen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MediaViewerScreen(
    viewModel: GalleryViewModel,
    onClose: () -> Unit,
    onLaunchSlideshow: () -> Unit,
    onJumpToFolder: (String) -> Unit,
    onJumpToPerson: (Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val items by viewModel.viewerItems.collectAsState()
    val startIndex by viewModel.viewerStartIndex.collectAsState()
    val allPersons by viewModel.allPersonsIncludingHidden.collectAsState()
    val personsMap = remember(allPersons) { allPersons.associateBy { it.personId } }

    if (items.isEmpty()) {
        onClose()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )
    val currentMedia = items.getOrNull(pagerState.currentPage) ?: items.first()

    var systemBarsHidden by remember { mutableStateOf(false) }
    var showInspector by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var enableTwoFingerRotation by remember { mutableStateOf(false) }
    var verticalDragPx by remember { mutableFloatStateOf(0f) }

    val facesOnCurrent by viewModel.observeFacesForMedia(currentMedia.id).collectAsState(initial = emptyList())

    // Edge-to-Edge System Bars Immersion Controller
    DisposableEffect(systemBarsHidden) {
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        if (systemBarsHidden) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (showEditor && !currentMedia.isVideo) {
        PhotoEditorScreen(
            media = currentMedia,
            fileManager = viewModel.fileManager,
            scanner = viewModel.scanner,
            onClose = { showEditor = false },
            onSaved = { msg ->
                showEditor = false
                viewModel.emitMessage(msg)
            }
        )
        return
    }

    val bgAlpha = (1f - (verticalDragPx / 650f)).coerceIn(0.18f, 1f)
    val dismissScale = (1f - (verticalDragPx / 2200f)).coerceIn(0.82f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, verticalDragPx.roundToInt()) }
                .graphicsLayer {
                    scaleX = dismissScale
                    scaleY = dismissScale
                }
        ) { pageIdx ->
            val media = items[pageIdx]
            if (media.isVideo) {
                VideoPlayerScreen(
                    media = media,
                    vaultManager = viewModel.vaultManager,
                    fileManager = viewModel.fileManager,
                    embeddedInPager = false,
                    onBack = onClose,
                    onMessage = { viewModel.emitMessage(it) }
                )
            } else {
                TiledZoomableImage(
                    media = media,
                    vaultManager = viewModel.vaultManager,
                    enableRotationGesture = enableTwoFingerRotation,
                    onSingleTap = { systemBarsHidden = !systemBarsHidden },
                    onSwipeVerticalDismiss = { dy -> verticalDragPx = dy },
                    onSwipeVerticalEnd = { shouldDismiss ->
                        if (shouldDismiss) {
                            onClose()
                        } else {
                            verticalDragPx = 0f
                        }
                    },
                    onSwipeUpInspector = { showInspector = true }
                )
            }
        }

        // Top & Bottom Overlay Chrome (for static/animated photos; VideoPlayerScreen renders its own pro HUD)
        AnimatedVisibility(
            visible = !systemBarsHidden && !currentMedia.isVideo && verticalDragPx == 0f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentMedia.fileName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / ${items.size} • ${currentMedia.width}×${currentMedia.height}",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onLaunchSlideshow) {
                        Icon(Icons.Filled.Slideshow, contentDescription = "Lancer Diaporama", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'outils", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            if (!currentMedia.uri.startsWith("hgvault://")) {
                                DropdownMenuItem(
                                    text = { Text("Définir comme fond d'écran") },
                                    leadingIcon = { Icon(Icons.Filled.Wallpaper, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        scope.launch {
                                            val ok = viewModel.fileManager.setAsWallpaper(currentMedia)
                                            viewModel.emitMessage(
                                                if (ok) "Fond d'écran appliqué !" else "Échec application fond d'écran"
                                            )
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Imprimer") },
                                    leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.fileManager.printPhoto(context, currentMedia)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Déplacer vers le Coffre-Fort AES-256") },
                                    leadingIcon = { Icon(Icons.Filled.EnhancedEncryption, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.moveSelectedToVault(listOf(currentMedia.id))
                                        onClose()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (enableTwoFingerRotation) "Désactiver rotation à 2 doigts"
                                        else "Activer rotation libre à 2 doigts"
                                    )
                                },
                                onClick = {
                                    enableTwoFingerRotation = !enableTwoFingerRotation
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
                }

                // Bottom Action Bar
                if (!currentMedia.uri.startsWith("hgvault://")) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.shareSelected(context, listOf(currentMedia)) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Partager", tint = Color.White)
                        }
                        IconButton(onClick = { showEditor = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Éditer", tint = Color.White)
                        }
                        IconButton(onClick = {
                            viewModel.toggleFavorite(listOf(currentMedia.id), !currentMedia.isFavorite)
                        }) {
                            Icon(
                                imageVector = if (currentMedia.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Favori",
                                tint = if (currentMedia.isFavorite) Color(0xFFFF4081) else Color.White
                            )
                        }
                        IconButton(onClick = { showInspector = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "Inspecteur EXIF & Histogramme", tint = Color.White)
                        }
                        IconButton(onClick = {
                            viewModel.moveSelectedToTrash(listOf(currentMedia.id))
                            if (items.size <= 1) onClose()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Corbeille", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showInspector) {
        ExifInspectorSheet(
            media = currentMedia,
            facesOnPhoto = facesOnCurrent,
            personsMap = personsMap,
            onDismiss = { showInspector = false },
            onNavigateToFolder = { folder ->
                onClose()
                onJumpToFolder(folder)
            },
            onNavigateToPerson = { pid ->
                onClose()
                onJumpToPerson(pid)
            },
            onStripExif = {
                viewModel.stripExif(currentMedia)
                showInspector = false
            }
        )
    }
}
