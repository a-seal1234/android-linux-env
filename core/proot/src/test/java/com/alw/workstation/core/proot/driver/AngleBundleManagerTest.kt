package com.alw.workstation.core.proot.driver

import com.alw.workstation.core.proot.RuntimeDirectories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AngleBundleManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private lateinit var manager: AngleBundleManager

    @Test
    fun testNotInstalledWhenStoreIsEmpty() {
        setUpDirs()
        assertFalse(manager.isBundleInstalled())
    }

    @Test
    fun testVerifyRejectsMissingManifestAndLibraries() {
        setUpDirs()
        val bundle = tempFolder.newFolder("bundle-a")
        val verdict = manager.verifyBundle(bundle)

        assertFalse(verdict.isValid)
        assertTrue(verdict.detailMessage.contains(AngleBundleManager.MANIFEST_NAME))
    }

    @Test
    fun testVerifyRejectsNonElfPayload() {
        setUpDirs()
        val bundle = tempFolder.newFolder("bundle-b")
        AngleBundleManager.REQUIRED_LIBRARIES.forEach { name ->
            File(bundle, name).writeText("not an elf")
        }
        val sha = shaOver(bundle)
        File(bundle, AngleBundleManager.MANIFEST_NAME).writeText(
            """{"version":"test","sha256":"$sha","abiType":"GLIBC_AARCH64"}"""
        )

        val verdict = manager.verifyBundle(bundle)
        assertFalse(verdict.isValid)
        assertTrue(verdict.detailMessage.contains(".so"))
    }

    @Test
    fun testActiveBundleResolutionFollowsPointer() {
        setUpDirs()
        dirs.getAngleBundleDir("1.2.3").mkdirs()
        assertEquals(
            dirs.getAngleBundleDir("1.2.3"),
            dirs.getActiveAngleBundleDir().also {
                dirs.getAngleActivePointer().writeText("1.2.3")
            }.let { dirs.getActiveAngleBundleDir() }
        )
    }

    @Test
    fun testManifestRoundTrip() {
        setUpDirs()
        val dir = tempFolder.newFolder("manifest")
        val manifest = AngleBundleManager.BundleManifest(
            version = "7258",
            sha256 = "abc",
            source = "cross-build"
        )
        File(dir, AngleBundleManager.MANIFEST_NAME).writeText(manifest.toString())

        val parsed = manager.readBundleManifest(
            File(dir, AngleBundleManager.MANIFEST_NAME)
        )
        assertEquals("7258", parsed?.version)
        assertEquals("abc", parsed?.sha256)
        assertEquals(ElfAbiType.GLIBC_AARCH64, parsed?.abiType)
    }

    private fun setUpDirs() {
        dirs = RuntimeDirectories(
            filesDir = tempFolder.newFolder("files-${System.nanoTime()}"),
            nativeLibDir = tempFolder.newFolder("lib").absolutePath
        )
        dirs.ensureDirectoriesExist()
        manager = AngleBundleManager(dirs)
    }

    private fun shaOver(bundle: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        AngleBundleManager.REQUIRED_LIBRARIES.forEach {
            digest.update(File(bundle, it).readText().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
