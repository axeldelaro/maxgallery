package com.hypergallery

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import com.hypergallery.data.media.RawThumbnailFetcher
import com.hypergallery.security.EncryptedVaultManager
import com.hypergallery.security.SecuritySessionManager
import com.hypergallery.security.VaultEncryptedFetcher
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HyperGalleryApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var vaultManager: EncryptedVaultManager

    @Inject
    lateinit var securitySessionManager: SecuritySessionManager

    override fun onCreate() {
        super.onCreate()
        securitySessionManager.initializeAutoRelock()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "hypergallery_image_cache"))
                    .maxSizeBytes(512L * 1024L * 1024L) // 512 MiB persistent disk cache
                    .build()
            }
            .components {
                add(VaultEncryptedFetcher.Factory(vaultManager))
                add(RawThumbnailFetcher.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .allowHardware(true)
            .crossfade(false)
            .build()
    }
}
