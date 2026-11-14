package com.alw.workstation.core.proot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Downloads official Arch Linux ARM rootfs archives with mirror failover,
 * HTTP redirect following, streamed progress callbacks, and atomic local caching.
 */
class RootfsDownloader(
    private val dirs: RuntimeDirectories,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val minValidSize: Long = MIN_VALID_ROOTFS_SIZE
) {

    companion object {
        private const val TAG = "ALW::RootfsDownloader"
        private const val BUFFER_SIZE = 65536
        private const val MIN_VALID_ROOTFS_SIZE = 50 * 1024 * 1024L // At least 50MB

        val DEFAULT_ARCH_AARCH64_MIRRORS = listOf(
            "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            "http://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            "http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            "http://dk.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            "http://de3.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
        )
    }

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val percent: Int,
        val speedBytesPerSec: Long,
        val currentMirror: String,
        val isComplete: Boolean = false
    )

    /**
     * Returns the cached rootfs file if already downloaded and valid.
     */
    fun getCachedRootfsFile(fileName: String = "ArchLinuxARM-aarch64-latest.tar.gz"): File {
        val downloadDir = File(dirs.filesDir, "downloads")
        return File(downloadDir, fileName)
    }

    /**
     * Checks if a valid cached rootfs archive already exists.
     */
    fun isRootfsCached(fileName: String = "ArchLinuxARM-aarch64-latest.tar.gz"): Boolean {
        val file = getCachedRootfsFile(fileName)
        return file.exists() && file.isFile && file.length() >= minValidSize
    }

    /**
     * Downloads the Arch Linux ARM rootfs archive, trying mirrors sequentially upon failure.
     */
    suspend fun downloadRootfs(
        mirrors: List<String> = DEFAULT_ARCH_AARCH64_MIRRORS,
        targetFile: File = getCachedRootfsFile(),
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (targetFile.exists() && targetFile.length() >= minValidSize) {
            Log.i(TAG, "Cached rootfs already exists: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            onProgress?.invoke(
                DownloadProgress(
                    bytesDownloaded = targetFile.length(),
                    totalBytes = targetFile.length(),
                    percent = 100,
                    speedBytesPerSec = 0L,
                    currentMirror = "local-cache",
                    isComplete = true
                )
            )
            return@withContext targetFile
        }

        val targetDir = targetFile.parentFile ?: dirs.filesDir
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val partFile = File(targetDir, "${targetFile.name}.part")
        val errorHistory = mutableListOf<String>()

        for (mirror in mirrors) {
            if (!coroutineContext.isActive) break
            Log.i(TAG, "Attempting rootfs download from: $mirror")

            try {
                downloadFromUrl(mirror, partFile, onProgress)

                if (partFile.exists() && partFile.length() >= minValidSize) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    val renamed = partFile.renameTo(targetFile)
                    if (!renamed) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                    Log.i(TAG, "Successfully downloaded and cached rootfs: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    onProgress?.invoke(
                        DownloadProgress(
                            bytesDownloaded = targetFile.length(),
                            totalBytes = targetFile.length(),
                            percent = 100,
                            speedBytesPerSec = 0L,
                            currentMirror = mirror,
                            isComplete = true
                        )
                    )
                    return@withContext targetFile
                } else {
                    val size = if (partFile.exists()) partFile.length() else 0L
                    throw IOException("Downloaded file is too small ($size bytes), expected at least $minValidSize bytes")
                }
            } catch (e: Exception) {
                val msg = "Mirror $mirror failed: ${e.message}"
                Log.w(TAG, msg)
                errorHistory.add(msg)
                if (partFile.exists()) {
                    partFile.delete()
                }
            }
        }

        throw IOException("Failed to download rootfs from all mirrors. Errors: ${errorHistory.joinToString("; ")}")
    }

    private suspend fun downloadFromUrl(
        sourceUrl: String,
        partFile: File,
        onProgress: ((DownloadProgress) -> Unit)?
    ) {
        var currentUrl = sourceUrl
        var redirects = 0
        var connection: HttpURLConnection? = null

        try {
            while (redirects < 5) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "ALW-Workstation/1.0 (Android-Linux-Workstation)")

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl.isNullOrBlank()) {
                        throw IOException("Redirect without Location header from $currentUrl")
                    }
                    currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        newUrl
                    } else {
                        URL(url, newUrl).toString()
                    }
                    redirects++
                    continue
                }

                if (status != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    throw IOException("HTTP error $status: ${conn.responseMessage} from $currentUrl")
                }

                connection = conn
                break
            }

            if (connection == null) {
                throw IOException("Too many redirects (max 5) attempting to fetch $sourceUrl")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            var bytesDownloaded = 0L
            var lastUpdateMillis = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            connection.inputStream.use { inputStream ->
                FileOutputStream(partFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        if (!coroutineContext.isActive) {
                            throw IOException("Download cancelled")
                        }
                        outputStream.write(buffer, 0, read)
                        bytesDownloaded += read
                        bytesSinceLastUpdate += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdateMillis
                        if (elapsed >= 150) {
                            currentSpeed = (bytesSinceLastUpdate * 1000L) / elapsed.coerceAtLeast(1)
                            val percent = if (totalBytes != null && totalBytes > 0) {
                                ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            onProgress?.invoke(
                                DownloadProgress(
                                    bytesDownloaded = bytesDownloaded,
                                    totalBytes = totalBytes,
                                    percent = percent,
                                    speedBytesPerSec = currentSpeed,
                                    currentMirror = currentUrl,
                                    isComplete = false
                                )
                            )
                            lastUpdateMillis = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                    outputStream.flush()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
