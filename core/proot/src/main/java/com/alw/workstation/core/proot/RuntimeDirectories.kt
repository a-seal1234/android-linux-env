package com.alw.workstation.core.proot

import java.io.File

/**
 * Encapsulates internal file system paths for PRoot runtime environments,
 * native loaders, rootfs storage, drivers, and ephemeral session sockets.
 */
data class RuntimeDirectories(
    val filesDir: File,
    val nativeLibDir: String,
    val rootfsBaseDir: File = File(filesDir, "rootfs"),
    val driversBaseDir: File = File(filesDir, "drivers"),
    val turnipDriversBaseDir: File = File(driversBaseDir, "turnip"),
    val angleBundleBaseDir: File = File(driversBaseDir, "angle"),
    val overlaysBaseDir: File = File(filesDir, "overlays"),
    val fexOverlaysBaseDir: File = File(overlaysBaseDir, "fex"),
    val box64OverlaysBaseDir: File = File(overlaysBaseDir, "box64"),
    val runtimeBaseDir: File = File(filesDir, "runtime"),
    val prootTmpDir: File = File(filesDir, "tmp"),
    val fakefsBaseDir: File = File(filesDir, "fakefs")
) {
    val prootLibPath: String get() = File(nativeLibDir, "lib_proot.so").absolutePath
    val loaderPath: String get() = File(nativeLibDir, "lib_loader.so").absolutePath
    val loader32Path: String get() = File(nativeLibDir, "lib_loader32.so").absolutePath
    val tallocLibPath: String get() = File(nativeLibDir, "libtalloc.so").absolutePath
    val shmemLibPath: String get() = File(nativeLibDir, "libandroid-shmem.so").absolutePath

    fun getRootfsDir(rootfsId: String): File = File(rootfsBaseDir, rootfsId)
    fun getDriverDir(driverId: String): File = File(driversBaseDir, driverId)
    fun getTurnipDriverDir(version: String): File = File(turnipDriversBaseDir, version)
    fun getActiveTurnipDriverDir(): File = File(turnipDriversBaseDir, "active")
    fun getAngleBundleDir(version: String): File = File(angleBundleBaseDir, version)
    fun getActiveAngleBundleDir(): File {
        val pointer = getAngleActivePointer()
        val version = if (pointer.isFile) pointer.readText().trim() else ""
        if (version.isNotEmpty()) {
            val dir = getAngleBundleDir(version)
            if (dir.isDirectory) return dir
        }
        return File(angleBundleBaseDir, "active")
    }
    fun getAngleActivePointer(): File = File(angleBundleBaseDir, "active-version")
    fun getOverlayDir(engine: String, version: String): File = File(File(overlaysBaseDir, engine.lowercase()), version)
    fun getActiveOverlayDir(engine: String): File = File(File(overlaysBaseDir, engine.lowercase()), "active")
    fun getRuntimeSocketDir(sessionId: String): File {
        val runtimeAlwDir = File(File(fakefsBaseDir, "tmp"), "rt")
        if (!runtimeAlwDir.exists()) {
            runtimeAlwDir.mkdirs()
        }
        return runtimeAlwDir
    }

    fun ensureDirectoriesExist() {
        if (!filesDir.exists()) filesDir.mkdirs()
        if (!rootfsBaseDir.exists()) rootfsBaseDir.mkdirs()
        if (!driversBaseDir.exists()) driversBaseDir.mkdirs()
        if (!turnipDriversBaseDir.exists()) turnipDriversBaseDir.mkdirs()
        if (!angleBundleBaseDir.exists()) angleBundleBaseDir.mkdirs()
        if (!overlaysBaseDir.exists()) overlaysBaseDir.mkdirs()
        if (!fexOverlaysBaseDir.exists()) fexOverlaysBaseDir.mkdirs()
        if (!box64OverlaysBaseDir.exists()) box64OverlaysBaseDir.mkdirs()
        if (!runtimeBaseDir.exists()) runtimeBaseDir.mkdirs()
        if (!prootTmpDir.exists()) prootTmpDir.mkdirs()
        if (!fakefsBaseDir.exists()) fakefsBaseDir.mkdirs()
    }
}
