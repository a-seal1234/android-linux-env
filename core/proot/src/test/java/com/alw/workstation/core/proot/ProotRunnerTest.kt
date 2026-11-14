package com.alw.workstation.core.proot

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var runner: ProotRunner

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("alw-test-files")
        dirs = RuntimeDirectories(
            filesDir = tempDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        runner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so")
    }

    @Test
    fun testBuildLaunchCommandArguments() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-0")

        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        assertEquals("/data/app/lib/lib_proot.so", cmd.binary)
        assertTrue(cmd.arguments.contains("-0"))
        assertTrue(cmd.arguments.contains("--link2symlink"))
        assertTrue(cmd.arguments.contains("-r"))
        assertTrue(cmd.arguments.contains(File(tempDir, "rootfs/archlinux-default").absolutePath))
        assertTrue(cmd.arguments.contains("-b"))
        assertTrue(cmd.arguments.contains("/proc"))
        assertTrue(cmd.arguments.contains("/dev"))
        assertTrue(cmd.arguments.contains("/sys"))

        // Check build cache redirection env vars
        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("CARGO_TARGET_DIR=/home/alw/.cache/cargo-target") })
        assertTrue(envArgs.any { it.startsWith("UV_PROJECT_ENVIRONMENT=/home/alw/.cache/uv-env") })
        assertTrue(envArgs.any { it.startsWith("PNPM_STORE_DIR=/home/alw/.cache/pnpm-store") })
        assertTrue(envArgs.any { it.startsWith("GOCACHE=/home/alw/.cache/go-build") })

        // Check Turnip KGSL driver env vars
        assertTrue(envArgs.any { it.startsWith("VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json") })
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=kgsl") })

        // Check compositor exec
        assertTrue(cmd.arguments.contains("Hyprland"))
        assertTrue(cmd.arguments.contains("-c"))
        assertTrue(cmd.arguments.contains("/tmp/android-session.conf"))
    }

    @Test
    fun testSafeModeLabwcCommand() {
        val profile = StackProfile.safeModeProfile()
        val sessionRuntimeDir = File(tempDir, "runtime/session-safe")

        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe") })
        assertTrue(envArgs.any { it.startsWith("LIBGL_ALWAYS_SOFTWARE=1") })

        assertTrue(cmd.arguments.contains("labwc"))
    }

    @Test
    fun testAdrenoProprietaryZinkDriverCommand() {
        val baseProfile = StackProfile.defaultArchXiaomiPad8()
        val proprietaryProfile = baseProfile.copy(
            gpu = baseProfile.gpu.copy(
                driverMode = StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK,
                mesaLoaderOverride = "zink"
            )
        )
        val sessionRuntimeDir = File(tempDir, "runtime/session-zink")

        val cmd = runner.buildLaunchCommand(proprietaryProfile, sessionRuntimeDir)

        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/adreno_icd.aarch64.json") })
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=zink") })
    }

    @Test
    fun testEmulationHooks() {
        val baseProfile = StackProfile.defaultArchXiaomiPad8()
        val fexProfile = baseProfile.copy(
            emulation = StackProfile.EmulationConfig(
                enabled = true,
                engine = StackProfile.EmulationEngine.FEX
            )
        )
        val sessionRuntimeDir = File(tempDir, "runtime/session-fex")

        val cmdFex = runner.buildLaunchCommand(fexProfile, sessionRuntimeDir)
        val qIndex = cmdFex.arguments.indexOf("-q")
        assertTrue(qIndex >= 0)
        assertEquals("/opt/fex/bin/FEXInterpreter", cmdFex.arguments[qIndex + 1])

        val boxProfile = baseProfile.copy(
            emulation = StackProfile.EmulationConfig(
                enabled = true,
                engine = StackProfile.EmulationEngine.BOX64
            )
        )
        val cmdBox = runner.buildLaunchCommand(boxProfile, sessionRuntimeDir)
        val boxQIndex = cmdBox.arguments.indexOf("-q")
        assertTrue(boxQIndex >= 0)
        assertEquals("/usr/bin/box64", cmdBox.arguments[boxQIndex + 1])
    }

    @Test
    fun testCustomCommandExecution() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-cust")

        val cmd = runner.buildLaunchCommand(
            profile = profile,
            sessionRuntimeDir = sessionRuntimeDir,
            customExecCommand = "/bin/bash",
            customArgs = listOf("-c", "/tmp/provision.sh")
        )

        assertFalse(cmd.arguments.contains("Hyprland"))
        assertTrue(cmd.arguments.contains("/bin/bash"))
        assertTrue(cmd.arguments.contains("/tmp/provision.sh"))
    }
}
