package com.alw.workstation.core.proot.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RiceShimGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("local_bin")
    }

    @Test
    fun testInstallShimsCreatesAllRequiredFiles() {
        RiceShimGenerator.installShims(targetDir)

        val expectedShims = listOf(
            "startplasma-wayland",
            "plasma-session-start",
            "systemctl",
            "service",
            "loginctl",
            "org_kde_powerdevil",
            "upowerd",
            "udisksd",
            "pkexec",
            "fakeroot",
            "makepkg",
            "sudo",
            "xdg-open"
        )

        for (shimName in expectedShims) {
            val file = File(targetDir, shimName)
            assertTrue("$shimName shim must exist", file.isFile)
            assertTrue("$shimName shim must be executable", file.canExecute())
        }
    }

    @Test
    fun testStartPlasmaWaylandShimContent() {
        val content = RiceShimGenerator.STARTPLASMA_WAYLAND_SHIM
        assertTrue(content.contains("kwin_wayland"))
        assertTrue(content.contains("plasma-session-start"))
        assertTrue(content.contains("XDG_RUNTIME_DIR"))

        val sessionContent = RiceShimGenerator.PLASMA_SESSION_START_SHIM
        assertTrue(sessionContent.contains("plasmashell &"))
        assertTrue(sessionContent.contains("konsole"))
    }

    @Test
    fun testLoginctlShimContent() {
        val content = RiceShimGenerator.LOGINCTL_SHIM
        assertTrue(content.contains("Active=yes"))
        assertTrue(content.contains("State=active"))
        assertTrue(content.contains("Type=wayland"))
        assertTrue(content.contains("exit 0"))
    }

    @Test
    fun testPkexecShimContent() {
        val content = RiceShimGenerator.PKEXEC_SHIM
        assertTrue(content.contains("exec \"$@\""))
    }

    @Test
    fun testXdgOpenShimContent() {
        val content = RiceShimGenerator.XDG_OPEN_SHIM
        assertTrue(content.contains("am start -a android.intent.action.VIEW"))
    }

    @Test
    fun testExitZeroStubContent() {
        val content = RiceShimGenerator.EXIT_ZERO_STUB
        assertTrue(content.contains("exit 0"))
    }

    @Test
    fun testSystemctlShimContent() {
        val content = RiceShimGenerator.SYSTEMCTL_SHIM
        assertTrue(content.contains("GUI_DAEMONS=\"waybar swaync mako swww-daemon pipewire wireplumber ags plasma-plasmashell plasma-xdg-desktop-portal-kde\""))
        assertTrue(content.contains("HARDWARE_DAEMONS=\"NetworkManager bluetooth ufw powerdevil upower udisks2\""))
        assertTrue(content.contains("autostart.conf"))
        assertTrue(content.contains("exec-once ="))
        assertTrue(content.contains("--user"))
        assertTrue(content.contains("exit 0"))
    }

    @Test
    fun testFakerootShimContent() {
        val content = RiceShimGenerator.FAKEROOT_SHIM
        assertTrue(content.contains("fakeroot-tcp"))
        assertTrue(content.contains("exec fakeroot-tcp \"$@\""))
    }

    @Test
    fun testMakepkgShimContent() {
        val content = RiceShimGenerator.MAKEPKG_SHIM
        assertTrue(content.contains("-A"))
        assertTrue(content.contains("--ignorearch"))
        assertTrue(content.contains("exec \"\$REAL_MAKEPKG\" -A \"\$@\""))
    }

    @Test
    fun testSudoShimContent() {
        val content = RiceShimGenerator.SUDO_SHIM
        assertTrue(content.contains("exec \"\$@\""))
    }
}
