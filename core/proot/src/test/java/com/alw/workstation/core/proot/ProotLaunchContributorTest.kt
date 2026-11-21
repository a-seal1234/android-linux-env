package com.alw.workstation.core.proot

import com.alw.workstation.core.proot.driver.AngleBundleManager
import com.alw.workstation.core.proot.model.BindMount
import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotLaunchContributorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: File
    private lateinit var dirs: RuntimeDirectories

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("alw-test-files")
        dirs = RuntimeDirectories(
            filesDir = tempDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
    }

    @Test
    fun testContributorsExecutedInPriorityOrder() {
        val executionOrder = mutableListOf<String>()

        val contributorLowPriority = object : ProotLaunchContributor {
            override val priority: Int = 200
            override fun extraEnvVars(profile: StackProfile, dirs: RuntimeDirectories): Map<String, String> {
                executionOrder.add("low")
                return mapOf("TEST_ORDER" to "low")
            }
        }

        val contributorHighPriority = object : ProotLaunchContributor {
            override val priority: Int = 10
            override fun extraEnvVars(profile: StackProfile, dirs: RuntimeDirectories): Map<String, String> {
                executionOrder.add("high")
                return mapOf("TEST_ORDER" to "high")
            }
        }

        val runner = ProotRunner(
            dirs = dirs,
            prootBinaryPath = "/data/app/lib/lib_proot.so",
            contributors = listOf(contributorLowPriority, contributorHighPriority)
        )

        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-priority")
        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        assertEquals(listOf("high", "low"), executionOrder)
        // Later contributor overwrites earlier in Map.putAll
        assertTrue(cmd.arguments.contains("TEST_ORDER=low"))
    }

    @Test
    fun testAngleBundleContributorWhenInstalled() {
        // Create simulated active ANGLE bundle
        val angleDir = dirs.getAngleBundleDir("2.1.0").apply { mkdirs() }
        dirs.getAngleActivePointer().parentFile?.mkdirs()
        dirs.getAngleActivePointer().writeText("2.1.0\n")
        AngleBundleManager.REQUIRED_LIBRARIES.forEach { libName ->
            File(angleDir, libName).writeText("stub")
        }
        val manager = AngleBundleManager(dirs)
        assertTrue("Bundle should be installed", manager.isBundleInstalled())

        val contributor = AngleBundleContributor(dirs, manager)
        val rootfsDir = dirs.getRootfsDir("archlinux-default").apply { mkdirs() }
        val profile = StackProfile.defaultArchXiaomiPad8()

        val binds = contributor.extraBindMounts(profile, rootfsDir, dirs)
        assertEquals(1, binds.size)
        assertEquals(angleDir.absolutePath, binds[0].hostPath)
        assertEquals("/opt/angle/lib", binds[0].guestPath)

        val env = contributor.extraEnvVars(profile, dirs)
        assertEquals("freedreno", env["ALW_GL_BACKEND"])
    }

    @Test
    fun testAngleBundleContributorWhenNotInstalled() {
        val manager = AngleBundleManager(dirs)
        val contributor = AngleBundleContributor(dirs, manager)
        val rootfsDir = dirs.getRootfsDir("archlinux-default").apply { mkdirs() }
        val profile = StackProfile.defaultArchXiaomiPad8().copy(
            gpu = StackProfile.defaultArchXiaomiPad8().gpu.copy(
                driverMode = StackProfile.DriverMode.TURNIP_KGSL
            )
        )

        val binds = contributor.extraBindMounts(profile, rootfsDir, dirs)
        assertTrue(binds.isEmpty())

        val env = contributor.extraEnvVars(profile, dirs)
        assertEquals("zink", env["ALW_GL_BACKEND"])
    }

    @Test
    fun testOnProcessSpawnedDispatched() {
        var capturedPid = -1L
        val testContributor = object : ProotLaunchContributor {
            override val priority: Int = 50
            override fun onProcessSpawned(pid: Long, profile: StackProfile) {
                capturedPid = pid
            }
        }

        val runner = ProotRunner(
            dirs = dirs,
            prootBinaryPath = "/data/app/lib/lib_proot.so",
            contributors = listOf(testContributor)
        )

        val profile = StackProfile.defaultArchXiaomiPad8()
        runner.onProcessSpawned(12345L, profile)
        assertEquals(12345L, capturedPid)
    }

    @Test
    fun testEnvDMaterializationInLaunchCommand() {
        val runner = ProotRunner(dirs = dirs, prootBinaryPath = "/data/app/lib/lib_proot.so")
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-envd")

        runner.buildLaunchCommand(profile, sessionRuntimeDir)

        val envFile = File(tempDir, "rootfs/archlinux-default/etc/alw/env.d/10-alw-renderer.sh")
        assertTrue("10-alw-renderer.sh must be materialized", envFile.isFile)
        val content = envFile.readText()
        assertTrue(content.contains("VK_ICD_FILENAMES"))
        assertTrue(content.contains("ALW_GL_BACKEND"))
    }

    @Test
    fun testCorePinningContributor() {
        val contributor = CorePinningContributor(dirs)
        val profile = StackProfile.defaultArchXiaomiPad8()

        val env = contributor.extraEnvVars(profile, dirs)
        assertEquals("2", env["MALLOC_ARENA_MAX"])

        val overrides = contributor.runnerEnvOverrides()
        assertTrue(overrides.containsKey("PROOT_NO_SECCOMP"))
        assertEquals(null, overrides["PROOT_NO_SECCOMP"])
    }

    @Test
    fun testSandboxIsolationContributor() {
        val contributor = SandboxIsolationContributor(dirs)
        val profile = StackProfile.defaultArchXiaomiPad8()
        val rootfsDir = dirs.getRootfsDir("archlinux-default").apply { mkdirs() }

        val env = contributor.extraEnvVars(profile, dirs)
        assertEquals("1", env["MOZ_DISABLE_CONTENT_SANDBOX"])
        assertEquals("1", env["MOZ_DISABLE_RDD_SANDBOX"])
        assertEquals("1", env["MOZ_DISABLE_GMP_SANDBOX"])
        assertEquals("1", env["MOZ_DISABLE_SOCKET_PROCESS_SANDBOX"])
        assertEquals("1", env["QTWEBENGINE_DISABLE_SANDBOX"])
        assertEquals("wayland", env["ELECTRON_OZONE_PLATFORM_HINT"])

        val binds = contributor.extraBindMounts(profile, rootfsDir, dirs)
        assertEquals(1, binds.size)
        assertEquals(File(dirs.fakefsBaseDir, "dev/shm").absolutePath, binds[0].hostPath)
        assertEquals("/dev/shm", binds[0].guestPath)
    }

    @Test
    fun testDefaultContributorsAppliedInProotRunner() {
        val runner = ProotRunner(dirs = dirs, prootBinaryPath = "/data/app/lib/lib_proot.so")
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-defaults")

        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        // PROOT_NO_SECCOMP should be unset in host runner env by CorePinningContributor
        assertFalse(cmd.environment.containsKey("PROOT_NO_SECCOMP"))
        assertEquals(dirs.prootTmpDir.absolutePath, cmd.environment["PROOT_TMP_DIR"])

        // Guest environment arguments should include tuning & sandbox variables
        assertTrue(cmd.arguments.contains("MALLOC_ARENA_MAX=2"))
        assertTrue(cmd.arguments.contains("MOZ_DISABLE_CONTENT_SANDBOX=1"))
        assertTrue(cmd.arguments.contains("QTWEBENGINE_DISABLE_SANDBOX=1"))
        assertTrue(cmd.arguments.contains("ELECTRON_OZONE_PLATFORM_HINT=wayland"))

        // Bind mount for /dev/shm should be present
        val shmHostPath = File(dirs.fakefsBaseDir, "dev/shm").absolutePath
        val hasShmBind = cmd.arguments.any { it.contains(shmHostPath) && it.contains("/dev/shm") }
        assertTrue("Command should contain bind mount for /dev/shm", hasShmBind)
    }
}
