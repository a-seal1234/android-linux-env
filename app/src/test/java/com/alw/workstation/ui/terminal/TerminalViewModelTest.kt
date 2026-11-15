package com.alw.workstation.ui.terminal

import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.proot.RootfsExtractor
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var rootfsManager: RootfsManager
    private lateinit var rootfsExtractor: RootfsExtractor
    private lateinit var prootRunner: ProotRunner
    private lateinit var profileRepository: ProfileRepository

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("alw-terminal-test")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        dirs.ensureDirectoriesExist()

        rootfsManager = RootfsManager(dirs)
        rootfsExtractor = RootfsExtractor(dirs)
        prootRunner = ProotRunner(dirs)
        profileRepository = ProfileRepository(filesDir)

        val defaultProfile = StackProfile.defaultArchXiaomiPad8()
        profileRepository.saveProfile(defaultProfile)
    }

    @Test
    fun testInitialStateUnprovisioned() {
        val testDispatcher = StandardTestDispatcher()
        val viewModel = TerminalViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            prootRunner = prootRunner,
            profileRepository = profileRepository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        val state = viewModel.uiState.value
        assertFalse(state.rootfsValid)
        assertFalse(state.isRunning)
    }

    @Test
    fun testStartSessionWhenValid() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        // Create and mark rootfs valid
        val rootfsDir = rootfsManager.createRootfs("archlinux-default")
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "bin/bash").writeText("#!/bin/sh\n")
        rootfsManager.markProvisioned("archlinux-default")

        val viewModel = TerminalViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            prootRunner = prootRunner,
            profileRepository = profileRepository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.rootfsValid)

        viewModel.startSession()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isRunning)
        assertTrue(state.outputText.contains("PTY attached"))

        viewModel.sendInput("ls -la\n")
        testScheduler.advanceUntilIdle()

        viewModel.stopSession()
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun testCtrlKeyEncoding() {
        val testDispatcher = StandardTestDispatcher()
        val viewModel = TerminalViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            prootRunner = prootRunner,
            profileRepository = profileRepository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        viewModel.toggleCtrl()
        assertTrue(viewModel.uiState.value.isCtrlActive)

        viewModel.sendCtrlKey('C')
        assertFalse(viewModel.uiState.value.isCtrlActive)
    }

    @Test
    fun testDownloadAndInstallArchLinuxWithCachedFile() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = TerminalViewModel(
            dirs = dirs,
            rootfsManager = rootfsManager,
            rootfsExtractor = rootfsExtractor,
            prootRunner = prootRunner,
            profileRepository = profileRepository,
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher
        )

        // Mock cached archive file to bypass network
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        val fakeArchive = File(downloadsDir, "ArchLinuxARM-aarch64-latest.tar.gz")
        fakeArchive.writeBytes(ByteArray(60 * 1024 * 1024)) // > 50MB dummy

        viewModel.downloadAndInstallArchLinux()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isDownloadingRootfs)
    }
}
