package com.alw.workstation.core.proot

import com.alw.workstation.core.session.model.StackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotRunnerTest {

    @Test
    fun testBuildLaunchCommandArguments() {
        val tempDir = File("/tmp/alw-test-files")
        val dirs = RuntimeDirectories(
            filesDir = tempDir,
            nativeLibDir = "/data/app/lib"
        )
        val runner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so")
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
}
