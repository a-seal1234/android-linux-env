package com.alw.workstation.core.proot

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

/**
 * Manages multi-rootfs directory lifecycle, snapshot cloning, integrity validation,
 * and session desktop discovery within the application's internal files storage.
 */
class RootfsManager(private val dirs: RuntimeDirectories) {

    companion object {
        private const val TAG = "ALW::RootfsManager"
        private val VALID_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$")
        const val PROVISIONED_MARKER = ".alw-provisioned"
    }

    data class DesktopSession(
        val id: String,
        val name: String,
        val exec: String,
        val comment: String? = null,
        val terminal: Boolean = false
    )

    data class RootfsInfo(
        val id: String,
        val name: String,
        val path: File,
        val isValid: Boolean,
        val isProvisioned: Boolean,
        val sizeBytes: Long,
        val desktopSessions: List<DesktopSession>
    )

    private fun validateRootfsId(id: String) {
        require(id.isNotBlank()) { "Rootfs ID cannot be blank" }
        require(VALID_ID_PATTERN.matcher(id).matches()) {
            "Rootfs ID contains invalid characters: $id (must match [a-zA-Z0-9_.-]+)"
        }
        require(!id.contains("..")) { "Rootfs ID cannot contain path traversal sequences: $id" }
    }

    /**
     * Lists all rootfs directories installed under files/rootfs/<id>.
     */
    fun listRootfs(): List<RootfsInfo> {
        dirs.ensureDirectoriesExist()
        val base = dirs.rootfsBaseDir
        if (!base.exists() || !base.isDirectory) return emptyList()

        val subdirs = base.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return subdirs.map { dir ->
            buildRootfsInfo(dir.name, dir)
        }.sortedBy { it.id }
    }

    /**
     * Returns metadata for a specific rootfs ID, or null if not found.
     */
    fun getRootfsInfo(rootfsId: String): RootfsInfo? {
        validateRootfsId(rootfsId)
        val dir = dirs.getRootfsDir(rootfsId)
        if (!dir.exists() || !dir.isDirectory) return null
        return buildRootfsInfo(rootfsId, dir)
    }

    private fun buildRootfsInfo(id: String, dir: File): RootfsInfo {
        val isValid = checkRootfsValidity(dir)
        val isProvisioned = File(dir, PROVISIONED_MARKER).exists()
        val sessions = discoverWaylandSessions(id)
        val size = calculateDirectorySize(dir)

        return RootfsInfo(
            id = id,
            name = formatDisplayName(id),
            path = dir,
            isValid = isValid,
            isProvisioned = isProvisioned,
            sizeBytes = size,
            desktopSessions = sessions
        )
    }

    /**
     * Checks if a rootfs filesystem contains a usable shell interpreter.
     */
    fun isRootfsValid(rootfsId: String): Boolean = checkRootfsValidity(dirs.getRootfsDir(rootfsId))

    /**
     * Checks if a rootfs filesystem contains a usable shell interpreter.
     */
    fun checkRootfsValidity(rootfsDir: File): Boolean {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false
        val shellCandidates = listOf(
            File(rootfsDir, "bin/sh"),
            File(rootfsDir, "usr/bin/sh"),
            File(rootfsDir, "bin/bash"),
            File(rootfsDir, "usr/bin/bash"),
            File(rootfsDir, "bin/busybox")
        )
        return shellCandidates.any { it.exists() }
    }

