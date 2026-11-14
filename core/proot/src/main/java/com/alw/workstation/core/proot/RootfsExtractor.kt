package com.alw.workstation.core.proot

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * High-performance streamed rootfs archive extractor supporting .tar, .tar.gz, and .tar.xz
 * with POSIX/GNU tar format compatibility, symlink restoration, executable permission preservation,
 * directory traversal protection, and auto-configuration of guest network files.
 */
class RootfsExtractor(private val dirs: RuntimeDirectories) {

    companion object {
        private const val TAG = "ALW::RootfsExtractor"
        private const val BUFFER_SIZE = 65536
    }

    enum class CompressionType {
        NONE,
        GZIP,
        XZ,
        AUTO
    }

    data class ExtractionProgress(
        val currentEntry: String,
        val entriesExtracted: Long,
        val bytesExtracted: Long,
        val totalBytes: Long? = null,
        val isComplete: Boolean = false
    )

    data class ExtractionResult(
        val isSuccess: Boolean,
        val entriesExtracted: Long,
        val bytesExtracted: Long,
        val errorMessage: String? = null
    )

    /**
     * Extracts an archive file from the host filesystem into the target rootfs directory.
     */
    fun extractArchiveFile(
        archiveFile: File,
        targetDir: File,
        compressionType: CompressionType = CompressionType.AUTO,
        onProgress: ((ExtractionProgress) -> Unit)? = null
    ): ExtractionResult {
        if (!archiveFile.exists() || !archiveFile.isFile) {
            return ExtractionResult(
                isSuccess = false,
                entriesExtracted = 0,
                bytesExtracted = 0,
                errorMessage = "Archive file not found: ${archiveFile.absolutePath}"
            )
        }

        val resolvedCompression = if (compressionType == CompressionType.AUTO) {
            detectCompressionByFileName(archiveFile.name)
        } else {
            compressionType
        }

        return try {
            FileInputStream(archiveFile).use { fis ->
                extractArchiveStream(
                    stream = fis,
                    compressionType = resolvedCompression,
                    targetDir = targetDir,
                    totalBytesEstimate = archiveFile.length(),
                    onProgress = onProgress
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for ${archiveFile.name}: ${e.message}", e)
            ExtractionResult(
                isSuccess = false,
                entriesExtracted = 0,
                bytesExtracted = 0,
                errorMessage = e.message ?: "Extraction failed"
            )
        }
    }

    /**
     * Extracts an archive from an arbitrary InputStream into the target rootfs directory.
     */
    fun extractArchiveStream(
        stream: InputStream,
        compressionType: CompressionType = CompressionType.AUTO,
        targetDir: File,
        totalBytesEstimate: Long? = null,
        onProgress: ((ExtractionProgress) -> Unit)? = null
    ): ExtractionResult {
        dirs.ensureDirectoriesExist()
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val canonicalTargetDir = targetDir.canonicalFile
        val bufferedStream = BufferedInputStream(stream, BUFFER_SIZE)

        val resolvedType = if (compressionType == CompressionType.AUTO) {
            detectCompressionByMagicBytes(bufferedStream)
        } else {
            compressionType
        }

        val decompressorStream: InputStream = when (resolvedType) {
            CompressionType.GZIP -> GzipCompressorInputStream(bufferedStream)
            CompressionType.XZ -> XZCompressorInputStream(bufferedStream)
            CompressionType.NONE, CompressionType.AUTO -> bufferedStream
        }

        var entriesCount = 0L
        var totalBytesCount = 0L

        try {
            TarArchiveInputStream(decompressorStream).use { tarIn ->
                val buffer = ByteArray(BUFFER_SIZE)
                var entry: TarArchiveEntry? = tarIn.nextEntry

                while (entry != null) {
                    var entryName = entry.name
                    if (entryName.startsWith("./")) {
                        entryName = entryName.removePrefix("./")
                    }
                    if (entryName.startsWith("/")) {
                        entryName = entryName.removePrefix("/")
                    }

                    if (entryName.isNotEmpty()) {
                        val targetFile = File(canonicalTargetDir, entryName)
                        val canonicalTarget = targetFile.canonicalFile

                        // Anti-ZipSlip security boundary check
                        if (!canonicalTarget.toPath().startsWith(canonicalTargetDir.toPath())) {
                            throw SecurityException("Malicious path traversal detected in tar entry: $entryName")
                        }

                        if (entry.isDirectory) {
                            canonicalTarget.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            canonicalTarget.parentFile?.mkdirs()
                            if (canonicalTarget.exists()) {
                                canonicalTarget.delete()
                            }
                            createSymbolicLinkSafe(canonicalTarget, entry.linkName)
                        } else if (entry.isLink) {
                            canonicalTarget.parentFile?.mkdirs()
                            val sourceLink = File(canonicalTargetDir, entry.linkName)
                            createHardLinkSafe(canonicalTarget, sourceLink)
                        } else {
                            canonicalTarget.parentFile?.mkdirs()
                            FileOutputStream(canonicalTarget).use { out ->
                                var len: Int
                                while (tarIn.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                    totalBytesCount += len
                                }
                            }

                            // Restore executable permissions
                            if ((entry.mode and 0b001_001_001) != 0 || isBinaryPath(entryName)) {
                                canonicalTarget.setExecutable(true, false)
                            }
                            canonicalTarget.setReadable(true, false)
                        }

                        entriesCount++
                        if (entriesCount % 100 == 0L) {
                            onProgress?.invoke(
                                ExtractionProgress(
                                    currentEntry = entryName,
                                    entriesExtracted = entriesCount,
                                    bytesExtracted = totalBytesCount,
                                    totalBytes = totalBytesEstimate,
                                    isComplete = false
                                )
                            )
                        }
                    }

                    entry = tarIn.nextEntry
                }
            }

            // Post-extraction environment initialization
            ensureResolvConf(canonicalTargetDir)
            ensureHosts(canonicalTargetDir)

            onProgress?.invoke(
                ExtractionProgress(
                    currentEntry = "Complete",
                    entriesExtracted = entriesCount,
                    bytesExtracted = totalBytesCount,
                    totalBytes = totalBytesEstimate,
                    isComplete = true
                )
            )

            return ExtractionResult(
                isSuccess = true,
                entriesExtracted = entriesCount,
                bytesExtracted = totalBytesCount,
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stream extraction error: ${e.message}", e)
            return ExtractionResult(
                isSuccess = false,
                entriesExtracted = entriesCount,
                bytesExtracted = totalBytesCount,
                errorMessage = e.message ?: "Unknown extraction error"
            )
        }
    }

    private fun isBinaryPath(path: String): Boolean {
        return path.startsWith("bin/") ||
            path.startsWith("sbin/") ||
            path.startsWith("usr/bin/") ||
            path.startsWith("usr/sbin/") ||
            path.startsWith("usr/local/bin/") ||
            path.startsWith("lib/ld-") ||
            path.startsWith("usr/lib/ld-")
    }

    private fun createSymbolicLinkSafe(target: File, linkTarget: String) {
        try {
            Files.createSymbolicLink(target.toPath(), Paths.get(linkTarget))
        } catch (e: Throwable) {
            // Fallback for filesystems that do not support native POSIX symlinks
            try {
                target.writeText("!#alw-symlink\n$linkTarget\n")
            } catch (err: Throwable) {
                Log.w(TAG, "Failed creating symlink or fallback for ${target.absolutePath} -> $linkTarget")
            }
        }
    }

    private fun createHardLinkSafe(target: File, source: File) {
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (e: Throwable) {
            // Fallback: copy file contents if hardlinks fail across mount boundaries
            try {
                if (source.exists()) {
                    Files.copy(source.toPath(), target.toPath())
                }
            } catch (err: Throwable) {
                Log.w(TAG, "Failed creating hard link or copy for ${target.absolutePath} -> ${source.absolutePath}")
            }
        }
    }

    /**
     * Ensures /etc/resolv.conf is populated with standard DNS servers so guest network access works.
     */
    fun ensureResolvConf(rootfsDir: File) {
        val resolvFile = File(rootfsDir, "etc/resolv.conf")
        if (resolvFile.exists() && resolvFile.length() > 0) return

        try {
            resolvFile.parentFile?.mkdirs()
            resolvFile.writeText(
                """
                # Generated by Android Linux Workstation
                nameserver 1.1.1.1
                nameserver 8.8.8.8
                nameserver 8.8.4.4
                """.trimIndent() + "\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write resolv.conf: ${e.message}")
        }
    }

    /**
     * Ensures /etc/hosts contains localhost definitions.
     */
    fun ensureHosts(rootfsDir: File) {
        val hostsFile = File(rootfsDir, "etc/hosts")
        if (hostsFile.exists() && hostsFile.length() > 0) return

        try {
            hostsFile.parentFile?.mkdirs()
            hostsFile.writeText(
                """
                127.0.0.1   localhost localhost.localdomain
                ::1         localhost localhost.localdomain ip6-localhost ip6-loopback
                """.trimIndent() + "\n"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write hosts: ${e.message}")
        }
    }

    private fun detectCompressionByFileName(name: String): CompressionType {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> CompressionType.GZIP
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> CompressionType.XZ
            lower.endsWith(".tar") -> CompressionType.NONE
            else -> CompressionType.AUTO
        }
    }

    private fun detectCompressionByMagicBytes(stream: BufferedInputStream): CompressionType {
        stream.mark(8)
        val header = ByteArray(6)
        val bytesRead = stream.read(header, 0, 6)
        stream.reset()

        if (bytesRead < 2) return CompressionType.NONE

        // GZIP magic number: 0x1F, 0x8B
        if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
            return CompressionType.GZIP
        }

        // XZ magic bytes: 0xFD, '7', 'z', 'X', 'Z', 0x00
        if (bytesRead >= 6 &&
            header[0] == 0xFD.toByte() &&
            header[1] == '7'.code.toByte() &&
            header[2] == 'z'.code.toByte() &&
            header[3] == 'X'.code.toByte() &&
            header[4] == 'Z'.code.toByte() &&
            header[5] == 0x00.toByte()
        ) {
            return CompressionType.XZ
        }

        return CompressionType.NONE
    }
}
