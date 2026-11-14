package com.alw.workstation.core.proot.driver

import android.util.Log
import com.alw.workstation.core.proot.RuntimeDirectories
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Manages the shared Turnip / Adreno GPU driver repository on the Android host (files/drivers/turnip/<version>/).
 * Enforces SHA256 cryptographic verification and ELF glibc vs Bionic ABI validation
 * before activating drivers for the guest Wayland workstation.
 */
class TurnipDriverManager(
    private val dirs: RuntimeDirectories,
    private val releaseFetcher: GitHubReleaseFetcher = GitHubReleaseFetcher()
) {
    companion object {
        private const val TAG = "ALW::TurnipDriverMgr"
        const val ACTIVE_DRIVER_MARKER = "active-driver.txt"
    }

    init {
        dirs.ensureDirectoriesExist()
    }

    /**
     * Lists all installed drivers in the shared driver store.
     */
    fun listInstalledDrivers(): List<TurnipDriverManifest> {
        val baseDir = dirs.turnipDriversBaseDir
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return emptyList()
        }

        val manifests = mutableListOf<TurnipDriverManifest>()
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != "active") {
                val manifestFile = File(dir, "manifest.json")
                if (manifestFile.exists()) {
                    try {
                        manifests.add(TurnipDriverManifest.fromJson(manifestFile.readText()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed reading manifest for ${dir.name}: ${e.message}")
                    }
                }
            }
        }
        return manifests.sortedBy { it.version }
    }

    /**
     * Retrieves manifest for an installed driver version.
     */
    fun getInstalledDriver(version: String): TurnipDriverManifest? {
        val driverDir = dirs.getTurnipDriverDir(version)
        val manifestFile = File(driverDir, "manifest.json")
        if (!manifestFile.exists()) return null
        return try {
            TurnipDriverManifest.fromJson(manifestFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a driver version is installed and valid in the shared store.
     */
    fun isDriverInstalled(version: String): Boolean {
        return getInstalledDriver(version) != null
    }

    /**
     * Downloads and installs a GitHub driver release into the shared store.
     */
    fun installDriverRelease(
        release: DriverRelease,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): TurnipDriverManifest {
        val tempDir = File(dirs.prootTmpDir, "driver_downloads")
        tempDir.mkdirs()
        val tempFile = File(tempDir, release.archiveName)
        try {
            releaseFetcher.downloadRelease(release, tempFile, onProgress)
            return installDriverArchive(
                version = release.version,
                archiveFile = tempFile,
                expectedSha256 = release.sha256Checksum,
                isProprietary = false,
                releaseTag = release.releaseTag
            )
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    /**
     * Installs a driver archive into the shared store with cryptographic and ELF validation.
     */
    fun installDriverArchive(
        version: String,
        archiveFile: File,
        expectedSha256: String,
        isProprietary: Boolean = false,
        releaseTag: String = ""
    ): TurnipDriverManifest {
        // 1. Verify SHA256 Checksum
        if (!releaseFetcher.verifyChecksum(archiveFile, expectedSha256)) {
            val actual = releaseFetcher.computeSha256(archiveFile)
            throw SecurityException(
                "Driver archive SHA256 checksum mismatch for $version: expected $expectedSha256 but got $actual"
            )
        }

        val targetDir = dirs.getTurnipDriverDir(version)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        // 2. Unpack archive based on extension
        unpackArchive(archiveFile, targetDir)

        // 3. Locate Vulkan driver shared object
        val expectedLibName = if (isProprietary) "libvulkan_adreno.so" else "libvulkan_freedreno.so"
        var driverFile = File(targetDir, expectedLibName)

        if (!driverFile.exists()) {
            // Check subdirectories or aliases
            val candidate = targetDir.walkTopDown().find {
                it.isFile && (it.name == expectedLibName ||
                        it.name.contains("freedreno") && it.name.endsWith(".so"))
            }
            if (candidate != null) {
                if (candidate.parentFile != targetDir) {
                    val dest = File(targetDir, expectedLibName)
                    candidate.copyTo(dest, overwrite = true)
                    driverFile = dest
                } else {
                    driverFile = candidate
                }
            }
        }

        if (!driverFile.exists()) {
            targetDir.deleteRecursively()
            throw IllegalArgumentException(
                "Driver archive does not contain expected Vulkan driver shared object ($expectedLibName)"
            )
        }

        // 4. Validate ELF ABI (glibc vs Bionic)
        val abiResult = ElfAbiValidator.validate(driverFile)
        if (!isProprietary && abiResult.abiType == ElfAbiType.BIONIC_ANDROID) {
            targetDir.deleteRecursively()
            throw IllegalStateException(
                "Incompatible driver ABI: ${driverFile.name} was compiled for Android Bionic and will crash glibc loaders inside Arch Linux PRoot. Native glibc aarch64 build required."
            )
        }

        if (abiResult.abiType == ElfAbiType.UNSUPPORTED_ARCH || abiResult.abiType == ElfAbiType.INVALID_ELF) {
            targetDir.deleteRecursively()
            throw IllegalArgumentException(
                "Invalid or unsupported ELF driver binary: ${abiResult.detailMessage}"
            )
        }

        // 5. Generate deterministic ICD JSON configuration
        val icdFileName = if (isProprietary) "adreno_icd.aarch64.json" else "freedreno_icd.aarch64.json"
        val icdFile = File(targetDir, icdFileName)
        val targetIcdLibPath = "/opt/drivers/turnip/active/${driverFile.name}"

        val icdJson = if (isProprietary) {
            IcdConfigGenerator.generateAdrenoProprietaryIcdJson(libraryPath = targetIcdLibPath)
        } else {
            IcdConfigGenerator.generateFreedrenoIcdJson(libraryPath = targetIcdLibPath)
        }
        IcdConfigGenerator.writeIcdFile(icdFile, icdJson)

        // 6. Write driver manifest
        val manifest = TurnipDriverManifest(
            version = version,
            releaseTag = releaseTag,
            driverType = if (isProprietary) TurnipDriverManifest.DriverType.ADRENO_PROPRIETARY else TurnipDriverManifest.DriverType.TURNIP_KGSL,
            sha256Checksum = expectedSha256,
            apiVersion = "1.4.0",
            supportedGpus = listOf("Adreno 825 (A8xx)", "Snapdragon 8s Gen 4"),
            abiType = abiResult.abiType,
            libraryFileName = driverFile.name,
            icdFileName = icdFileName,
            tuDebugRecommendation = "noconform"
        )
        File(targetDir, "manifest.json").writeText(manifest.toJson())

        return manifest
    }

    /**
     * Activates a driver version for the workstation environment.
     */
    fun activateDriver(version: String): File {
        val driverDir = dirs.getTurnipDriverDir(version)
        if (!driverDir.exists() || !File(driverDir, "manifest.json").exists()) {
            throw IllegalArgumentException("Turnip driver version '$version' is not installed in shared store")
        }

        val activeMarker = File(dirs.turnipDriversBaseDir, ACTIVE_DRIVER_MARKER)
        activeMarker.writeText(version.trim())

        // Create or update 'active' link/directory under files/drivers/turnip/active
        val activeLink = File(dirs.turnipDriversBaseDir, "active")
        try {
            if (activeLink.exists()) {
                if (Files.isSymbolicLink(activeLink.toPath())) {
                    activeLink.delete()
                } else {
                    activeLink.deleteRecursively()
                }
            }
            Files.createSymbolicLink(activeLink.toPath(), driverDir.toPath())
        } catch (e: Exception) {
            // Fallback for filesystems that forbid symlinks
            Log.d(TAG, "Symlink creation fallback for active driver: ${e.message}")
        }

        return driverDir
    }

    /**
     * Retrieves the currently active driver manifest, if set.
     */
    fun getActiveDriver(): TurnipDriverManifest? {
        val activeMarker = File(dirs.turnipDriversBaseDir, ACTIVE_DRIVER_MARKER)
        if (!activeMarker.exists()) return null
        val version = activeMarker.readText().trim()
        return getInstalledDriver(version)
    }

    /**
     * Deletes a driver version from the shared store.
     */
    fun deleteDriver(version: String): Boolean {
        val active = getActiveDriver()
        if (active?.version == version) {
            File(dirs.turnipDriversBaseDir, ACTIVE_DRIVER_MARKER).delete()
            File(dirs.turnipDriversBaseDir, "active").deleteRecursively()
        }
        val driverDir = dirs.getTurnipDriverDir(version)
        return if (driverDir.exists()) driverDir.deleteRecursively() else false
    }

    private fun unpackArchive(archiveFile: File, targetDir: File) {
        val name = archiveFile.name.lowercase()
        when {
            name.endsWith(".tar.xz") -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        XZCompressorInputStream(bis).use { xzIn ->
                            extractTar(xzIn, targetDir)
                        }
                    }
                }
            }
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        GzipCompressorInputStream(bis).use { gzIn ->
                            extractTar(gzIn, targetDir)
                        }
                    }
                }
            }
            name.endsWith(".tar") -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        extractTar(bis, targetDir)
                    }
                }
            }
            name.endsWith(".zip") -> {
                FileInputStream(archiveFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        ZipInputStream(bis).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                val destFile = File(targetDir, entry.name)
                                // ZipSlip guard
                                if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                                    throw SecurityException("ZipSlip path traversal attempt: ${entry.name}")
                                }
                                if (entry.isDirectory) {
                                    destFile.mkdirs()
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    FileOutputStream(destFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                    }
                }
            }
            name.endsWith(".so") -> {
                // Direct .so copy
                val dest = File(targetDir, archiveFile.name)
                archiveFile.copyTo(dest, overwrite = true)
            }
            else -> {
                throw IllegalArgumentException("Unsupported driver archive format: ${archiveFile.name}")
            }
        }
    }

    private fun extractTar(input: InputStream, targetDir: File) {
        TarArchiveInputStream(input).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextEntry
            while (entry != null) {
                val destFile = File(targetDir, entry.name)
                // TarSlip guard
                if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("TarSlip path traversal attempt: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        tarIn.copyTo(fos)
                    }
                }
                entry = tarIn.nextEntry
            }
        }
    }
}
