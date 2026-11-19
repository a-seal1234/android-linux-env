package com.alw.workstation.core.proot.shim

import java.io.File

/**
 * Deploys the Quickshell `alw-fps` pill (TASK-026) into the guest homes.
 *
 * `ConfigSynthesizer` starts the pill with `qs -c alw-fps`, but nothing used to
 * materialize `~/.config/quickshell/alw-fps/shell.qml`, so the autostart was a
 * no-op after a fresh rootfs provisioning. Like the shim layer, deployment is
 * non-destructive: an existing file is never overwritten, which keeps user
 * customizations intact (Non-Destructive Invariant).
 */
object AlwFpsShellGenerator {

    const val SHELL_QML_RESOURCE = "/alw-fps/shell.qml"
    const val QUICKSHELL_DIR = ".config/quickshell/alw-fps"
    const val SHELL_FILE_NAME = "shell.qml"

    /** Read-only lookup for tests and callers that need the embedded QML. */
    fun shellQml(): String =
        AlwFpsShellGenerator::class.java.getResourceAsStream(SHELL_QML_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing classpath resource $SHELL_QML_RESOURCE")

    /**
     * Writes shell.qml into `<rootfsDir>/home/<user>/.config/quickshell/alw-fps/` for
     * every home and into `<rootfsDir>/root/.config/quickshell/alw-fps/`.
     *
     * @return the files that were created (already-present files are skipped).
     */
    fun deploy(
        rootfsDir: File,
        userHomes: List<String> = listOf("home/alw", "root")
    ): List<File> {
        if (!rootfsDir.isDirectory) return emptyList()

        val qml = shellQml()
        val created = mutableListOf<File>()

        for (home in userHomes) {
            val targetDir = File(rootfsDir, "$home/$QUICKSHELL_DIR")
            val target = File(targetDir, SHELL_FILE_NAME)
            if (target.exists()) continue
            targetDir.mkdirs()
            target.writeText(qml)
            target.setReadable(true, false)
            created += target
        }
        return created
    }
}
