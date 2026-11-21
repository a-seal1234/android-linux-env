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
            "xdg-open",
            "alw-host-query",
            "brightnessctl",
            "wpctl",
            "pamixer",
            "nmcli"
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
    fun testGuiRunShimOffersFreedrenoBackend() {
        val shim = RiceShimGenerator.ALW_GUI_RUN_SHIM

        // freedreno renders GL/GLES directly on /dev/kgsl-3d0 (no Vulkan/Zink layer),
        // while ANGLE and Zink stay selectable through the same variable.
        assertTrue(shim.contains("freedreno)"))
        assertTrue(shim.contains("export MESA_LOADER_DRIVER_OVERRIDE=kgsl"))
        assertTrue(shim.contains("export GALLIUM_DRIVER=kgsl"))
        // Linear sysmem rendering is required by the bridge's dma-buf upload path.
        assertTrue(shim.contains("FD_MESA_DEBUG"))
        assertTrue(shim.contains("sysmem,noubwc"))
        assertTrue(shim.contains("ALW_GL_BACKEND=zink"))
        assertTrue(shim.contains("ANGLE_DEFAULT_PLATFORM=vulkan"))
    }

    @Test
    fun testRiceHostShimsContent() {
        val query = RiceShimGenerator.ALW_HOST_QUERY_SHIM
        assertTrue(query.contains("/alw-host.sock"))
        assertTrue(query.contains("python3"))
        assertTrue(query.contains("AF_UNIX"))

        val brightness = RiceShimGenerator.BRIGHTNESSCTL_SHIM
        assertTrue(brightness.contains("/sys/class/backlight/alw_backlight"))
        assertTrue(brightness.contains("alw-host-query \"brightness set"))
        assertTrue(brightness.contains("alw_backlight,backlight,"))
        assertTrue(brightness.contains("max)"))

        val wpctl = RiceShimGenerator.WPCTL_SHIM
        assertTrue(wpctl.contains("get-volume"))
        assertTrue(wpctl.contains("[MUTED]"))
        assertTrue(wpctl.contains("set-mute"))
        assertTrue(wpctl.contains("alw-host-query \"volume"))

        val pamixer = RiceShimGenerator.PAMIXER_SHIM
        assertTrue(pamixer.contains("--get-volume"))
        assertTrue(pamixer.contains("--get-mute"))
        assertTrue(pamixer.contains("volume step"))

        val nmcli = RiceShimGenerator.NMCLI_SHIM
        assertTrue(nmcli.contains("alw-host-query \"network\""))
        assertTrue(nmcli.contains("yes:\$SSID"))
        assertTrue(nmcli.contains("STATE"))

        val xdgOpen = RiceShimGenerator.XDG_OPEN_SHIM
        assertTrue(xdgOpen.contains("alw-host-query \"open"))
    }

    @Test
    fun testSystemctlShimHandlesPowerVerbs() {
        val content = RiceShimGenerator.SYSTEMCTL_SHIM
        assertTrue(content.contains("poweroff|reboot|suspend|hibernate"))
        assertTrue(content.contains("is-system-running"))
        assertTrue(content.contains("handled by the Android host"))
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
