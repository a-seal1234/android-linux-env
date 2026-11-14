package com.alw.workstation.core.proot.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuCompanionManagerTest {

    private class MockShizukuShellRunner(
        private val handler: (List<String>) -> ShizukuCommandResult
    ) : ShizukuShellRunner {
        val executedCommands = mutableListOf<List<String>>()

        override fun execute(command: List<String>): ShizukuCommandResult {
            executedCommands.add(command)
            return handler(command)
        }
    }

    @Test
    fun testInitialStateNotInstalled() {
        val manager = ShizukuCompanionManager(
            isInstalledProvider = { false },
            isRunningProvider = { false },
            hasPermissionProvider = { false },
            ioDispatcher = Dispatchers.Unconfined
        )

        val state = manager.companionState.value
        assertEquals(ShizukuState.NOT_INSTALLED, state.state)
        assertFalse(state.isReady)
        assertFalse(state.isBypassed)

        manager.requestPermission()
        assertEquals("Shizuku is not installed on this device", manager.companionState.value.lastError)
    }

    @Test
    fun testInitialStateStopped() {
        val manager = ShizukuCompanionManager(
            isInstalledProvider = { true },
            isRunningProvider = { false },
            hasPermissionProvider = { false },
            ioDispatcher = Dispatchers.Unconfined
        )

        val state = manager.companionState.value
        assertEquals(ShizukuState.STOPPED, state.state)
        assertFalse(state.isReady)

        manager.requestPermission()
        assertEquals("Shizuku service is not running. Please start Shizuku.", manager.companionState.value.lastError)
    }

    @Test
    fun testPermissionRequiredAndGrantFlow() {
        var requestedCode = 0
        val manager = ShizukuCompanionManager(
            isInstalledProvider = { true },
            isRunningProvider = { true },
            hasPermissionProvider = { false },
            requestPermissionAction = { requestedCode = it },
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals(ShizukuState.PERMISSION_REQUIRED, manager.companionState.value.state)

        manager.requestPermission()
        assertEquals(ShizukuCompanionManager.SHIZUKU_PERMISSION_REQUEST_CODE, requestedCode)

        // Deny permission
        manager.onPermissionResult(ShizukuCompanionManager.SHIZUKU_PERMISSION_REQUEST_CODE, PackageManager.PERMISSION_DENIED)
        assertEquals(ShizukuState.PERMISSION_REQUIRED, manager.companionState.value.state)
        assertEquals("Shizuku permission was denied", manager.companionState.value.lastError)

        // Grant permission
        manager.onPermissionResult(ShizukuCompanionManager.SHIZUKU_PERMISSION_REQUEST_CODE, PackageManager.PERMISSION_GRANTED)
        assertEquals(ShizukuState.READY, manager.companionState.value.state)
        assertTrue(manager.companionState.value.isReady)
    }

    @Test
    fun testApplyPhantomProcessBypassSuccess() = runBlocking {
        val mockRunner = MockShizukuShellRunner { cmd ->
            when {
                cmd.contains("get") && cmd.contains("max_phantom_processes") ->
                    ShizukuCommandResult(0, "2147483647", "")
                else ->
                    ShizukuCommandResult(0, "Success", "")
            }
        }

        val manager = ShizukuCompanionManager(
            shellRunner = mockRunner,
            isInstalledProvider = { true },
            isRunningProvider = { true },
            hasPermissionProvider = { true },
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals(ShizukuState.READY, manager.companionState.value.state)

        val success = manager.applyPhantomProcessBypass("com.alw.workstation")
        assertTrue(success)

        val state = manager.companionState.value
        assertTrue(state.phantomProcessStatus.isBypassed)
        assertTrue(state.phantomProcessStatus.currentLimit.contains("Unlimited"))
        assertTrue(state.phantomProcessStatus.powerKeeperWhitelisted)

        // Verify the expected bypass commands were executed
        assertTrue(mockRunner.executedCommands.size >= 6)
        assertTrue(mockRunner.executedCommands.any { it.contains("set_sync_disabled_for_tests") })
        assertTrue(mockRunner.executedCommands.any { it.contains("max_phantom_processes") && it.contains("put") })
        assertTrue(mockRunner.executedCommands.any { it.contains("deviceidle") && it.contains("+com.alw.workstation") })
        assertTrue(mockRunner.executedCommands.any { it.contains("appops") && it.contains("RUN_IN_BACKGROUND") })
    }

    @Test
    fun testReadPhantomProcessLimitDefault() = runBlocking {
        val mockRunner = MockShizukuShellRunner { cmd ->
            if (cmd.contains("get")) {
                ShizukuCommandResult(0, "null", "")
            } else {
                ShizukuCommandResult(0, "", "")
            }
        }

        val manager = ShizukuCompanionManager(
            shellRunner = mockRunner,
            isInstalledProvider = { true },
            isRunningProvider = { true },
            hasPermissionProvider = { true },
            ioDispatcher = Dispatchers.Unconfined
        )

        val limit = manager.readPhantomProcessLimit()
        assertEquals("32 (Default - 32 Process Cap)", limit)
        assertFalse(manager.companionState.value.phantomProcessStatus.isBypassed)
    }
}
