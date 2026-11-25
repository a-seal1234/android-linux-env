package com.alw.workstation.core.proot

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotAppLauncherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var runner: ProotRunner
    private lateinit var runtimeDir: File
    private val profile = StackProfile.defaultArchXiaomiPad8()

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("alw-test-files")
        dirs = RuntimeDirectories(
            filesDir = tempDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        runner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so", kgslNodeChecker = { true })
        runtimeDir = File(tempDir, "runtime/session-0").apply { mkdirs() }
    }

    @Test
    fun testSpawnAppBuildsIsolatedCommand() {
        var spawnedCommand: ProotRunner.LaunchCommand? = null
        val fakeProcessManager = object : ProotProcessManager() {
            override fun startProcess(
                command: ProotRunner.LaunchCommand,
                listener: ProcessListener?
            ): ManagedProcess {
                spawnedCommand = command
                // Create mock Process
                val mockProcess = ProcessBuilder("sh", "-c", "exit 0").start()
                return ManagedProcess(mockProcess, command)
            }
        }

        val launcher = ProotAppLauncher(
            runner = runner,
            processManager = fakeProcessManager,
            profileProvider = { profile },
            runtimeDirProvider = { runtimeDir }
        )

        val result = launcher.spawnApp("/usr/bin/code", listOf("--no-sandbox", "--disable-gpu"))
        assertTrue("spawnApp should succeed", result.isSuccess)

        val cmd = spawnedCommand
        assertTrue("Command should have been captured", cmd != null)
        assertEquals("ALW_ISOLATED must be 1 to prevent recursive delegation", "1", cmd?.environment?.get("ALW_ISOLATED"))
        assertTrue("Arguments should contain the target binary", cmd?.arguments?.contains("/usr/bin/code") == true)
        assertTrue("Arguments should contain custom args", cmd?.arguments?.contains("--no-sandbox") == true)
        assertTrue("Arguments should contain custom args", cmd?.arguments?.contains("--disable-gpu") == true)
    }

    @Test
    fun testSpawnAppFailsWhenNoProfileOrRuntime() {
        val launcherNoProfile = ProotAppLauncher(
            runner = runner,
            profileProvider = { null },
            runtimeDirProvider = { runtimeDir }
        )
        val res1 = launcherNoProfile.spawnApp("code", emptyList())
        assertTrue(res1.isFailure)

        val launcherNoRuntime = ProotAppLauncher(
            runner = runner,
            profileProvider = { profile },
            runtimeDirProvider = { null }
        )
        val res2 = launcherNoRuntime.spawnApp("code", emptyList())
        assertTrue(res2.isFailure)
    }

    @Test
    fun testTerminateAllClearsActiveProcesses() {
        val fakeProcessManager = object : ProotProcessManager() {
            override fun startProcess(
                command: ProotRunner.LaunchCommand,
                listener: ProcessListener?
            ): ManagedProcess {
                val mockProcess = ProcessBuilder("sleep", "10").start()
                return ManagedProcess(mockProcess, command)
            }
        }

        val launcher = ProotAppLauncher(
            runner = runner,
            processManager = fakeProcessManager,
            profileProvider = { profile },
            runtimeDirProvider = { runtimeDir }
        )

        val res = launcher.spawnApp("code", emptyList())
        assertTrue(res.isSuccess)

        launcher.terminateAll(100L)
        assertEquals(0, launcher.activeProcessCount())
    }
}
