package com.hypergallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hypergallery.domain.model.QuickFilter
import com.hypergallery.ui.GalleryViewModel
import com.hypergallery.ui.screens.FoldersTabScreen
import com.hypergallery.ui.screens.PeopleTabScreen
import com.hypergallery.ui.screens.PhotosTabScreen
import com.hypergallery.ui.screens.VaultAndToolsTabScreen
import com.hypergallery.ui.slideshow.SlideshowScreen
import com.hypergallery.ui.theme.HyperGalleryTheme
import com.hypergallery.ui.viewer.MediaViewerScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestHighRefreshRateDisplay()

        setContent {
            val viewModel: GalleryViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsState()
            val isSensitiveActive by viewModel.securitySessionManager.isSensitiveContentActive.collectAsState()
            val slideshowConfig by viewModel.slideshowConfig.collectAsState()
            val viewerItems by viewModel.viewerItems.collectAsState()

            val snackbarHostState = remember { SnackbarHostState() }
            var currentTab by remember { mutableIntStateOf(0) }
            var isViewerOpen by remember { mutableStateOf(false) }
            var isSlideshowOpen by remember { mutableStateOf(false) }
            var hasFullFilesAccess by remember { mutableStateOf(checkManageExternalStorage()) }

            // Dynamic Anti-Leak Protection: Toggle FLAG_SECURE whenever Vault or Locked Folder is open!
            LaunchedEffect(isSensitiveActive) {
                if (isSensitiveActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            // Collect toast/snackbar feedback
            LaunchedEffect(Unit) {
                viewModel.snackbarMessage.collect { msg ->
                    snackbarHostState.showSnackbar(msg)
                }
            }

            // Runtime Media Permissions Launcher (Android 8..15)
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.triggerFullMediaScan()
            }

            LaunchedEffect(Unit) {
                val needed = buildRequiredMediaPermissions().filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (needed.isNotEmpty()) {
                    permissionLauncher.launch(needed.toTypedArray())
                } else {
                    viewModel.triggerFullMediaScan()
                }
            }

            // Re-check MANAGE_EXTERNAL_STORAGE when returning from Android Settings
            val lifecycleOwner = LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        val nowFull = checkManageExternalStorage()
                        if (nowFull && !hasFullFilesAccess) {
                            hasFullFilesAccess = true
                            viewModel.triggerFullMediaScan()
                        } else {
                            hasFullFilesAccess = nowFull
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            HyperGalleryTheme(
                themeMode = settings.themeMode,
                dynamicColors = settings.dynamicColors
            ) {
                when {
                    isSlideshowOpen -> {
                        BackHandler { isSlideshowOpen = false }
                        SlideshowScreen(
                            sourceItems = viewerItems,
                            config = slideshowConfig,
                            onUpdateConfig = { viewModel.updateSlideshowConfig(it) },
                            loadFacesForMedia = { mediaId -> viewModel.getFacesForMediaOnce(mediaId) },
                            onExit = { isSlideshowOpen = false }
                        )
                    }

                    isViewerOpen -> {
                        BackHandler { isViewerOpen = false }
                        MediaViewerScreen(
                            viewModel = viewModel,
                            onClose = { isViewerOpen = false },
                            onLaunchSlideshow = {
                                isViewerOpen = false
                                isSlideshowOpen = true
                            },
                            onJumpToFolder = { folderPath ->
                                isViewerOpen = false
                                currentTab = 1
                                viewModel.openFolder(folderPath)
                            },
                            onJumpToPerson = { personId ->
                                isViewerOpen = false
                                currentTab = 2
                                viewModel.setSinglePersonView(personId)
                            }
                        )
                    }

                    else -> {
                        Scaffold(
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = currentTab == 0,
                                        onClick = { currentTab = 0 },
                                        icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Photos") },
                                        label = { Text("Photos") }
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == 1,
                                        onClick = { currentTab = 1 },
                                        icon = { Icon(Icons.Filled.Folder, contentDescription = "Dossiers") },
                                        label = { Text("Dossiers") }
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == 2,
                                        onClick = { currentTab = 2 },
                                        icon = { Icon(Icons.Filled.Face, contentDescription = "Personnes") },
                                        label = { Text("Personnes") }
                                    )
                                    NavigationBarItem(
                                        selected = currentTab == 3,
                                        onClick = { currentTab = 3 },
                                        icon = { Icon(Icons.Filled.Security, contentDescription = "Coffre & Outils") },
                                        label = { Text("Coffre & Outils") }
                                    )
                                }
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = innerPadding.calculateTopPadding())
                            ) {
                                when (currentTab) {
                                    0 -> PhotosTabScreen(
                                        viewModel = viewModel,
                                        hasFullStoragePermission = hasFullFilesAccess,
                                        onRequestFullStoragePermission = { launchManageAllFilesSettings() },
                                        onOpenViewer = { isViewerOpen = true },
                                        onLaunchSlideshow = { isSlideshowOpen = true }
                                    )

                                    1 -> FoldersTabScreen(
                                        viewModel = viewModel,
                                        onOpenViewer = { isViewerOpen = true },
                                        onLaunchSlideshow = { isSlideshowOpen = true }
                                    )

                                    2 -> PeopleTabScreen(
                                        viewModel = viewModel,
                                        onOpenViewer = { isViewerOpen = true },
                                        onLaunchSlideshow = { isSlideshowOpen = true }
                                    )

                                    3 -> VaultAndToolsTabScreen(
                                        viewModel = viewModel,
                                        onOpenViewer = { isViewerOpen = true },
                                        onNavigateToFavoritesTimeline = {
                                            viewModel.clearFilters()
                                            viewModel.toggleFilter(QuickFilter.FAVORITES)
                                            currentTab = 0
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

    /**
     * Negotiates the highest available hardware refresh rate (120Hz / 144Hz) on supported displays.
     */
    private fun requestHighRefreshRateDisplay() {
        runCatching {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return
            val bestMode = disp.supportedModes.maxWithOrNull(
                compareBy<android.view.Display.Mode> { it.refreshRate }
                    .thenBy { it.physicalWidth * it.physicalHeight }
            ) ?: return
            val params = window.attributes
            params.preferredDisplayModeId = bestMode.modeId
            window.attributes = params
        }
    }

    private fun checkManageExternalStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun launchManageAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = runCatching {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            }.getOrElse {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun buildRequiredMediaPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }
    }
}
