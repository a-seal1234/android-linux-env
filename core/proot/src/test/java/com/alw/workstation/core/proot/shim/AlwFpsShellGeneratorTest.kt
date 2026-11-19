package com.alw.workstation.core.proot.shim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlwFpsShellGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun shellQmlIsBundledOnTheClasspath() {
        val qml = AlwFpsShellGenerator.shellQml()
        assertTrue(qml.contains("ShellRoot"))
        assertTrue(qml.contains("alw-host-query"))
        assertTrue(qml.contains("fps"))
    }

    @Test
    fun deployWritesTheShellIntoEveryGuestHome() {
        val rootfs = tempFolder.newFolder("rootfs")

        val created = AlwFpsShellGenerator.deploy(rootfs)

        assertEquals(2, created.size)
        for (home in listOf("home/alw", "root")) {
            val shell = File(rootfs, "$home/.config/quickshell/alw-fps/shell.qml")
            assertTrue("$shell should exist", shell.isFile)
            assertTrue(shell.readText().contains("ShellRoot"))
        }
    }

    @Test
    fun deployIsNonDestructive() {
        val rootfs = tempFolder.newFolder("rootfs")
        val userShell = File(rootfs, "home/alw/.config/quickshell/alw-fps/shell.qml")
        userShell.parentFile?.mkdirs()
        userShell.writeText("// user customized shell")

        val created = AlwFpsShellGenerator.deploy(rootfs)

        assertEquals("only /root should be created", 1, created.size)
        assertEquals("// user customized shell", userShell.readText())
    }

    @Test
    fun deployHonoursACustomUserHome() {
        val rootfs = tempFolder.newFolder("rootfs")

        val created = AlwFpsShellGenerator.deploy(rootfs, listOf("home/custom"))

        assertEquals(1, created.size)
        assertTrue(File(rootfs, "home/custom/.config/quickshell/alw-fps/shell.qml").isFile)
        assertTrue(!File(rootfs, "root/.config/quickshell/alw-fps/shell.qml").exists())
    }

    @Test
    fun deployOnMissingRootfsIsANoOp() {
        val missing = File(tempFolder.root ?: error("no temp root"), "does-not-exist")

        assertTrue(AlwFpsShellGenerator.deploy(missing).isEmpty())
    }
}