    /**
     * Creates an empty rootfs directory with the specified ID.
     */
    fun createRootfs(rootfsId: String): File {
        validateRootfsId(rootfsId)
        val dir = dirs.getRootfsDir(rootfsId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Deletes a rootfs directory recursively.
     */
    fun deleteRootfs(rootfsId: String): Boolean {
        validateRootfsId(rootfsId)
        val dir = dirs.getRootfsDir(rootfsId)
        if (!dir.exists()) return false

        // Safety check: ensure dir is strictly a direct child of rootfsBaseDir
        val canonicalBase = dirs.rootfsBaseDir.canonicalFile
        val canonicalTarget = dir.canonicalFile
        if (canonicalTarget.parentFile != canonicalBase) {
            throw SecurityException("Attempted to delete outside rootfsBaseDir: ${canonicalTarget.absolutePath}")
        }

        return dir.deleteRecursively()
    }

    /**
     * Clones an existing rootfs directory into a new rootfs target, preserving symlinks,
     * permissions, and directory structure.
     */
    fun cloneRootfs(
        sourceId: String,
        targetId: String,
        onProgress: ((copied: Int, total: Int) -> Unit)? = null
    ): Boolean {
        validateRootfsId(sourceId)
        validateRootfsId(targetId)

        val sourceDir = dirs.getRootfsDir(sourceId)
        val targetDir = dirs.getRootfsDir(targetId)

        if (!sourceDir.exists() || !sourceDir.isDirectory) {
            Log.e(TAG, "Source rootfs directory does not exist: ${sourceDir.absolutePath}")
            return false
        }
        if (targetDir.exists()) {
            Log.e(TAG, "Target rootfs directory already exists: ${targetDir.absolutePath}")
            return false
        }

        targetDir.mkdirs()

        return try {
            val sourceFiles = sourceDir.walkTopDown().toList()
            val total = sourceFiles.size
            var copied = 0

            for (file in sourceFiles) {
                val relativePath = file.relativeTo(sourceDir).path
                if (relativePath.isEmpty()) continue

                val destFile = File(targetDir, relativePath)
                val sourcePath = file.toPath()
                val destPath = destFile.toPath()

                if (Files.isSymbolicLink(sourcePath)) {
                    destFile.parentFile?.mkdirs()
                    val targetLink = Files.readSymbolicLink(sourcePath)
                    try {
                        Files.createSymbolicLink(destPath, targetLink)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed creating symlink $destPath -> $targetLink: ${e.message}")
                    }
                } else if (file.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING)
                    if (file.canExecute()) {
                        destFile.setExecutable(true, false)
                    }
                    if (file.canRead()) {
                        destFile.setReadable(true, false)
                    }
                }

                copied++
                if (copied % 250 == 0 || copied == total) {
                    onProgress?.invoke(copied, total)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cloning failed from $sourceId to $targetId: ${e.message}", e)
            targetDir.deleteRecursively()
            false
        }
    }

    /**
     * Inspects /usr/share/wayland-sessions within the guest rootfs to discover installed desktops.
     */
    fun discoverWaylandSessions(rootfsId: String): List<DesktopSession> {
        validateRootfsId(rootfsId)
        val rootfsDir = dirs.getRootfsDir(rootfsId)
        val sessionsDir = File(rootfsDir, "usr/share/wayland-sessions")
        if (!sessionsDir.exists() || !sessionsDir.isDirectory) return emptyList()

        val sessions = mutableListOf<DesktopSession>()
        sessionsDir.listFiles()?.filter { it.extension == "desktop" }?.sortedBy { it.name }?.forEach { file ->
            var name = file.nameWithoutExtension
            var exec = ""
            var comment: String? = null
            var terminal = false

            try {
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("Name=") && name == file.nameWithoutExtension) {
                        name = trimmed.removePrefix("Name=").trim()
                    } else if (trimmed.startsWith("Exec=")) {
                        exec = trimmed.removePrefix("Exec=").trim()
                    } else if (trimmed.startsWith("Comment=")) {
                        comment = trimmed.removePrefix("Comment=").trim()
                    } else if (trimmed.startsWith("Terminal=")) {
                        terminal = trimmed.removePrefix("Terminal=").trim().equals("true", ignoreCase = true)
                    }
                }
                if (exec.isNotBlank()) {
                    sessions.add(
                        DesktopSession(
                            id = file.nameWithoutExtension,
                            name = name,
                            exec = exec,
                            comment = comment,
                            terminal = terminal
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing desktop file ${file.absolutePath}: ${e.message}")
            }
        }
        return sessions
    }

    /**
     * Marks a rootfs as successfully provisioned by creating or removing the marker file.
     */
    fun markProvisioned(rootfsId: String, provisioned: Boolean = true) {
        validateRootfsId(rootfsId)
        val dir = dirs.getRootfsDir(rootfsId)
        val marker = File(dir, PROVISIONED_MARKER)
        if (provisioned) {
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.writeText("alw-provisioned-ok\n")
            }
        } else {
            if (marker.exists()) {
                marker.delete()
            }
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatDisplayName(id: String): String {
        return when (id) {
            "archlinux-default" -> "Arch Linux ARM (Default)"
            "arch-xiaomi-pad-8" -> "Arch Linux (Xiaomi Pad 8)"
            "safe-mode" -> "Safe Mode (LabWC + llvmpipe)"
            else -> id.split("-", "_").joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
    }
}
