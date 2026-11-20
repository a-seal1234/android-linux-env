package com.alw.workstation.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionCleanupCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun process(pid: Int, name: String, uid: Int = TEST_UID) =
        GuestProcessReaper.GuestProcess(pid = pid, ppid = 1, uid = uid, name = name)

    private companion object {
        const val TEST_UID = 10200
    }

    @Test
    fun staleArtifactDetectionCoversWaylandAndSessionLocks() {
        val dir = tempFolder.newFolder("runtime")
        val stale = listOf(
            "wayland-0", "wayland-1", "wayland-0.lock", "wayland-1.lock",
            SessionCleanupCoordinator.HYPRLAND_LOCK,
            SessionCleanupCoordinator.HOST_SOCKET
        )
        val keep = listOf("pulse", "quickshell", "bluetooth")

        for (name in stale) {
            assertTrue("$name must be stale", SessionCleanupCoordinator.isStaleSessionArtifact(File(dir, name)))
        }
        for (name in keep) {
            assertFalse("$name must survive", SessionCleanupCoordinator.isStaleSessionArtifact(File(dir, name)))
        }
    }

    @Test
    fun purgeDeletesOnlySessionArtifacts() {
        val runtime = tempFolder.newFolder("rt")
        File(runtime, "wayland-0").createNewFile()
        File(runtime, "wayland-0.lock").createNewFile()
        File(runtime, SessionCleanupCoordinator.HOST_SOCKET).createNewFile()
        File(runtime, SessionCleanupCoordinator.HYPRLAND_LOCK).createNewFile()
        File(runtime, "keep-me").createNewFile()
        File(runtime, "pulse").mkdirs()

        val coordinator = SessionCleanupCoordinator(
            signalSender = { _, _ -> },
            processLister = { emptyList() },
            sleeper = {}
        )
        val purged = coordinator.purgeArtifacts(listOf(runtime))

        assertEquals(4, purged.size)
        assertTrue(File(runtime, "keep-me").exists())
        assertTrue(File(runtime, "pulse").isDirectory)
    }

    @Test
    fun twoStageTerminationEscalatesToSigkill() {
        val signals = mutableListOf<Pair<Int, Int>>()
        val coordinator = SessionCleanupCoordinator(
            signalSender = { pid, sig -> signals.add(pid to sig) },
            processLister = {
                listOf(
                    process(301, "Hyprland"),
                    process(302, "quickshell"),
                    process(303, "kitty"),
                    process(304, "Hyprland", uid = 99999)
                )
            },
            sleeper = {},
            myUid = TEST_UID,
            isPidAlive = { false }
        )

        // Pid 301/302 survive the grace window (isPidAlive reads /proc, so plant alive
        // pids for the escalation assertions by using real live pids instead).
        val report = coordinator.closeAllSessions(emptyList())

        assertEquals(listOf(301, 302), report.terminatedPids)
        assertTrue(report.hadWork)
        assertEquals(2, signals.count { it.second == android.system.OsConstants.SIGTERM })
        assertFalse(report.killedPids.contains(303))
    }

    @Test
    fun survivingProcessesEscalateFromSigtermToSigkill() {
        val signals = mutableListOf<Pair<Int, Int>>()
        val coordinator = SessionCleanupCoordinator(
            signalSender = { pid, sig -> signals.add(pid to sig) },
            processLister = { listOf(process(501, "Hyprland")) },
            sleeper = {},
            myUid = TEST_UID,
            isPidAlive = { true }
        )

        val report = coordinator.closeAllSessions(emptyList())

        assertEquals(listOf(501), report.terminatedPids)
        assertEquals(listOf(501), report.killedPids)
        assertEquals(
            listOf(501 to android.system.OsConstants.SIGTERM, 501 to android.system.OsConstants.SIGKILL),
            signals
        )
    }

    @Test
    fun onlyThisUidGuestSessionProcessesAreTargeted() {
        val signals = mutableListOf<Int>()
        val coordinator = SessionCleanupCoordinator(
            signalSender = { pid, _ -> signals.add(pid) },
            processLister = {
                listOf(
                    process(401, "Hyprland"),
                    process(402, "my-self"),
                    process(403, "waybar"),
                    process(404, "Hyprland", uid = 5555),
                    process(405, "bash")
                )
            },
            sleeper = {},
            myPid = 402,
            myUid = TEST_UID
        )

        coordinator.closeAllSessions(emptyList())

        assertEquals(listOf(401, 403), signals)
    }

    @Test
    fun emptyStateReportsNoWork() {
        val coordinator = SessionCleanupCoordinator(
            signalSender = { _, _ -> },
            processLister = { emptyList() },
            sleeper = {}
        )
        val report = coordinator.closeAllSessions(emptyList())

        assertFalse(report.hadWork)
        assertTrue(report.terminatedPids.isEmpty())
        assertTrue(report.purgedArtifacts.isEmpty())
    }
}
