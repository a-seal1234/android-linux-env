package com.alw.workstation.core.session

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Hard cleanup for crashed or abandoned sessions (TASK-036 Phase 1).
 *
 * A guest crash (Firefox `SIGSYS`/`SIGBUS`, GPU context loss) can leave the compositor
 * tree, the shell (Quickshell/Waybar) and stale runtime artifacts behind. Those artifacts
 * — `wayland-0` sockets, `wayland-*.lock`, `.hyprland.lock`, `alw-host.sock` — make the
 * next launch fail or, worse, silently attach to a half-dead server.
 *
 * Two-stage termination mirrors [ProotProcessManager.ManagedProcess.terminate]: `SIGTERM`,
 * a grace window, then `SIGKILL`. The process selection is injected so the whole flow stays
 * host-JVM testable.
 */
class SessionCleanupCoordinator(
    private val signalSender: (Int, Int) -> Unit = { pid, sig -> Os.kill(pid, sig) },
    private val processLister: () -> List<GuestProcessReaper.GuestProcess> =
        GuestProcessReaper::readGuestProcesses,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val gracePeriodMs: Long = 1200L,
    private val myPid: Int = Process.myPid(),
    private val myUid: Int = Process.myUid(),
    private val isPidAlive: (Int) -> Boolean = GuestProcessReaper::isPidAlive
) {

    /** Runtime artifacts that must never survive a hard cleanup. */
    companion object {
        const val HYPRLAND_LOCK = ".hyprland.lock"
        const val HOST_SOCKET = "alw-host.sock"

        /** Guest desktop processes eligible for the two-stage kill. */
        val SESSION_PROCESS_NAMES = setOf(
            "lib_proot.so",
            "Hyprland",
            "start-hyprland",
            "hyprpaper",
            "waybar",
            "quickshell",
            "dbus-run-session"
        )

        /**
         * True for artifacts a crashed session can leave behind: Wayland sockets/locks,
         * the Hyprland instance lock and the ALW host bridge socket.
         */
        fun isStaleSessionArtifact(file: File): Boolean {
            val name = file.name
            return name == HYPRLAND_LOCK ||
                name == HOST_SOCKET ||
                name.startsWith("wayland-")
        }
    }

    data class CleanupReport(
        val terminatedPids: List<Int>,
        val killedPids: List<Int>,
        val purgedArtifacts: List<String>
    ) {
        val hadWork: Boolean get() = terminatedPids.isNotEmpty() ||
            killedPids.isNotEmpty() || purgedArtifacts.isNotEmpty()
    }

    /**
     * Terminates the guest session tree (SIGTERM → grace → SIGKILL) and purges stale
     * runtime artifacts below [runtimeDirs]. Never throws; unreachable pids are skipped.
     */
    fun closeAllSessions(runtimeDirs: List<File>): CleanupReport {
        val targets = processLister().filter { it.isSessionProcess() }
        val terminated = mutableListOf<Int>()
        val killed = mutableListOf<Int>()

        for (process in targets) {
            try {
                signalSender(process.pid, OsConstants.SIGTERM)
                terminated.add(process.pid)
            } catch (_: Throwable) {
            }
        }

        if (terminated.isNotEmpty()) {
            try {
                sleeper(gracePeriodMs)
            } catch (_: InterruptedException) {
            }
        }

        for (process in targets) {
            if (!isPidAlive(process.pid)) continue
            try {
                signalSender(process.pid, OsConstants.SIGKILL)
                killed.add(process.pid)
            } catch (_: Throwable) {
            }
        }

        return CleanupReport(
            terminatedPids = terminated,
            killedPids = killed,
            purgedArtifacts = purgeArtifacts(runtimeDirs)
        )
    }

    /** Deletes stale sockets/locks; returns the deleted paths. */
    fun purgeArtifacts(runtimeDirs: List<File>): List<String> {
        val purged = mutableListOf<String>()
        for (dir in runtimeDirs) {
            if (!dir.isDirectory) continue
            val children = dir.listFiles() ?: continue
            for (file in children) {
                if (!isStaleSessionArtifact(file)) continue
                try {
                    if (file.delete()) purged.add(file.absolutePath)
                } catch (_: Throwable) {
                }
            }
        }
        return purged
    }

    private fun GuestProcessReaper.GuestProcess.isSessionProcess(): Boolean {
        if (pid == myPid || uid != myUid) return false
        if (name !in SESSION_PROCESS_NAMES) return false
        return true
    }
}
