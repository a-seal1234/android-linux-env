package com.alw.workstation.core.session

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Reaps orphaned guest session processes left behind when the host app was killed
 * without a clean stop (installer SIGKILL, crash). Without this, a previous
 * incarnation's `Hyprland` / `waybar` / `hyprpaper` reconnect to the new session's
 * Wayland socket and stack on top of the live compositor — the "ghost frame"
 * artifact and duplicated `wl_output` scale errors (TASK-035).
 *
 * Only same-UID processes whose parent is not this app instance are touched, so
 * the live session tree and the Terminal session (both spawned by this app) are
 * never signalled. `kitty` and other plain guest apps are deliberately not in the
 * kill set — they die on their own when their compositor goes away.
 */
class GuestProcessReaper(
    private val myPid: Int = Process.myPid(),
    private val myUid: Int = Process.myUid(),
    private val processLister: () -> List<GuestProcess> = ::readGuestProcesses,
    private val parentAlive: (Int) -> Boolean = ::isPidAlive,
    private val killer: (Int) -> Unit = { pid -> Os.kill(pid, OsConstants.SIGKILL) }
) {

    data class GuestProcess(val pid: Int, val ppid: Int, val uid: Int, val name: String)

    /**
     * Signals every orphaned guest-session process and returns the pids that were
     * signalled. Never throws — an unroutable pid is skipped.
     */
    fun reapOrphanedGuests(): List<Int> {
        val reaped = mutableListOf<Int>()
        for (proc in processLister()) {
            if (!isReapable(proc)) continue
            try {
                killer(proc.pid)
                reaped.add(proc.pid)
            } catch (_: Throwable) {
            }
        }
        return reaped
    }

    private fun isReapable(proc: GuestProcess): Boolean {
        if (proc.pid == myPid) return false
        if (proc.uid != myUid) return false
        if (proc.name !in GUEST_PROCESS_NAMES) return false

        // Never touch a tree that is a direct child of this app instance: it may be
        // the session that is about to be started/kept. Killing a previous
        // session's PRoot launcher is the explicit Close All Sessions job
        // (SessionCleanupCoordinator), not the pre-launch orphan sweep.
        if (proc.ppid == myPid) return false

        // Children of init are orphans by definition; anything else must have a
        // dead parent to count as leftover.
        if (proc.ppid != 1 && parentAlive(proc.ppid)) return false
        return true
    }

    companion object {
        /** Guest-side processes that only exist in desktop sessions. */
        val GUEST_PROCESS_NAMES = setOf(
            "lib_proot.so",
            "Hyprland",
            "start-hyprland",
            "hyprpaper",
            "waybar",
            "quickshell",
            "dbus-run-session"
        )

        fun isPidAlive(pid: Int): Boolean = File("/proc/$pid").exists()

        fun readGuestProcesses(): List<GuestProcess> {
            val result = mutableListOf<GuestProcess>()
            val pidDirs = File("/proc").listFiles { file ->
                file.isDirectory && file.name.all { it.isDigit() }
            } ?: return result

            for (dir in pidDirs) {
                try {
                    val pid = dir.name.toInt()
                    val cmdline = File(dir, "cmdline").readBytes()
                        .toString(Charsets.UTF_8)
                        .substringBefore('\u0000')
                        .trim()
                    val name = cmdline.ifBlank {
                        readStat(dir)?.let { stat -> stat.substringAfter('(').substringBeforeLast(')') }
                    } ?: continue
                    if (name.isBlank()) continue

                    val status = File(dir, "status").readText()
                    val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: continue
                    val uid = uidLine.split(Regex("\\s+"))[1].toInt()

                    val stat = readStat(dir) ?: continue
                    val afterComm = stat.substringAfterLast(") ").trim().split(" ")
                    val ppid = afterComm.getOrNull(1)?.toInt() ?: continue

                    result.add(GuestProcess(pid, ppid, uid, name))
                } catch (_: Throwable) {
                    // A process may exit between listing and reading; skip it.
                }
            }
            return result
        }

        private fun readStat(dir: File): String? = try {
            File(dir, "stat").readText()
        } catch (_: Throwable) {
            null
        }
    }
}
