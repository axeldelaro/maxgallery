package com.hypergallery.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecuritySessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isVaultUnlocked = MutableStateFlow(false)
    val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

    private val _unlockedFolders = MutableStateFlow<Set<String>>(emptySet())
    val unlockedFolders: StateFlow<Set<String>> = _unlockedFolders.asStateFlow()

    private val _isHiddenSessionAuthorized = MutableStateFlow(false)
    val isHiddenSessionAuthorized: StateFlow<Boolean> = _isHiddenSessionAuthorized.asStateFlow()

    val isSensitiveContentActive: StateFlow<Boolean> = combine(
        _isVaultUnlocked,
        _unlockedFolders
    ) { vault, folders ->
        vault || folders.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var initialized = false

    fun initializeAutoRelock() {
        if (initialized) return
        initialized = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val screenOffFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        lockAllNow()
                    }
                }
            },
            screenOffFilter
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        // Instant automatic relock whenever the app goes to background
        lockAllNow()
    }

    fun unlockVault() {
        _isVaultUnlocked.value = true
    }

    fun unlockFolder(folderPath: String) {
        _unlockedFolders.value = _unlockedFolders.value + folderPath
    }

    fun isFolderUnlocked(folderPath: String): Boolean {
        return folderPath in _unlockedFolders.value
    }

    fun authorizeHiddenFilesSession() {
        _isHiddenSessionAuthorized.value = true
    }

    fun lockAllNow() {
        _isVaultUnlocked.value = false
        _unlockedFolders.value = emptySet()
        _isHiddenSessionAuthorized.value = false
    }
}
