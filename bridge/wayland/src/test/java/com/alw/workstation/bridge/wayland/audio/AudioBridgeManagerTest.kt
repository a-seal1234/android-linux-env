package com.alw.workstation.bridge.wayland.audio

import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AudioBridgeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var audioManager: AudioBridgeManager

    @Before
    fun setUp() {
        runtimeDir = tempFolder.newFolder("runtime-audio-test")
        audioManager = AudioBridgeManager()
    }

    @Test
    fun testAudioBridgeStartupAndShutdown() {
        assertFalse(audioManager.isRunning())

        val started = audioManager.startAudioServer(runtimeDir, sampleRate = 48000, channels = 2)
        assertTrue(started)
        assertTrue(audioManager.isRunning())

        val status = audioManager.getStatus()
        assertTrue(status.isRunning)
        assertTrue(status.isNullSink) // On host JVM test harness without NDK
        assertEquals(48000, status.sampleRate)
        assertEquals(2, status.channels)
        assertTrue(status.latencyMs >= 0f)
        assertNotNull(status.socketPath)

        val expectedSocket = File(runtimeDir, "pulse/native")
        assertEquals(expectedSocket.absolutePath, status.socketPath)
        assertTrue(expectedSocket.exists())

        audioManager.stopAudioServer()
        assertFalse(audioManager.isRunning())
        val stoppedStatus = audioManager.getStatus()
        assertFalse(stoppedStatus.isRunning)
    }

    @Test
    fun testVolumeClamping() {
        audioManager.setVolume(0.8f)
        assertEquals(0.8f, audioManager.getVolume(), 0.001f)

        audioManager.setVolume(2.0f)
        assertEquals(1.0f, audioManager.getVolume(), 0.001f)

        audioManager.setVolume(-0.5f)
        assertEquals(0.0f, audioManager.getVolume(), 0.001f)
    }

    @Test
    fun testCoordinationWithSessionController() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val controller = SessionController()
        val job = audioManager.coordinateWith(controller, runtimeDir, testScope)

        testScope.advanceUntilIdle()
        assertFalse(audioManager.isRunning())

        // Transition to Running state
        val profile = StackProfile.defaultArchXiaomiPad8()
        controller.updateState(SessionState.Running(profile, uptimeMillis = 1000L))
        testScope.advanceUntilIdle()

        assertTrue(audioManager.isRunning())
        val socketFile = File(runtimeDir, "pulse/native")
        assertTrue(socketFile.exists())

        // Transition to Stopped state
        controller.updateState(SessionState.Stopped)
        testScope.advanceUntilIdle()

        assertFalse(audioManager.isRunning())

        job.cancel()
    }

    @Test
    fun testCoordinationStopsOnError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val controller = SessionController()
        val job = audioManager.coordinateWith(controller, runtimeDir, testScope)

        val profile = StackProfile.defaultArchXiaomiPad8()
        controller.updateState(SessionState.Running(profile, uptimeMillis = 1000L))
        testScope.advanceUntilIdle()
        assertTrue(audioManager.isRunning())

        controller.updateState(SessionState.Error("Crash test"))
        testScope.advanceUntilIdle()
        assertFalse(audioManager.isRunning())

        job.cancel()
    }
}
