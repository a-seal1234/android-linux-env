package com.alw.workstation.core.proot.shizuku

import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream

/**
 * Interface abstraction for executing privileged commands via Shizuku shell.
 * Enables deterministic mocking and offline unit testing without requiring
 * live Android Binder IPC.
 */
interface ShizukuShellRunner {
    fun execute(command: List<String>): ShizukuCommandResult
}

/**
 * Adapter wrapping [IRemoteProcess] into standard [java.lang.Process].
 */
class AidlRemoteProcessAdapter(
    private val remote: IRemoteProcess
) : Process() {
    override fun getOutputStream(): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)

    override fun getInputStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)

    override fun getErrorStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun waitFor(): Int = remote.waitFor()
    override fun exitValue(): Int = remote.exitValue()
    override fun destroy() = remote.destroy()
}

/**
 * Default production implementation executing commands via Shizuku UID 2000 shell.
 */
class DefaultShizukuShellRunner : ShizukuShellRunner {

    override fun execute(command: List<String>): ShizukuCommandResult {
        return try {
            val process: Process = try {
                // Try Shizuku.newProcess via reflection first
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
                method.invoke(null, command.toTypedArray(), null, null) as Process
            } catch (reflectionEx: Throwable) {
                // Direct AIDL fallback
                val binder = Shizuku.getBinder()
                    ?: return ShizukuCommandResult(-1, "", "Shizuku binder is unavailable")
                val service = IShizukuService.Stub.asInterface(binder)
                val remoteProcess = service.newProcess(command.toTypedArray(), null, null)
                AidlRemoteProcessAdapter(remoteProcess)
            }

            val stdout = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()

            ShizukuCommandResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
        } catch (e: Exception) {
            ShizukuCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Privileged shell execution failed"
            )
        }
    }
}
