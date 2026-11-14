package com.alw.workstation.core.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RootfsManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var manager: RootfsManager

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        manager = RootfsManager(dirs)
    }

    @Test
    fun testListRootfsEmpty() {
        val list = manager.listRootfs()
        assertTrue(list.isEmpty())
    }

    @Test
    fun testCreateAndListRootfs() {
        manager.createRootfs("archlinux-default")
        manager.createRootfs("debian-test")

        val list = manager.listRootfs()
        assertEquals(2, list.size)
        assertEquals("archlinux-default", list[0].id)
        assertEquals("debian-test", list[1].id)
        assertFalse(list[0].isValid) // No shell yet
    }

    @Test
    fun testCheckRootfsValidity() {
        val archDir = manager.createRootfs("arch-test")
        assertFalse(manager.checkRootfsValidity(archDir))

        // Create /usr/bin/sh
        val binSh = File(archDir, "usr/bin/sh")
        binSh.parentFile?.mkdirs()
        binSh.writeText("#!/bin/sh\n")

        val info = manager.getRootfsInfo("arch-test")
        assertNotNull(info)
        assertTrue(info!!.isValid)
    }

    @Test
    fun testDeleteRootfs() {
        manager.createRootfs("to-delete")
        assertNotNull(manager.getRootfsInfo("to-delete"))

        val deleted = manager.deleteRootfs("to-delete")
        assertTrue(deleted)
        assertNull(manager.getRootfsInfo("to-delete"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPathTraversalRootfsIdRejected() {
        manager.createRootfs("../outside")
    }

    @Test
    fun testCloneRootfs() {
        val source = manager.createRootfs("source-distro")
        val testFile = File(source, "etc/os-release").apply {
            parentFile?.mkdirs()
            writeText("NAME=\"Arch Linux ARM\"\n")
        }
        val testScript = File(source, "bin/run.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\necho OK\n")
            setExecutable(true)
        }

        // Create symlink
        val linkFile = File(source, "bin/sh")
        try {
            Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get("run.sh"))
        } catch (e: Exception) {
            // Ignored if platform doesn't support symlinks in test
        }

        var progressCalls = 0
        val cloneSuccess = manager.cloneRootfs("source-distro", "cloned-distro") { _, _ ->
            progressCalls++
        }

        assertTrue(cloneSuccess)
        val target = dirs.getRootfsDir("cloned-distro")
        assertTrue(target.exists())
        assertEquals("NAME=\"Arch Linux ARM\"\n", File(target, "etc/os-release").readText())
        assertTrue(File(target, "bin/run.sh").canExecute())
    }

    @Test
    fun testDiscoverWaylandSessions() {
        val rootfsDir = manager.createRootfs("arch-desktop")
        val sessionsDir = File(rootfsDir, "usr/share/wayland-sessions").apply { mkdirs() }

        val hyprlandDesktop = File(sessionsDir, "hyprland.desktop")
        hyprlandDesktop.writeText(
            """
            [Desktop Entry]
            Name=Hyprland
            Comment=An intelligent dynamic tiling Wayland compositor
            Exec=Hyprland
            Type=Application
            """.trimIndent()
        )

        val labwcDesktop = File(sessionsDir, "labwc.desktop")
        labwcDesktop.writeText(
            """
            [Desktop Entry]
            Name=Labwc
            Comment=A Wayland window-stacking compositor
            Exec=labwc
            Type=Application
            """.trimIndent()
        )

        val sessions = manager.discoverWaylandSessions("arch-desktop")
        assertEquals(2, sessions.size)

        val hypr = sessions.first { it.id == "hyprland" }
        assertEquals("Hyprland", hypr.name)
        assertEquals("Hyprland", hypr.exec)
        assertEquals("An intelligent dynamic tiling Wayland compositor", hypr.comment)

        val labwc = sessions.first { it.id == "labwc" }
        assertEquals("Labwc", labwc.name)
        assertEquals("labwc", labwc.exec)
    }

    @Test
    fun testMarkProvisioned() {
        manager.createRootfs("arch-prov")
        var info = manager.getRootfsInfo("arch-prov")
        assertNotNull(info)
        assertFalse(info!!.isProvisioned)

        manager.markProvisioned("arch-prov", true)
        info = manager.getRootfsInfo("arch-prov")
        assertTrue(info!!.isProvisioned)

        manager.markProvisioned("arch-prov", false)
        info = manager.getRootfsInfo("arch-prov")
        assertFalse(info!!.isProvisioned)
    }
}
