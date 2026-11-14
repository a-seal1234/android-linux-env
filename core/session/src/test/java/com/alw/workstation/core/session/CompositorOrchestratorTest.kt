package com.alw.workstation.core.session

import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompositorOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sessionController: SessionController
    private lateinit var sessionRuntimeDir: File
    private var mockCurrentTime = 1000000L

    @Before
    fun setUp() {
        sessionController = SessionController()
        sessionRuntimeDir = tempFolder.newFolder("runtime")
        mockCurrentTime = 1000000L
    }

    @Test
    fun testNormalHyprlandSessionSucceeds() = runTest {
        val profile = StackProfile.defaultArchXiaomiPad8()

        val launcher = SessionProcessLauncher { _, _, _, _ ->
            // Advance time by 10 seconds (exceeds 5-second crash window)
            mockCurrentTime += 10000L
            0 // Clean exit code
        }

        val orchestrator = CompositorOrchestrator(
            sessionController = sessionController,
            processLauncher = launcher,
            crashWindowMillis = 5000L,
            timeProvider = { mockCurrentTime }
        )

        val outcome = orchestrator.launchSession(profile, sessionRuntimeDir)

        assertTrue(outcome is CompositorOrchestrator.LaunchOutcome.Success)
        val success = outcome as CompositorOrchestrator.LaunchOutcome.Success
        assertEquals(0, success.exitCode)
        assertEquals(10000L, success.uptimeMillis)
        assertEquals(SessionState.Stopped, sessionController.sessionState.value)
    }

    @Test
    fun testEarlyHyprlandCrashTriggersAutomaticSafeModeFallback() = runTest {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val launchedProfiles = mutableListOf<StackProfile>()

        val launcher = SessionProcessLauncher { launchProfile, _, _, _ ->
            launchedProfiles.add(launchProfile)
            if (launchProfile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
                // Advance time by only 800ms (crash within 5s window)
                mockCurrentTime += 800L
                139 // SIGSEGV crash
            } else {
                // Safe Mode LabWC succeeds
                mockCurrentTime += 4000L
                0
            }
        }

        val orchestrator = CompositorOrchestrator(
            sessionController = sessionController,
            processLauncher = launcher,
            crashWindowMillis = 5000L,
            timeProvider = { mockCurrentTime }
        )

        val outcome = orchestrator.launchSession(profile, sessionRuntimeDir)

        // Verifies fallback was triggered
        assertTrue(outcome is CompositorOrchestrator.LaunchOutcome.FallbackTriggered)
        val fallback = outcome as CompositorOrchestrator.LaunchOutcome.FallbackTriggered

        assertEquals(StackProfile.CompositorType.HYPRLAND, fallback.originalProfile.compositor.type)
        assertEquals(StackProfile.CompositorType.LABWC, fallback.fallbackProfile.compositor.type)
        assertEquals(StackProfile.DriverMode.LLVMPIPE, fallback.fallbackProfile.gpu.driverMode)
        assertEquals(0, fallback.fallbackExitCode)
        assertTrue(fallback.reason.contains("Hyprland terminated prematurely"))

        // Verifies both profiles were launched in sequence
        assertEquals(2, launchedProfiles.size)
        assertEquals(StackProfile.CompositorType.HYPRLAND, launchedProfiles[0].compositor.type)
        assertEquals(StackProfile.CompositorType.LABWC, launchedProfiles[1].compositor.type)

        // Session ends in Stopped state after successful Safe Mode exit
        assertEquals(SessionState.Stopped, sessionController.sessionState.value)
    }

    @Test
    fun testNonZeroExitHyprlandTriggersFallback() = runTest {
        val profile = StackProfile.defaultArchXiaomiPad8()

        val launcher = SessionProcessLauncher { launchProfile, _, _, _ ->
            if (launchProfile.compositor.type == StackProfile.CompositorType.HYPRLAND) {
                mockCurrentTime += 2000L
                1 // General error
            } else {
                mockCurrentTime += 1000L
                0
            }
        }

        val orchestrator = CompositorOrchestrator(
            sessionController = sessionController,
            processLauncher = launcher,
            crashWindowMillis = 5000L,
            timeProvider = { mockCurrentTime }
        )

        val outcome = orchestrator.launchSession(profile, sessionRuntimeDir)
        assertTrue(outcome is CompositorOrchestrator.LaunchOutcome.FallbackTriggered)
    }

    @Test
    fun testDirectLabwcSafeModeDoesNotLoopOnCrash() = runTest {
        val safeProfile = StackProfile.safeModeProfile()

        val launcher = SessionProcessLauncher { _, _, _, _ ->
            mockCurrentTime += 300L
            1 // LabWC failure
        }

        val orchestrator = CompositorOrchestrator(
            sessionController = sessionController,
            processLauncher = launcher,
            crashWindowMillis = 5000L,
            timeProvider = { mockCurrentTime }
        )

        val outcome = orchestrator.launchSession(safeProfile, sessionRuntimeDir)

        // Must NOT attempt infinite fallback loop; returns Error immediately
        assertTrue(outcome is CompositorOrchestrator.LaunchOutcome.Error)
        assertTrue(sessionController.sessionState.value is SessionState.Error)
    }

    @Test
    fun testRootfsDefaultConfigsCreatedOnFirstBoot() = runTest {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val rootfsDir = tempFolder.newFolder("rootfs-test")

        val launcher = SessionProcessLauncher { _, _, _, _ ->
            mockCurrentTime += 6000L
            0
        }

        val orchestrator = CompositorOrchestrator(
            sessionController = sessionController,
            processLauncher = launcher,
            timeProvider = { mockCurrentTime }
        )

        orchestrator.launchSession(profile, sessionRuntimeDir, rootfsDir = rootfsDir)

        val hyprFile = File(rootfsDir, "home/alw/.config/hypr/hyprland.conf")
        val labwcFile = File(rootfsDir, "home/alw/.config/labwc/rc.xml")
        assertTrue(hyprFile.exists())
        assertTrue(labwcFile.exists())
    }
}
