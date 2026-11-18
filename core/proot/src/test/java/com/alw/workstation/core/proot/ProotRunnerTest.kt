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
        runner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so", kgslNodeChecker = { true })
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
        assertTrue(cmd.arguments.contains("/dev/kgsl-3d0"))

        // Check rice compatibility shims in /usr/local/bin
        val guestLocalBin = File(tempDir, "rootfs/archlinux-default/usr/local/bin")
        assertTrue(File(guestLocalBin, "systemctl").canExecute())
        assertTrue(File(guestLocalBin, "fakeroot").canExecute())
        assertTrue(File(guestLocalBin, "makepkg").canExecute())
        assertTrue(File(guestLocalBin, "sudo").canExecute())

        // Check build cache redirection env vars
        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("CARGO_TARGET_DIR=/home/alw/.cache/cargo-target") })
        assertTrue(envArgs.any { it.startsWith("UV_PROJECT_ENVIRONMENT=/home/alw/.cache/uv-env") })
        assertTrue(envArgs.any { it.startsWith("PNPM_STORE_DIR=/home/alw/.cache/pnpm-store") })
        assertTrue(envArgs.any { it.startsWith("GOCACHE=/home/alw/.cache/go-build") })

        // Check Turnip KGSL driver env vars
        assertTrue(envArgs.any { it.startsWith("VK_ICD_FILENAMES=/opt/drivers/turnip/active/freedreno_icd.aarch64.json") })
        assertTrue(envArgs.any { it.startsWith("VK_DRIVER_FILES=/opt/drivers/turnip/active/freedreno_icd.aarch64.json") })
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=kms_swrast") })
        assertTrue(envArgs.none { it.startsWith("GALLIUM_DRIVER=") })
        assertTrue(envArgs.any { it.startsWith("TU_DEBUG=noconform") })

        // Check compositor exec with D-Bus session wrapping
        assertTrue(cmd.arguments.contains("dbus-run-session"))
        assertTrue(cmd.arguments.contains("--"))
        assertTrue(cmd.arguments.contains("start-hyprland"))
        assertTrue(cmd.arguments.contains("-c"))
        assertTrue(cmd.arguments.contains("/tmp/android-session.lua"))
    }

    @Test
    fun testSafeModeLabwcCommand() {
        val profile = StackProfile.safeModeProfile()
        val sessionRuntimeDir = File(tempDir, "runtime/session-safe")

        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe") })
        assertTrue(envArgs.any { it.startsWith("LIBGL_ALWAYS_SOFTWARE=1") })

        assertTrue(cmd.arguments.contains("dbus-run-session"))
        assertTrue(cmd.arguments.contains("labwc"))
        assertFalse(cmd.arguments.contains("-s"))
    }

    @Test
    fun testPtyIsolationEnsuresGuestDevPtsWithoutHostBind() {
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-pty")

        val cmd = runner.buildLaunchCommand(profile, sessionRuntimeDir)

        // Verifies guest rootfs /dev/pts directory is created
        val guestDevPts = File(tempDir, "rootfs/archlinux-default/dev/pts")
        assertTrue("Guest /dev/pts must exist for PRoot pseudo-terminal allocation", guestDevPts.exists())

        // Host /dev/pts must NOT be bound under any circumstances to keep PRoot internal PTY intact
        val devPtsMountIndex = cmd.arguments.indexOf("/dev/pts")
        assertEquals("Host /dev/pts must never be bound", -1, devPtsMountIndex)
    }

    @Test
    fun testFakeFsManagerBindsIncluded() {
        val fakeFs = com.alw.workstation.core.proot.fakefs.FakeFsManager(dirs, hostProcChecker = { false })
        val customRunner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so", fakeFsManager = fakeFs)
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-fakefs")

        val cmd = customRunner.buildLaunchCommand(profile, sessionRuntimeDir)

        assertTrue(cmd.arguments.any { it.endsWith(":/dev/shm") })
        assertTrue(cmd.arguments.any { it.endsWith(":/tmp") })
        assertTrue(cmd.arguments.any { it.endsWith(":/var/tmp") })
        assertTrue(cmd.arguments.any { it.endsWith(":/run") })
        assertTrue(cmd.arguments.any { it.endsWith(":/etc/resolv.conf") })
        assertTrue(cmd.arguments.any { it.endsWith(":/etc/hosts") })
        assertTrue(cmd.arguments.any { it.endsWith(":/proc/stat") })
        assertTrue(cmd.arguments.any { it.endsWith(":/proc/uptime") })
        assertTrue(cmd.arguments.any { it.endsWith(":/proc/loadavg") })
        assertTrue(cmd.arguments.any { it.endsWith(":/proc/version") })
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
        assertTrue(envArgs.any { it.startsWith("VK_ICD_FILENAMES=/opt/drivers/turnip/active/adreno_icd.aarch64.json") })
        // Hyprland compositor is pinned to kms_swrast on the virtual DRM node
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=kms_swrast") })
        assertTrue(envArgs.none { it.startsWith("GALLIUM_DRIVER=") })
    }

    @Test
    fun testKgslUnavailableFallsBackToLlvmpipe() {
        val fallbackRunner = ProotRunner(dirs, prootBinaryPath = "/data/app/lib/lib_proot.so", kgslNodeChecker = { false })
        val profile = StackProfile.defaultArchXiaomiPad8()
        val sessionRuntimeDir = File(tempDir, "runtime/session-fallback")

        val cmd = fallbackRunner.buildLaunchCommand(profile, sessionRuntimeDir)

        // Verify /dev/kgsl-3d0 is NOT bound
        assertFalse(cmd.arguments.contains("/dev/kgsl-3d0"))

        // Verify LLVMPIPE fallback environment
        val envArgs = cmd.arguments.filter { it.contains("=") }
        assertTrue(envArgs.any { it.startsWith("MESA_LOADER_DRIVER_OVERRIDE=llvmpipe") })
        assertTrue(envArgs.any { it.startsWith("GALLIUM_DRIVER=llvmpipe") })
        assertTrue(envArgs.any { it.startsWith("LIBGL_ALWAYS_SOFTWARE=1") })
        assertFalse(envArgs.any { it.startsWith("VK_ICD_FILENAMES=") })
        assertFalse(envArgs.any { it.startsWith("VK_DRIVER_FILES=") })
    }

    @Test
    fun testEmulationHooks() {
        val baseProfile = StackProfile.defaultArchXiaomiPad8()

        // 1. Verify zero overhead when emulation is disabled
        assertFalse(baseProfile.emulation.enabled)
        val cmdDefault = runner.buildLaunchCommand(baseProfile, File(tempDir, "runtime/session-def"))
        assertFalse(cmdDefault.arguments.contains("-q"))

        // 2. Fallback when overlay directory not yet installed on host
        val fexProfile = baseProfile.copy(
            emulation = StackProfile.EmulationConfig(
                enabled = true,
                engine = StackProfile.EmulationEngine.FEX,
                overlayVersion = "fex-2412"
            )
        )
        val sessionRuntimeDir = File(tempDir, "runtime/session-fex")

        val cmdFex = runner.buildLaunchCommand(fexProfile, sessionRuntimeDir)
        val qIndex = cmdFex.arguments.indexOf("-q")
        assertTrue(qIndex >= 0)
        assertEquals("/opt/fex/bin/FEXInterpreter", cmdFex.arguments[qIndex + 1])

        // 3. Active overlay present on host
        val overlayDir = dirs.getOverlayDir("fex", "fex-2412")
        overlayDir.mkdirs()
        File(overlayDir, "bin").mkdirs()
        val cmdFexWithOverlay = runner.buildLaunchCommand(fexProfile, sessionRuntimeDir)
        val qIndexOverlay = cmdFexWithOverlay.arguments.indexOf("-q")
        assertTrue(qIndexOverlay >= 0)
        assertEquals("/opt/overlays/fex/active/bin/FEXInterpreter", cmdFexWithOverlay.arguments[qIndexOverlay + 1])
        assertTrue(cmdFexWithOverlay.arguments.contains("${overlayDir.absolutePath}:/opt/overlays/fex/active:ro"))
        assertTrue(cmdFexWithOverlay.arguments.contains("${overlayDir.absolutePath}:/opt/fex:ro"))

        // 4. BOX64 emulation
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

        // 5. Custom interpreter path override
        val customProfile = baseProfile.copy(
            emulation = StackProfile.EmulationConfig(
                enabled = true,
                engine = StackProfile.EmulationEngine.FEX,
                customInterpreterPath = "/custom/bin/fex"
            )
        )
        val cmdCustom = runner.buildLaunchCommand(customProfile, sessionRuntimeDir)
        val customQIndex = cmdCustom.arguments.indexOf("-q")
        assertTrue(customQIndex >= 0)
        assertEquals("/custom/bin/fex", cmdCustom.arguments[customQIndex + 1])
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
