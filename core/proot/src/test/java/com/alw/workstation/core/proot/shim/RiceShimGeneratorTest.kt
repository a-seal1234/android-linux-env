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
    fun testInstallShimsCreatesAllFourFiles() {
        RiceShimGenerator.installShims(targetDir)

        val systemctl = File(targetDir, "systemctl")
        val fakeroot = File(targetDir, "fakeroot")
        val makepkg = File(targetDir, "makepkg")
        val sudo = File(targetDir, "sudo")

        assertTrue("systemctl shim must exist", systemctl.isFile)
        assertTrue("systemctl shim must be executable", systemctl.canExecute())

        assertTrue("fakeroot shim must exist", fakeroot.isFile)
        assertTrue("fakeroot shim must be executable", fakeroot.canExecute())

        assertTrue("makepkg shim must exist", makepkg.isFile)
        assertTrue("makepkg shim must be executable", makepkg.canExecute())

        assertTrue("sudo shim must exist", sudo.isFile)
        assertTrue("sudo shim must be executable", sudo.canExecute())
    }

    @Test
    fun testSystemctlShimContent() {
        val content = RiceShimGenerator.SYSTEMCTL_SHIM
        assertTrue(content.contains("GUI_DAEMONS=\"waybar swaync mako swww-daemon pipewire wireplumber ags\""))
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
