package com.alw.workstation.core.proot.driver

import com.alw.workstation.core.proot.RuntimeDirectories
import java.io.File
import java.security.MessageDigest

/**
 * Shared-store manager for the Google ANGLE guest GLES bundle (TASK-038).
 *
 * Angular/layout mirrors [TurnipDriverManager]'s store: one versioned directory per
 * bundle plus an atomic `active` pointer, so an upgrade never breaks a live rootfs.
 *
 * The bundle is aarch64 **glibc** (`libEGL.so.1`, `libGLESv2.so.2` linked against
 * Turnip's Vulkan ICD) and is consumed by the guest at `/opt/angle/lib` via
 * `LD_LIBRARY_PATH` + `ANGLE_DEFAULT_PLATFORM=vulkan` from the `alw-gui-run` shim.
 *
 * There is no upstream aarch64 ANGLE package (archlinuxarm ships none and the AUR
 * PKGBUILD builds x86_64 only), so bundles are produced out-of-tree and are
 * validated here before activation: manifest SHA-256 + ELF ABI (a Bionic build
 * would crash the glibc guest loader).
 */
class AngleBundleManager(
    private val dirs: RuntimeDirectories
) {

    companion object {
        const val GUEST_LIB_DIR = "/opt/angle/lib"
        const val MANIFEST_NAME = "angle-bundle.json"
        val REQUIRED_LIBRARIES = listOf("libEGL.so.1", "libGLESv2.so.2")
    }

    data class BundleManifest(
        val version: String,
        val sha256: String,
        val abiType: ElfAbiType = ElfAbiType.GLIBC_AARCH64,
        val source: String = ""
    ) {
        override fun toString(): String = org.json.JSONObject().apply {
            put("version", version)
            put("sha256", sha256)
            put("abiType", abiType.name)
            put("source", source)
        }.toString(2)
    }

    /** True when a validated bundle is active for the next session. */
    fun isBundleInstalled(): Boolean {
        val active = dirs.getActiveAngleBundleDir()
        return active.isDirectory && REQUIRED_LIBRARIES.all { File(active, it).isFile }
    }

    /** Paths bound into the guest as `/opt/angle/lib`. */
    fun activeLibraryDir(): File = dirs.getActiveAngleBundleDir()

    /**
     * Verifies a candidate bundle directory without touching the active pointer:
     * every required library exists, matches the manifest SHA-256, and is a
     * glibc aarch64 ELF.
     */
    fun verifyBundle(bundleDir: File): ElfValidationResult {
        val manifest = readManifest(bundleDir)
            ?: return ElfValidationResult(
                isValid = false,
                abiType = ElfAbiType.INVALID_ELF,
                isAarch64 = false,
                is64Bit = false,
                detectedLibraries = emptyList(),
                detailMessage = "missing $MANIFEST_NAME"
            )

        for (name in REQUIRED_LIBRARIES) {
            val lib = File(bundleDir, name)
            if (!lib.isFile) {
                return invalid("missing $name")
            }
            val actualSha = sha256(lib)
            if (!actualSha.equals(manifest.sha256, ignoreCase = true)) {
                return invalid("SHA-256 mismatch for $name ($actualSha)")
            }
            val abi = ElfAbiValidator.validate(lib)
            if (!abi.isValid || !abi.isAarch64) {
                return abi.copy(detailMessage = "$name: ${abi.detailMessage}")
            }
            if (abi.abiType != ElfAbiType.GLIBC_AARCH64) {
                return abi.copy(
                    isValid = false,
                    detailMessage = "$name is ${abi.abiType.name}, guest loader needs glibc"
                )
            }
        }
        return ElfValidationResult(
            isValid = true,
            abiType = ElfAbiType.GLIBC_AARCH64,
            isAarch64 = true,
            is64Bit = true,
            detectedLibraries = REQUIRED_LIBRARIES,
            detailMessage = "verified ANGLE bundle ${manifest.version}"
        )
    }

    /** Installs a verified bundle into the store and points `active` at it. */
    fun installBundle(sourceDir: File, version: String): ElfValidationResult {
        val target = dirs.getAngleBundleDir(version)
        if (!sourceDir.isDirectory) return invalid("source $sourceDir is not a directory")
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) return invalid("cannot create $target")
        sourceDir.copyRecursively(target, overwrite = true)

        if (readManifest(target) == null) {
            writeManifest(target, BundleManifest(version = version, sha256 = aggregateSha(target)))
        }
        val verdict = verifyBundle(target)
        if (verdict.isValid) {
            dirs.getAngleActivePointer().writeText(version)
        }
        return verdict
    }

    private fun readManifest(bundleDir: File): BundleManifest? =
        readBundleManifest(File(bundleDir, MANIFEST_NAME))

    private fun writeManifest(bundleDir: File, manifest: BundleManifest) {
        File(bundleDir, MANIFEST_NAME).writeText(manifest.toString())
    }

    /** Aggregate digest over both libraries, in the manifest's stable order. */
    private fun aggregateSha(bundleDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in REQUIRED_LIBRARIES) {
            digest.update(sha256(File(bundleDir, name)).toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun invalid(message: String) = ElfValidationResult(
        isValid = false,
        abiType = ElfAbiType.INVALID_ELF,
        isAarch64 = false,
        is64Bit = false,
        detectedLibraries = emptyList(),
        detailMessage = message
    )

    fun readBundleManifest(file: File): BundleManifest? {
        if (!file.isFile) return null
        return try {
            val obj = org.json.JSONObject(file.readText())
            BundleManifest(
                version = obj.optString("version", ""),
                sha256 = obj.optString("sha256", ""),
                abiType = ElfAbiType.valueOf(
                    obj.optString("abiType", ElfAbiType.GLIBC_AARCH64.name)
                ),
                source = obj.optString("source", "")
            )
        } catch (_: Throwable) {
            null
        }
    }
}
