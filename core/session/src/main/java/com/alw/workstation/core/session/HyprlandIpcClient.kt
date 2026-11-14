package com.alw.workstation.core.session

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Client for communicating with the running Hyprland compositor via its UNIX domain socket.
 * Enables live resolution, scale, and keyword configuration updates without restarting the session.
 */
class HyprlandIpcClient(private val runtimeDir: File) {

    /**
     * Dispatches an IPC command to Hyprland and returns the compositor's response.
     * Command format: "[[FLAGS]]/<command>" (e.g. "keyword monitor WL-1,3200x2136@144,0x0,1.75")
     */
    fun sendCommand(command: String): Result<String> {
        val socketFile = findHyprlandSocket() ?: return Result.failure(
            IllegalStateException("Hyprland socket not found in $runtimeDir")
        )

        return try {
            val socket = LocalSocket()
            val address = LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
            socket.connect(address)

            val outStream: OutputStream = socket.outputStream
            outStream.write(command.toByteArray(Charsets.UTF_8))
            outStream.flush()

            val inStream: InputStream = socket.inputStream
            val response = inStream.bufferedReader(Charsets.UTF_8).readText()

            socket.close()
            Result.success(response.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setKeyword(key: String, value: String): Result<String> {
        return sendCommand("keyword $key $value")
    }

    private fun findHyprlandSocket(): File? {
        val hyprDir = File(runtimeDir, "hypr")
        if (!hyprDir.exists() || !hyprDir.isDirectory) return null

        // Look for .socket.sock or .socket2.sock inside session subdirectories
        return hyprDir.walkTopDown().firstOrNull { it.name.endsWith(".sock") && !it.name.contains("2") }
    }
}
