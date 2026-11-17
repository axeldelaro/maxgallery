package com.hypergallery.security

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import coil3.ImageLoader
import coil3.asImage
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import com.hypergallery.data.files.GalleryFileManager
import com.hypergallery.data.local.dao.MediaDao
import com.hypergallery.data.local.dao.VaultDao
import com.hypergallery.data.local.entity.VaultItemEntity
import com.hypergallery.data.scanner.HybridMediaScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-backed AES-256-GCM Encrypted Vault.
 * File format ("HGV1"):
 *  - [0..3]  4 bytes ASCII magic: "HGV1"
 *  - [4..11] 8 bytes Long: original plaintext total size
 *  - [12..15] 4 bytes Int: plaintext chunk size (1_048_576 bytes = 1 MiB)
 *  - Repeated chunks, each of fixed on-disk stride (12-byte IV + chunkPlainLen + 16-byte GCM Auth Tag)
 *    except the final chunk which holds (12-byte IV + remainingPlainLen + 16-byte GCM Auth Tag).
 * This allows O(1) random-access seeking in 4K videos via [AesGcmVaultDataSource] without writing
 * any temporary plaintext file to disk!
 */
@Singleton
class EncryptedVaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultDao: VaultDao,
    private val mediaDao: MediaDao,
    private val fileManager: GalleryFileManager,
    private val scanner: HybridMediaScanner
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "hypergallery_vault_aes256_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HEADER_SIZE = 16
        const val PLAIN_CHUNK_SIZE = 1024 * 1024 // 1 MiB
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_BITS = 128
        const val ENCRYPTED_CHUNK_STRIDE = IV_SIZE + PLAIN_CHUNK_SIZE + TAG_SIZE
        const val VAULT_URI_SCHEME = "hgvault"
    }

    private val vaultDir: File by lazy {
        File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }
    }

    fun getOrCreateMasterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    suspend fun moveMediaToVault(mediaIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val items = mediaDao.getByIds(mediaIds)
        val secretKey = getOrCreateMasterKey()
        val now = System.currentTimeMillis()
        val movedIds = mutableListOf<Long>()
        var count = 0

        for (item in items) {
            val src = File(item.path)
            if (!src.exists()) continue
            val dest = File(vaultDir, "${UUID.randomUUID()}.hgvault")

            val encryptedOk = runCatching {
                encryptFileToVault(src, dest, secretKey)
                true
            }.getOrDefault(false)

            if (encryptedOk && dest.exists() && dest.length() > HEADER_SIZE) {
                fileManager.deletePublicFile(src, item.mediaStoreId, item.isVideo)
                vaultDao.insertVaultItem(
                    VaultItemEntity(
                        encryptedFilePath = dest.absolutePath,
                        originalPath = item.path,
                        fileName = item.fileName,
                        mimeType = item.mimeType,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        width = item.width,
                        height = item.height,
                        durationMs = item.durationMs,
                        dateTaken = item.dateTaken,
                        addedAt = now
                    )
                )
                movedIds.add(item.id)
                count++
            } else {
                dest.delete()
            }
        }
        if (movedIds.isNotEmpty()) {
            mediaDao.deleteByIds(movedIds)
        }
        count
    }

    suspend fun restoreFromVault(vaultIds: List<Long>): Int = withContext(Dispatchers.IO) {
        val secretKey = getOrCreateMasterKey()
        val restoredPaths = mutableListOf<String>()
        var restored = 0

        for (id in vaultIds) {
            val item = vaultDao.getById(id) ?: continue
            val encFile = File(item.encryptedFilePath)
            if (!encFile.exists()) {
                vaultDao.deleteVaultItem(id)
                continue
            }
            val dest = fileManager.resolveNonCollidingFile(File(item.originalPath))
            dest.parentFile?.mkdirs()
            val ok = runCatching {
                decryptVaultToFile(encFile, dest, secretKey)
                encFile.delete()
                true
            }.getOrDefault(false)

            if (ok) {
                vaultDao.deleteVaultItem(id)
                restoredPaths.add(dest.absolutePath)
                restored++
            } else {
                dest.delete()
            }
        }
        if (restoredPaths.isNotEmpty()) {
            fileManager.notifyMediaScanner(restoredPaths)
            scanner.scanAllMedia()
        }
        restored
    }

    suspend fun deleteVaultItemsPermanently(vaultIds: List<Long>) = withContext(Dispatchers.IO) {
        for (id in vaultIds) {
            val item = vaultDao.getById(id) ?: continue
            File(item.encryptedFilePath).delete()
            vaultDao.deleteVaultItem(id)
        }
    }

    fun encryptFileToVault(src: File, dest: File, secretKey: SecretKey) {
        val totalPlainSize = src.length()
        src.inputStream().buffered().use { input ->
            DataOutputStream(dest.outputStream().buffered()).use { output ->
                output.writeBytes("HGV1")
                output.writeLong(totalPlainSize)
                output.writeInt(PLAIN_CHUNK_SIZE)

                val plainBuffer = ByteArray(PLAIN_CHUNK_SIZE)
                while (true) {
                    val bytesRead = readFullyUpTo(input, plainBuffer)
                    if (bytesRead <= 0) break

                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                    val iv = cipher.iv
                    check(iv.size == IV_SIZE) { "Unexpected GCM IV length: ${iv.size}" }
                    val encryptedChunk = cipher.doFinal(plainBuffer, 0, bytesRead)

                    output.write(iv)
                    output.write(encryptedChunk)
                }
                output.flush()
            }
        }
    }

    private fun decryptVaultToFile(encFile: File, dest: File, secretKey: SecretKey) {
        openDecryptedInputStream(encFile, secretKey).use { decryptedIn ->
            dest.outputStream().buffered().use { out ->
                decryptedIn.copyTo(out)
            }
        }
    }

    fun decryptImageBytesInMemory(encFile: File): ByteArray {
        val secretKey = getOrCreateMasterKey()
        val baos = ByteArrayOutputStream(encFile.length().toInt().coerceAtLeast(4096))
        openDecryptedInputStream(encFile, secretKey).use { it.copyTo(baos) }
        return baos.toByteArray()
    }

    fun openDecryptedInputStream(encFile: File, secretKey: SecretKey = getOrCreateMasterKey()): InputStream {
        return VaultChunkedInputStream(encFile, secretKey)
    }

    fun decryptSingleChunk(raf: RandomAccessFile, chunkIndex: Long, totalPlainSize: Long, secretKey: SecretKey): ByteArray {
        val plainOffset = chunkIndex * PLAIN_CHUNK_SIZE
        if (plainOffset >= totalPlainSize) return ByteArray(0)
        val expectedPlainLen = min(PLAIN_CHUNK_SIZE.toLong(), totalPlainSize - plainOffset).toInt()
        val encryptedLen = expectedPlainLen + TAG_SIZE
        val diskOffset = HEADER_SIZE.toLong() + chunkIndex * ENCRYPTED_CHUNK_STRIDE.toLong()

        val iv = ByteArray(IV_SIZE)
        val cipherBytes = ByteArray(encryptedLen)
        synchronized(raf) {
            raf.seek(diskOffset)
            raf.readFully(iv)
            raf.readFully(cipherBytes)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun readFullyUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val r = input.read(buffer, total, buffer.size - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    fun buildVaultUri(encryptedFilePath: String): String =
        "$VAULT_URI_SCHEME://$encryptedFilePath"
}

private class VaultChunkedInputStream(
    private val encFile: File,
    private val secretKey: SecretKey
) : InputStream() {

    private val raf = RandomAccessFile(encFile, "r")
    private val totalPlainSize: Long
    private var currentChunkIndex = 0L
    private var currentChunk: ByteArray = ByteArray(0)
    private var posInChunk = 0
    private var totalRead = 0L

    init {
        val magic = ByteArray(4)
        raf.readFully(magic)
        check(String(magic, Charsets.US_ASCII) == "HGV1") { "Invalid vault magic header" }
        totalPlainSize = raf.readLong()
        raf.readInt() // chunkSize
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (totalRead >= totalPlainSize) return -1
        if (len == 0) return 0
        if (posInChunk >= currentChunk.size) {
            loadNextChunk()
            if (currentChunk.isEmpty()) return -1
        }
        val available = currentChunk.size - posInChunk
        val toCopy = min(len, available)
        System.arraycopy(currentChunk, posInChunk, b, off, toCopy)
        posInChunk += toCopy
        totalRead += toCopy
        return toCopy
    }

    private fun loadNextChunk() {
        val plainOffset = currentChunkIndex * EncryptedVaultManager.PLAIN_CHUNK_SIZE
        if (plainOffset >= totalPlainSize) {
            currentChunk = ByteArray(0)
            return
        }
        val expectedPlainLen = min(
            EncryptedVaultManager.PLAIN_CHUNK_SIZE.toLong(),
            totalPlainSize - plainOffset
        ).toInt()
        val iv = ByteArray(EncryptedVaultManager.IV_SIZE)
        val cipherBytes = ByteArray(expectedPlainLen + EncryptedVaultManager.TAG_SIZE)
        raf.readFully(iv)
        raf.readFully(cipherBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(EncryptedVaultManager.TAG_BITS, iv))
        currentChunk = cipher.doFinal(cipherBytes)
        posInChunk = 0
        currentChunkIndex++
    }

    override fun close() {
        currentChunk.fill(0)
        raf.close()
    }
}

/**
 * In-memory Coil 3 Fetcher for hgvault:// encrypted files — zero temp files on disk!
 */
class VaultEncryptedFetcher(
    private val encryptedFile: File,
    private val options: Options,
    private val vaultManager: EncryptedVaultManager
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (!encryptedFile.exists()) return null
        val bytes = vaultManager.decryptImageBytesInMemory(encryptedFile)
        val targetPx = when (val w = options.size.width) {
            is Dimension.Pixels -> w.px.coerceIn(200, 2560)
            Dimension.Undefined -> 1440
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
        bytes.fill(0) // Scrub plaintext buffer after bitmap decode
        bitmap ?: return null
        return ImageFetchResult(
            image = bitmap.asImage(shareable = false),
            isSampled = sample > 1,
            dataSource = coil3.decode.DataSource.MEMORY
        )
    }

    class Factory(
        private val vaultManager: EncryptedVaultManager
    ) : Fetcher.Factory<coil3.Uri> {
        override fun create(
            data: coil3.Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (data.scheme != EncryptedVaultManager.VAULT_URI_SCHEME) return null
            val rawPath = data.toString().removePrefix("${EncryptedVaultManager.VAULT_URI_SCHEME}://")
            return VaultEncryptedFetcher(File(rawPath), options, vaultManager)
        }
    }
}

/**
 * Media3 ExoPlayer DataSource providing O(1) random-access in-memory decryption of AES-256-GCM .hgvault videos!
 */
@UnstableApi
class AesGcmVaultDataSource(
    private val vaultManager: EncryptedVaultManager
) : BaseDataSource(/* isNetwork = */ false) {

    private var raf: RandomAccessFile? = null
    private var currentUri: Uri? = null
    private var totalPlainSize: Long = 0L
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var cachedChunkIndex: Long = -1L
    private var cachedChunkBytes: ByteArray = ByteArray(0)
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri
        val rawPath = if (uri.scheme == EncryptedVaultManager.VAULT_URI_SCHEME) {
            uri.toString().removePrefix("${EncryptedVaultManager.VAULT_URI_SCHEME}://")
        } else {
            uri.path ?: error("Missing vault file path")
        }
        val file = File(rawPath)
        val randomAccess = RandomAccessFile(file, "r")
        val magic = ByteArray(4)
        randomAccess.readFully(magic)
        check(String(magic, Charsets.US_ASCII) == "HGV1") { "Invalid HGV1 header" }
        totalPlainSize = randomAccess.readLong()
        randomAccess.readInt()

        raf = randomAccess
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalPlainSize - readPosition
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        val randomAccess = raf ?: return C.RESULT_END_OF_INPUT
        val chunkIdx = readPosition / EncryptedVaultManager.PLAIN_CHUNK_SIZE
        if (chunkIdx != cachedChunkIndex) {
            cachedChunkBytes.fill(0)
            cachedChunkBytes = vaultManager.decryptSingleChunk(
                raf = randomAccess,
                chunkIndex = chunkIdx,
                totalPlainSize = totalPlainSize,
                secretKey = vaultManager.getOrCreateMasterKey()
            )
            cachedChunkIndex = chunkIdx
        }
        if (cachedChunkBytes.isEmpty()) return C.RESULT_END_OF_INPUT

        val offsetInChunk = (readPosition % EncryptedVaultManager.PLAIN_CHUNK_SIZE).toInt()
        val availableInChunk = cachedChunkBytes.size - offsetInChunk
        if (availableInChunk <= 0) return C.RESULT_END_OF_INPUT

        val bytesToCopy = min(min(length.toLong(), bytesRemaining).toInt(), availableInChunk)
        System.arraycopy(cachedChunkBytes, offsetInChunk, buffer, offset, bytesToCopy)
        readPosition += bytesToCopy
        bytesRemaining -= bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        cachedChunkBytes.fill(0)
        cachedChunkBytes = ByteArray(0)
        cachedChunkIndex = -1L
        runCatching { raf?.close() }
        raf = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val vaultManager: EncryptedVaultManager
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AesGcmVaultDataSource(vaultManager)
    }
}
