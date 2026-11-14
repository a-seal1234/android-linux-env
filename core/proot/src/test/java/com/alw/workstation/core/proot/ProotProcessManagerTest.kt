package com.alw.workstation.core.proot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotProcessManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val processManager = ProotProcessManager()

    @Test
    fun testExecuteCommandSynchronous() = runBlocking {
        val cmd = ProotRunner.LaunchCommand(
            binary = "/bin/sh",
            arguments = listOf("-c", "echo 'ALW_PROCESS_OK'"),
            environment = emptyMap(),
            workingDir = tempFolder.root
        )

        val result = processManager.executeCommand(cmd)

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("ALW_PROCESS_OK"))
        assertTrue(result.stderr.isEmpty())
    }

    @Test
    fun testExecuteCommandCapturesStderr() = runBlocking {
        val cmd = ProotRunner.LaunchCommand(
            binary = "/bin/sh",
            arguments = listOf("-c", "echo 'ERR_OUTPUT' >&2; exit 42"),
            environment = emptyMap(),
            workingDir = tempFolder.root
        )

        val result = processManager.executeCommand(cmd)

        assertEquals(42, result.exitCode)
        assertTrue(result.stderr.contains("ERR_OUTPUT"))
    }

    @Test
    fun testStartProcessAndTerminate() {
        val cmd = ProotRunner.LaunchCommand(
            binary = "/bin/sh",
            arguments = listOf("-c", "sleep 30"),
            environment = emptyMap(),
            workingDir = tempFolder.root
        )

        val managed = processManager.startProcess(cmd)
        assertTrue(managed.isAlive)

        val exitCode = managed.terminate(gracePeriodMs = 150)
        assertFalse(managed.isAlive)
    }
}
