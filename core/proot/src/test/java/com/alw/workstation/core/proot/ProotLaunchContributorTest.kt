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
}
