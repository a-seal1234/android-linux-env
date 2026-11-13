package com.alw.workstation.core.proot

import java.io.File

data class RuntimeDirectories(
    val filesDir: File,
    val nativeLibDir: String,
    val rootfsBaseDir: File = File(filesDir, "rootfs"),
    val driversBaseDir: File = File(filesDir, "drivers"),
    val runtimeBaseDir: File = File(filesDir, "runtime"),
    val prootTmpDir: File = File(filesDir, "tmp")
) {
    fun getRootfsDir(rootfsId: String): File = File(rootfsBaseDir, rootfsId)
    fun getDriverDir(driverId: String): File = File(driversBaseDir, driverId)
    fun getRuntimeSocketDir(sessionId: String): File = File(runtimeBaseDir, sessionId)
}
