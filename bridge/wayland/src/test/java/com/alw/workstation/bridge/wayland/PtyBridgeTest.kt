package com.alw.workstation.bridge.wayland

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class PtyBridgeTest {

    @Test
    fun testCreatePtyFallback() {
        val ptyFds = PtyBridge.createPty(cols = 120, rows = 40)
        assertNotNull(ptyFds)
        assertTrue(ptyFds!!.masterFd >= 0)
        assertTrue(ptyFds.slaveFd >= 0)
    }

    @Test
    fun testSetWindowSize() {
        val success = PtyBridge.setWindowSize(masterFd = 100, cols = 160, rows = 50)
        assertTrue(success)
    }

    @Test
    fun testForkSubprocessFallback() {
        val result = PtyBridge.forkSubprocess(
            command = "/bin/bash",
            args = listOf("-l"),
            env = mapOf("PATH" to "/bin:/usr/bin"),
            cwd = "/root",
            cols = 80,
            rows = 24
        )
        assertNotNull(result)
        assertTrue(result!!.pid > 0)
        assertTrue(result.masterFd >= 0)
    }

    @Test
    fun testPtySessionStreamPumping() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val inputData = "Hello from Linux PTY\n"
        val mockInputStream = ByteArrayInputStream(inputData.toByteArray())
        val mockOutputStream = ByteArrayOutputStream()

        val session = PtySession(
            pid = 9999,
            masterFd = 42,
            scope = testScope,
            ioDispatcher = testDispatcher,
            customInputStream = mockInputStream,
            customOutputStream = mockOutputStream
        )

        testScheduler.advanceUntilIdle()

        session.write("echo test\n")
        testScheduler.advanceUntilIdle()

        assertEquals("echo test\n", mockOutputStream.toString())

        session.close()
    }
}
