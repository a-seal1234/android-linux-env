package com.alw.workstation.core.proot.oci

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Extracts OCI and Docker image layer archives sequentially into a PRoot rootfs directory,
 * with full support for Gzip and Zstd decompression, OCI whiteout deletion semantics
 * (.wh.<file> and .wh..wh..opq), anti-ZipSlip protection, and symlink/hardlink adaptation.
 */
class OciLayerExtractor(
    private val bufferSize: Int = 65536
) {

    companion object {
        private const val TAG = "ALW::OciLayerExtractor"
        const val WHITEOUT_PREFIX = ".wh."
        const val OPAQUE_WHITEOUT = ".wh..wh..opq"

        // Magic bytes
        private val GZIP_MAGIC = byteArrayOf(0x1F.toByte(), 0x8B.toByte())
        private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
    }

    data class LayerExtractionProgress(
        val layerIndex: Int,
        val totalLayers: Int,
        val currentEntry: String,
        val entriesExtracted: Long,
        val bytesExtracted: Long
    )

    /**
     * Extracts an ordered sequence of layer archive files into [targetDir].
     * Layers MUST be provided in order from base layer (index 0) to top layer.
     */
    suspend fun extractLayers(
        layerFiles: List<File>,
        targetDir: File,
        onProgress: ((LayerExtractionProgress) -> Unit)? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val canonicalTargetDir = targetDir.canonicalFile

        var totalEntriesExtracted = 0L

        try {
            layerFiles.forEachIndexed { index, layerFile ->
                Log.i(TAG, "Extracting layer ${index + 1}/${layerFiles.size}: ${layerFile.name} (${layerFile.length()} bytes)")
                val entries = extractSingleLayer(
                    layerFile = layerFile,
                    layerIndex = index,
                    totalLayers = layerFiles.size,
                    targetDir = canonicalTargetDir,
                    onProgress = onProgress
                )
                totalEntriesExtracted += entries
            }
            Result.success(totalEntriesExtracted)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed extracting layer sequence into ${targetDir.absolutePath}", e)
            Result.failure(e)
        }
    }

    /**
     * Extracts a single layer archive file into [targetDir] while resolving OCI whiteouts.
     */
    fun extractSingleLayer(
        layerFile: File,
        layerIndex: Int = 0,
        totalLayers: Int = 1,
        targetDir: File,
        onProgress: ((LayerExtractionProgress) -> Unit)? = null
    ): Long {
        var entriesCount = 0L
        var bytesExtracted = 0L

        FileInputStream(layerFile).use { fileIn ->
            val bufferedIn = BufferedInputStream(fileIn, bufferSize)
            bufferedIn.mark(8)
            val magic = ByteArray(4)
            val readMagic = bufferedIn.read(magic)
            bufferedIn.reset()

            val decompressorStream: InputStream = when {
                readMagic >= 2 && magic[0] == GZIP_MAGIC[0] && magic[1] == GZIP_MAGIC[1] -> {
                    GzipCompressorInputStream(bufferedIn)
                }
                readMagic >= 4 && magic[0] == ZSTD_MAGIC[0] && magic[1] == ZSTD_MAGIC[1] &&
                    magic[2] == ZSTD_MAGIC[2] && magic[3] == ZSTD_MAGIC[3] -> {
                    com.github.luben.zstd.ZstdInputStream(bufferedIn)
                }
                else -> bufferedIn
            }

            TarArchiveInputStream(decompressorStream).use { tarIn ->
                val buffer = ByteArray(bufferSize)
                var entry: TarArchiveEntry? = tarIn.nextEntry

                while (entry != null) {
                    var entryName = entry.name
                    if (entryName.startsWith("./")) entryName = entryName.removePrefix("./")
                    if (entryName.startsWith("/")) entryName = entryName.removePrefix("/")

                    if (entryName.isNotEmpty()) {
                        val isHandledAsWhiteout = handleWhiteoutIfApplicable(targetDir, entryName)

                        if (!isHandledAsWhiteout) {
                            val targetFile = File(targetDir, entryName)
                            val canonicalTarget = targetFile.canonicalFile

                            // Anti-ZipSlip boundary guard
                            if (!canonicalTarget.toPath().startsWith(targetDir.toPath())) {
                                throw SecurityException("Path traversal attempt in OCI layer entry: $entryName")
                            }

                            if (entry.isDirectory) {
                                canonicalTarget.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                canonicalTarget.parentFile?.mkdirs()
                                if (canonicalTarget.exists()) canonicalTarget.delete()
                                createSymbolicLinkSafe(canonicalTarget, entry.linkName)
                            } else if (entry.isLink) {
                                canonicalTarget.parentFile?.mkdirs()
                                val sourceLink = File(targetDir, entry.linkName)
                                createHardLinkSafe(canonicalTarget, sourceLink)
                            } else {
                                canonicalTarget.parentFile?.mkdirs()
                                FileOutputStream(canonicalTarget).use { out ->
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                        bytesExtracted += len
                                    }
                                }

                                if ((entry.mode and 0b001_001_001) != 0 || isBinaryPath(entryName)) {
                                    canonicalTarget.setExecutable(true, false)
                                }
                                canonicalTarget.setReadable(true, false)
                            }

                            entriesCount++
                            if (entriesCount % 100 == 0L) {
                                onProgress?.invoke(
                                    LayerExtractionProgress(
                                        layerIndex = layerIndex,
                                        totalLayers = totalLayers,
                                        currentEntry = entryName,
                                        entriesExtracted = entriesCount,
                                        bytesExtracted = bytesExtracted
                                    )
                                )
                            }
                        }
                    }

                    entry = tarIn.nextEntry
                }
            }
        }

        return entriesCount
    }

    /**
     * Inspects entryName for OCI whiteout markers:
     * 1. Opaque directory marker: `.wh..wh..opq`
     * 2. File deletion marker: `.wh.<filename>`
     * Returns true if the entry was a whiteout marker and was processed (so it should not be created).
     */
    private fun handleWhiteoutIfApplicable(targetDir: File, entryName: String): Boolean {
        val entryFile = File(entryName)
        val fileName = entryFile.name
        val parentPath = entryFile.parent ?: ""

        // Case 1: Opaque directory whiteout (.wh..wh..opq)
        if (fileName == OPAQUE_WHITEOUT) {
            val parentDir = if (parentPath.isEmpty()) targetDir else File(targetDir, parentPath)
            if (parentDir.exists() && parentDir.isDirectory) {
                // Delete all existing children in the directory
                parentDir.listFiles()?.forEach { it.deleteRecursively() }
            } else {
                parentDir.mkdirs()
            }
            return true
        }

        // Case 2: Standard file whiteout (.wh.<targetName>)
        if (fileName.startsWith(WHITEOUT_PREFIX)) {
            val targetName = fileName.removePrefix(WHITEOUT_PREFIX)
            val targetToDelete = if (parentPath.isEmpty()) {
                File(targetDir, targetName)
            } else {
                File(File(targetDir, parentPath), targetName)
            }

            if (targetToDelete.exists()) {
                targetToDelete.deleteRecursively()
            }
            return true
        }

        return false
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
            try {
                target.writeText("!#alw-symlink\n$linkTarget\n")
            } catch (err: Throwable) {
                Log.w(TAG, "Failed creating symlink: ${target.absolutePath} -> $linkTarget")
            }
        }
    }

    private fun createHardLinkSafe(target: File, source: File) {
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (e: Throwable) {
            // Emulate hardlink as a symlink due to Android non-root security boundaries
            createSymbolicLinkSafe(target, source.absolutePath)
        }
    }
}
