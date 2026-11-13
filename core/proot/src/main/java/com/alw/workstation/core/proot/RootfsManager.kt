package com.alw.workstation.core.proot

import java.io.File

/**
 * Manages multi-rootfs directory isolation and session desktop discovery.
 */
class RootfsManager(private val dirs: RuntimeDirectories) {

    data class DesktopSession(
        val id: String,
        val name: String,
        val exec: String
    )

    fun listRootfs(): List<String> {
        val base = dirs.rootfsBaseDir
        if (!base.exists() || !base.isDirectory) return emptyList()
        return base.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    /**
     * Inspects /usr/share/wayland-sessions within the guest rootfs to discover installed desktops.
     */
    fun discoverWaylandSessions(rootfsId: String): List<DesktopSession> {
        val rootfsDir = dirs.getRootfsDir(rootfsId)
        val sessionsDir = File(rootfsDir, "usr/share/wayland-sessions")
        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return emptyList()

        val sessions = mutableListOf<DesktopSession>()
        sessionsDir.listFiles()?.filter { it.extension == "desktop" }?.forEach { file ->
            var name = file.nameWithoutExtension
            var exec = ""
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("Name=")) {
                    name = trimmed.removePrefix("Name=").trim()
                } else if (trimmed.startsWith("Exec=")) {
                    exec = trimmed.removePrefix("Exec=").trim()
                }
            }
            if (exec.isNotBlank()) {
                sessions.add(DesktopSession(id = file.nameWithoutExtension, name = name, exec = exec))
            }
        }
        return sessions
    }
}
