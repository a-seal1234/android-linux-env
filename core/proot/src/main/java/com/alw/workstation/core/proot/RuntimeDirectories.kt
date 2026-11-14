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
    val runtimeBaseDir: File = File(filesDir, "runtime"),
    val prootTmpDir: File = File(filesDir, "tmp")
) {
    val prootLibPath: String get() = File(nativeLibDir, "lib_proot.so").absolutePath
    val loaderPath: String get() = File(nativeLibDir, "lib_loader.so").absolutePath
    val loader32Path: String get() = File(nativeLibDir, "lib_loader32.so").absolutePath
    val tallocLibPath: String get() = File(nativeLibDir, "libtalloc.so").absolutePath
    val shmemLibPath: String get() = File(nativeLibDir, "libandroid-shmem.so").absolutePath

    fun getRootfsDir(rootfsId: String): File = File(rootfsBaseDir, rootfsId)
    fun getDriverDir(driverId: String): File = File(driversBaseDir, driverId)
    fun getRuntimeSocketDir(sessionId: String): File = File(runtimeBaseDir, sessionId)

    fun ensureDirectoriesExist() {
        if (!filesDir.exists()) filesDir.mkdirs()
        if (!rootfsBaseDir.exists()) rootfsBaseDir.mkdirs()
        if (!driversBaseDir.exists()) driversBaseDir.mkdirs()
        if (!runtimeBaseDir.exists()) runtimeBaseDir.mkdirs()
        if (!prootTmpDir.exists()) prootTmpDir.mkdirs()
    }
}
