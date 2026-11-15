package com.alw.workstation.core.proot.fakefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeFsManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("fakefs-test")
    }

    @Test
    fun testMaterializeCreatesAllDirectoriesAndFiles() {
        val manager = FakeFsManager(baseDir)
        manager.materialize()

        // Check directories
        assertTrue("dev/shm must exist", manager.devShmDir.isDirectory)
        assertTrue("tmp must exist", manager.tmpDir.isDirectory)
        assertTrue("var/tmp must exist", manager.varTmpDir.isDirectory)
        assertTrue("run must exist", manager.runDir.isDirectory)
        assertTrue("run/lock must exist", manager.runLockDir.isDirectory)
        assertTrue("run/dbus must exist", manager.runDbusDir.isDirectory)
        assertTrue("run/user/1000 must exist", manager.runUser1000Dir.isDirectory)

        // Check network files
        assertTrue("etc/resolv.conf must exist", manager.etcResolvConf.isFile)
        val resolvContent = manager.etcResolvConf.readText()
        assertTrue(resolvContent.contains("nameserver 1.1.1.1"))
        assertTrue(resolvContent.contains("nameserver 8.8.8.8"))

        assertTrue("etc/hosts must exist", manager.etcHosts.isFile)
        val hostsContent = manager.etcHosts.readText()
        assertTrue(hostsContent.contains("127.0.0.1 localhost"))

        // Check synthetic /proc nodes
        assertTrue("proc/stat must exist", manager.procStat.isFile)
        val statContent = manager.procStat.readText()
        assertTrue(statContent.contains("cpu  "))
        assertTrue(statContent.contains("cpu0 "))
        assertTrue(statContent.contains("cpu7 "))
        assertTrue(statContent.contains("processes "))

        assertTrue("proc/uptime must exist", manager.procUptime.isFile)
        val uptimeContent = manager.procUptime.readText()
        assertTrue(uptimeContent.trim().split(" ").size == 2)

        assertTrue("proc/loadavg must exist", manager.procLoadavg.isFile)
        assertTrue(manager.procLoadavg.readText().contains("0.10 0.08 0.05"))

        assertTrue("proc/version must exist", manager.procVersion.isFile)
        assertTrue(manager.procVersion.readText().contains("Linux version 6.6.0-alw-proot-aarch64"))
    }

    @Test
    fun testGetBindMountsWhenHostProcUnreadable() {
        val manager = FakeFsManager(baseDir, hostProcChecker = { false })
        manager.materialize()

        val mounts = manager.getBindMounts()
        val guestPaths = mounts.map { it.guestPath }

        assertTrue(guestPaths.contains("/dev/shm"))
        assertTrue(guestPaths.contains("/tmp"))
        assertTrue(guestPaths.contains("/var/tmp"))
        assertTrue(guestPaths.contains("/run"))
        assertTrue(guestPaths.contains("/etc/resolv.conf"))
        assertTrue(guestPaths.contains("/etc/hosts"))
        assertTrue(guestPaths.contains("/proc/stat"))
        assertTrue(guestPaths.contains("/proc/uptime"))
        assertTrue(guestPaths.contains("/proc/loadavg"))
        assertTrue(guestPaths.contains("/proc/version"))
    }

    @Test
    fun testGetBindMountsWhenHostProcReadable() {
        val manager = FakeFsManager(baseDir, hostProcChecker = { true })
        manager.materialize()

        val mounts = manager.getBindMounts()
        val guestPaths = mounts.map { it.guestPath }

        assertTrue(guestPaths.contains("/dev/shm"))
        assertTrue(guestPaths.contains("/tmp"))
        assertTrue(guestPaths.contains("/var/tmp"))
        assertTrue(guestPaths.contains("/run"))
        assertTrue(guestPaths.contains("/etc/resolv.conf"))
        assertTrue(guestPaths.contains("/etc/hosts"))

        // Should NOT override when readable on host
        assertFalse(guestPaths.contains("/proc/stat"))
        assertFalse(guestPaths.contains("/proc/uptime"))
        assertFalse(guestPaths.contains("/proc/loadavg"))
        assertFalse(guestPaths.contains("/proc/version"))
    }
}
