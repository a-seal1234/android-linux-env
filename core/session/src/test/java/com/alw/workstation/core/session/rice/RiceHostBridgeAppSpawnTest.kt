package com.alw.workstation.core.session.rice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RiceHostBridgeAppSpawnTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var socketFile: File

    @Before
    fun setUp() {
        socketFile = File(tempFolder.root, "alw-host.sock")
    }

    @Test
    fun testAppSpawnWithoutHandlerReturnsError() {
        val bridge = RiceHostBridge(
            socketFile = socketFile,
            appSpawnHandler = null
        )

        val response = bridge.execute("app_spawn /usr/bin/code")
        assertEquals("error=no-spawn-handler\n", response)
    }

    @Test
    fun testAppSpawnEmptyCommandReturnsError() {
        val bridge = RiceHostBridge(
            socketFile = socketFile,
            appSpawnHandler = { _, _ -> Result.success(1234L) }
        )

        val response = bridge.execute("app_spawn")
        assertEquals("error=missing-command\n", response)
    }

    @Test
    fun testAppSpawnSuccessfulRouting() {
        var capturedCmd: String? = null
        var capturedArgs: List<String>? = null

        val bridge = RiceHostBridge(
            socketFile = socketFile,
            appSpawnHandler = { cmd, args ->
                capturedCmd = cmd
                capturedArgs = args
                Result.success(4321L)
            }
        )

        val response = bridge.execute("app_spawn /usr/bin/code --no-sandbox --disable-gpu")
        assertEquals("ok=1 pid=4321\n", response)
        assertEquals("/usr/bin/code", capturedCmd)
        assertEquals(listOf("--no-sandbox", "--disable-gpu"), capturedArgs)
    }

    @Test
    fun testAppSpawnFailureRouting() {
        val bridge = RiceHostBridge(
            socketFile = socketFile,
            appSpawnHandler = { _, _ -> Result.failure(IllegalStateException("Resource exhausted")) }
        )

        val response = bridge.execute("app-spawn /usr/bin/code")
        assertEquals("error=Resource exhausted\n", response)
    }
}
