package com.alw.workstation.bridge.wayland

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

class WaylandServerManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var socketDir: File
    private lateinit var manager: WaylandServerManager

    @Before
    fun setUp() {
        socketDir = tempFolder.newFolder("runtime-sockets")
        manager = WaylandServerManager()
    }

    @Test
    fun testServerStartAndStop() {
        assertFalse(manager.isRunning())

        val started = manager.startServer(
            socketDir = socketDir,
            width = 3200,
            height = 2136,
            scale = 1.75f
        )
        assertTrue(started)
        assertTrue(manager.isRunning())

        val status = manager.getStatus()
        assertTrue(status.isRunning)
        assertNotNull(status.socketPath)
        assertTrue(status.socketPath!!.endsWith("wayland-0"))
        assertEquals(3200, status.width)
        assertEquals(2136, status.height)
        assertEquals(1.75f, status.scale, 0.001f)
        assertTrue(status.tabletV2Supported)

        manager.stopServer()
        assertFalse(manager.isRunning())
        val afterStatus = manager.getStatus()
        assertFalse(afterStatus.isRunning)
        assertNull(afterStatus.socketPath)
    }

    @Test
    fun testServerResizeSurface() {
        manager.startServer(
            socketDir = socketDir,
            width = 3200,
            height = 2136,
            scale = 1.75f
        )

        manager.resizeSurface(1920, 1080)
        val status = manager.getStatus()
        assertEquals(1920, status.width)
        assertEquals(1080, status.height)

        manager.stopServer()
    }

    @Test
    fun testTabletV2Supported() {
        assertTrue(manager.isTabletV2Supported())
    }

    @Test
    fun testMultipleStartStopCycles() {
        for (i in 1..3) {
            val started = manager.startServer(
                socketDir = socketDir,
                width = 2560,
                height = 1600,
                scale = 1.5f
            )
            assertTrue("Cycle $i start failed", started)
            assertTrue("Cycle $i isRunning false", manager.isRunning())
            manager.stopServer()
            assertFalse("Cycle $i stop failed", manager.isRunning())
        }
    }
}
