package com.alw.workstation.core.session

import com.alw.workstation.core.session.GuestProcessReaper.GuestProcess
import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProcessReaperTest {

    private fun proc(pid: Int, ppid: Int, uid: Int, name: String) =
        GuestProcess(pid, ppid, uid, name)

    private fun reaperFor(
        processes: List<GuestProcess>,
        alivePids: Set<Int> = emptySet(),
        killed: MutableList<Int> = mutableListOf()
    ): GuestProcessReaper = GuestProcessReaper(
        myPid = 100,
        myUid = 10200,
        processLister = { processes },
        parentAlive = { it in alivePids },
        killer = { killed.add(it) }
    )

    @Test
    fun killsOrphansReparentedToInit() {
        val killed = mutableListOf<Int>()
        val reaper = reaperFor(
            listOf(
                proc(200, 1, 10200, "Hyprland"),
                proc(201, 1, 10200, "lib_proot.so"),
                proc(202, 1, 10200, "hyprpaper"),
                proc(203, 1, 10200, "waybar")
            ),
            killed = killed
        )

        assertEquals(listOf(200, 201, 202, 203), reaper.reapOrphanedGuests())
        assertEquals(listOf(200, 201, 202, 203), killed)
    }

    @Test
    fun killsOrphansWhoseParentIsDead() {
        val killed = mutableListOf<Int>()
        val reaper = reaperFor(
            listOf(proc(210, 9999, 10200, "Hyprland")),
            alivePids = emptySet(),
            killed = killed
        )

        assertEquals(listOf(210), reaper.reapOrphanedGuests())
    }

    @Test
    fun sparesLiveSessionTreeSpawnedByThisApp() {
        val killed = mutableListOf<Int>()
        val reaper = reaperFor(
            listOf(
                proc(10166, 100, 10200, "lib_proot.so"),
                proc(10564, 10166, 10200, "Hyprland"),
                proc(10761, 1, 10200, "kitty")
            ),
            alivePids = setOf(10166),
            killed = killed
        )

        assertTrue(reaper.reapOrphanedGuests().isEmpty())
        assertTrue(killed.isEmpty())
    }

    @Test
    fun sparesOtherUidsSelfAndNonGuestProcesses() {
        val killed = mutableListOf<Int>()
        val reaper = reaperFor(
            listOf(
                proc(300, 1, 10500, "Hyprland"),
                proc(100, 1, 10200, "com.alw.workstation"),
                proc(310, 1, 10200, "kitty"),
                proc(320, 1, 10200, "bash")
            ),
            killed = killed
        )

        assertTrue(reaper.reapOrphanedGuests().isEmpty())
        assertTrue(killed.isEmpty())
    }

    @Test
    fun sparesProcessesWhenParentStillAlive() {
        val killed = mutableListOf<Int>()
        val reaper = reaperFor(
            listOf(proc(400, 500, 10200, "Hyprland")),
            alivePids = setOf(500),
            killed = killed
        )

        assertTrue(reaper.reapOrphanedGuests().isEmpty())
        assertTrue(killed.isEmpty())
    }

    @Test
    fun guestProcessNameSetCoversSessionTree() {
        val expected = setOf(
            "lib_proot.so", "Hyprland", "start-hyprland",
            "hyprpaper", "waybar", "quickshell", "dbus-run-session"
        )
        assertEquals(expected, GuestProcessReaper.GUEST_PROCESS_NAMES)
    }

    @Test
    fun isLiveSessionTracksBootingAndRunningStates() {
        val profile = StackProfile.defaultArchXiaomiPad8()

        assertTrue(SessionState.Idle.isLiveSession().not())
        assertTrue(!SessionState.Stopped.isLiveSession())
        assertTrue(!SessionState.Error("x").isLiveSession())
        assertTrue(!SessionState.Provisioning(10, "x").isLiveSession())
        assertTrue(SessionState.Starting("arch").isLiveSession())
        assertTrue(SessionState.Running(profile).isLiveSession())
        assertTrue(
            SessionState.SafeModeFallback(profile, profile, "r").isLiveSession()
        )
    }
}
