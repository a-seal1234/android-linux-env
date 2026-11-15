package com.alw.workstation.core.proot.emulator

import android.util.Log
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.ElfAbiType
import com.alw.workstation.core.proot.driver.ElfAbiValidator
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Manages the shared x86_64 translation engine overlays (FEX-Emu / box64) on the host
 * (`files/overlays/<engine>/<version>/`).
 *
 * Enforces SHA256 cryptographic verification and 64-bit ARM (EM_AARCH64 + glibc) ELF ABI
 * validation before activating an overlay for guest Linux execution.
 */
class EmulatorManager(
    private val dirs: RuntimeDirectories
) {
    companion object {
        private const val TAG = "ALW::EmulatorManager"
        const val ACTIVE_OVERLAY_MARKER = "active-overlay.txt"
    }

    init {
        dirs.ensureDirectoriesExist()
    }

    /**
     * Lists all installed emulator overlays, optionally filtered by engine ("fex" or "box64").
     */
    fun listInstalledOverlays(engine: String? = null): List<EmulatorManifest> {
        val engines = if (engine != null) {
            listOf(engine.lowercase())
        } else {
            listOf("fex", "box64")
        }

        val manifests = mutableListOf<EmulatorManifest>()
        for (eng in engines) {
            val baseDir = File(dirs.overlaysBaseDir, eng)
            if (!baseDir.exists() || !baseDir.isDirectory) continue

            baseDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "active") {
                    val manifestFile = File(dir, "manifest.json")
                    if (manifestFile.exists()) {
                        try {
                            manifests.add(EmulatorManifest.fromJson(manifestFile.readText()))
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading manifest for $eng/${dir.name}: ${e.message}")
                        }
                    }
                }
            }
        }
        return manifests.sortedWith(compareBy({ it.engine }, { it.version }))
    }

    /**
     * Retrieves manifest for an installed emulator overlay version.
     */
    fun getInstalledOverlay(engine: String, version: String): EmulatorManifest? {
        val overlayDir = dirs.getOverlayDir(engine, version)
        val manifestFile = File(overlayDir, "manifest.json")
        if (!manifestFile.exists()) return null
        return try {
            EmulatorManifest.fromJson(manifestFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if an overlay version is installed and valid in the shared store.
     */
    fun isOverlayInstalled(engine: String, version: String): Boolean {
        return getInstalledOverlay(engine, version) != null
    }

    /**
     * Installs an emulator overlay archive into the shared store with cryptographic
     * and ELF ABI validation.
     */
    fun installOverlayArchive(
        engine: String,
        version: String,
        archiveFile: File,
        expectedSha256: String? = null,
        displayName: String? = null
    ): EmulatorManifest {
        val eng = engine.lowercase()

        // 1. Verify SHA256 checksum if provided
        if (!expectedSha256.isNullOrBlank()) {
            val actual = computeSha256(archiveFile)
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                throw SecurityException(
                    "Emulator archive SHA256 checksum mismatch for $eng/$version: expected $expectedSha256 but got $actual"
                )
            }
        }

        val targetDir = dirs.getOverlayDir(eng, version)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        // 2. Unpack archive
        unpackArchive(archiveFile, targetDir)

        // 3. Locate interpreter binary
        val expectedBinaryName = if (eng == "fex") "FEXInterpreter" else "box64"
        var interpreterFile = File(targetDir, "bin/$expectedBinaryName")
        if (!interpreterFile.exists()) {
            interpreterFile = File(targetDir, expectedBinaryName)
        }

        if (!interpreterFile.exists()) {
            // Recursive scan for matching binary name
            val candidate = targetDir.walkTopDown().find {
                it.isFile && it.name == expectedBinaryName
            }
            if (candidate != null) {
                val binDir = File(targetDir, "bin")
                binDir.mkdirs()
                val dest = File(binDir, expectedBinaryName)
                candidate.copyTo(dest, overwrite = true)
                interpreterFile = dest
            }
        }

        if (!interpreterFile.exists()) {
            targetDir.deleteRecursively()
            throw IllegalArgumentException(
                "Overlay archive does not contain expected $eng interpreter binary ($expectedBinaryName)"
            )
        }

        // 4. Validate ELF ABI (must be 64-bit ARM with glibc)
        val abiResult = ElfAbiValidator.validate(interpreterFile)
        if (abiResult.abiType == ElfAbiType.BIONIC_ANDROID) {
            targetDir.deleteRecursively()
            throw IllegalStateException(
                "Incompatible binary ABI: ${interpreterFile.name} was compiled for Android Bionic and will crash glibc loaders inside Arch Linux PRoot. Native glibc aarch64 build required."
            )
        }

        if (abiResult.abiType == ElfAbiType.UNSUPPORTED_ARCH || abiResult.abiType == ElfAbiType.INVALID_ELF) {
            targetDir.deleteRecursively()
            throw IllegalArgumentException(
                "Invalid or unsupported ELF emulator binary: ${abiResult.detailMessage}"
            )
        }

        // Ensure executable permissions
        interpreterFile.setExecutable(true, false)

        // 5. Detect thunks and rootfs overlays
        val hasThunks = File(targetDir, "thunks").exists() || File(targetDir, "lib").exists()
        val hasRootfs = File(targetDir, "rootfs").exists()

        // Compute relative interpreter path
        val relPath = interpreterFile.relativeTo(targetDir).path

        val sha256 = expectedSha256?.takeIf { it.isNotBlank() } ?: computeSha256(interpreterFile)

        // 6. Write manifest
        val manifest = EmulatorManifest(
            engine = eng,
            version = version,
            displayName = displayName ?: "${eng.uppercase()} $version",
            interpreterRelativePath = relPath,
            sha256Checksum = sha256,
            abiType = abiResult.abiType,
            hasThunkLibs = hasThunks,
            hasRootfsOverlay = hasRootfs,
            installedAtEpochMs = System.currentTimeMillis()
        )

        File(targetDir, "manifest.json").writeText(manifest.toJson())

        // 7. Auto-activate if no active overlay exists for this engine
        if (getActiveOverlay(eng) == null) {
            activateOverlay(eng, version)
        }

        return manifest
    }

    /**
     * Activates an installed emulator overlay version.
     */
    fun activateOverlay(engine: String, version: String): File {
        val eng = engine.lowercase()
        val overlayDir = dirs.getOverlayDir(eng, version)
        if (!overlayDir.exists() || !overlayDir.isDirectory) {
            throw IllegalArgumentException("Cannot activate non-existent overlay: $eng/$version")
        }

        val baseDir = File(dirs.overlaysBaseDir, eng)
        baseDir.mkdirs()

        // Write active marker file
        File(baseDir, ACTIVE_OVERLAY_MARKER).writeText(version)

        // Manage 'active' symlink/junction
        val activeLink = File(baseDir, "active")
        if (activeLink.exists() || Files.isSymbolicLink(activeLink.toPath())) {
            try {
                Files.delete(activeLink.toPath())
            } catch (e: Exception) {
                activeLink.deleteRecursively()
            }
        }

        try {
            Files.createSymbolicLink(activeLink.toPath(), overlayDir.toPath())
        } catch (e: Exception) {
            Log.d(TAG, "Symlink creation fallback for active overlay: ${e.message}")
        }

        return overlayDir
    }

    /**
     * Retrieves the currently active overlay manifest for an engine.
     */
    fun getActiveOverlay(engine: String): EmulatorManifest? {
        val eng = engine.lowercase()
        val baseDir = File(dirs.overlaysBaseDir, eng)
        val activeMarker = File(baseDir, ACTIVE_OVERLAY_MARKER)
        if (!activeMarker.exists()) return null
        val version = activeMarker.readText().trim()
        return getInstalledOverlay(eng, version)
    }

    /**
     * Deactivates the currently active overlay for an engine.
     */
    fun deactivateOverlay(engine: String): Boolean {
        val eng = engine.lowercase()
        val baseDir = File(dirs.overlaysBaseDir, eng)
        val activeMarker = File(baseDir, ACTIVE_OVERLAY_MARKER)
        val activeLink = File(baseDir, "active")
        var deleted = false
        if (activeMarker.exists()) {
            activeMarker.delete()
            deleted = true
        }
        if (activeLink.exists() || Files.isSymbolicLink(activeLink.toPath())) {
            try {
                Files.delete(activeLink.toPath())
            } catch (e: Exception) {
                activeLink.deleteRecursively()
            }
            deleted = true
        }
        return deleted
    }

    /**
     * Deletes an installed overlay from the shared store.
     */
    fun deleteOverlay(engine: String, version: String): Boolean {
        val eng = engine.lowercase()
        val active = getActiveOverlay(eng)
        if (active?.version == version) {
            deactivateOverlay(eng)
        }
        val overlayDir = dirs.getOverlayDir(eng, version)
        return if (overlayDir.exists()) overlayDir.deleteRecursively() else false
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
            else -> {
                // If it's a direct executable binary, place it into bin/
                val binDir = File(targetDir, "bin")
                binDir.mkdirs()
                val dest = File(binDir, archiveFile.name)
                archiveFile.copyTo(dest, overwrite = true)
                dest.setExecutable(true, false)
            }
        }
    }

    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        TarArchiveInputStream(input).use { tarIn ->
            var entry: TarArchiveEntry? = tarIn.nextEntry
            while (entry != null) {
                val destFile = File(targetDir, entry.name)
                if (!destFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Tar path traversal attempt: ${entry.name}")
                }
                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        tarIn.copyTo(fos)
                    }
                    if (entry.mode and 0x49 != 0) { // Check user/group/other execute bit
                        destFile.setExecutable(true, false)
                    }
                }
                entry = tarIn.nextEntry
            }
        }
    }
}
