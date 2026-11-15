package com.alw.workstation.core.proot.emulator

import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.driver.ElfAbiType
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class EmulatorManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private lateinit var manager: EmulatorManager

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("alw-files")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        dirs.ensureDirectoriesExist()
        manager = EmulatorManager(dirs)
    }

    private fun createSyntheticElf(strings: List<String>): ByteArray {
        val stream = ByteArrayOutputStream()
        // 0..3: ELF Magic
        stream.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        // 4: Class 64-bit
        stream.write(2)
        // 5: Little-endian
        stream.write(1)
        // 6: Version
        stream.write(1)
        // 7: OS/ABI
        stream.write(0)
        // 8..15: Padding
        stream.write(ByteArray(8))
        // 16..17: e_type (ET_EXEC / ET_DYN)
        stream.write(byteArrayOf(3, 0))
        // 18..19: e_machine (183 = EM_AARCH64)
        stream.write(byteArrayOf(0xB7.toByte(), 0x00))
        // 20..23: e_version
        stream.write(byteArrayOf(1, 0, 0, 0))

        while (stream.size() < 64) {
            stream.write(0)
        }

        for (s in strings) {
            stream.write(s.toByteArray(Charsets.US_ASCII))
            stream.write(0)
        }
        return stream.toByteArray()
    }

    private fun createTarGzArchive(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        GzipCompressorOutputStream(baos).use { gzo ->
            TarArchiveOutputStream(gzo).use { tao ->
                for ((name, data) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = data.size.toLong()
                    entry.mode = 0x1ED // 0755
                    tao.putArchiveEntry(entry)
                    tao.write(data)
                    tao.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun testListInstalledOverlaysEmptyInitially() {
        val installed = manager.listInstalledOverlays()
        assertTrue(installed.isEmpty())
        assertNull(manager.getActiveOverlay("fex"))
        assertNull(manager.getActiveOverlay("box64"))
    }

    @Test
    fun testInstallFexOverlaySuccess() {
        val elfData = createSyntheticElf(listOf("FEXInterpreter", "libc.so.6", "GLIBC_2.38"))
        val thunkDummy = "thunk host lib".toByteArray()
        val tarGzData = createTarGzArchive(
            mapOf(
                "bin/FEXInterpreter" to elfData,
                "thunks/host/libvulkan_freedreno.so" to thunkDummy
            )
        )

        val archiveFile = File(tempFolder.root, "fex-2412-aarch64.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = manager.computeSha256(archiveFile)

        val manifest = manager.installOverlayArchive(
            engine = "fex",
            version = "fex-2412",
            archiveFile = archiveFile,
            expectedSha256 = checksum,
            displayName = "FEX-Emu v2412 (Turnip Thunks)"
        )

        assertEquals("fex", manifest.engine)
        assertEquals("fex-2412", manifest.version)
        assertEquals("bin/FEXInterpreter", manifest.interpreterRelativePath)
        assertEquals(ElfAbiType.GLIBC_AARCH64, manifest.abiType)
        assertTrue(manifest.hasThunkLibs)

        // Verify listing and active state
        val installed = manager.listInstalledOverlays("fex")
        assertEquals(1, installed.size)
        assertEquals("fex-2412", installed[0].version)

        val active = manager.getActiveOverlay("fex")
        assertNotNull(active)
        assertEquals("fex-2412", active?.version)
    }

    @Test
    fun testInstallBox64OverlaySuccess() {
        val elfData = createSyntheticElf(listOf("box64", "libc.so.6", "GLIBC_2.38"))
        val tarGzData = createTarGzArchive(mapOf("bin/box64" to elfData))

        val archiveFile = File(tempFolder.root, "box64-0.3.2.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = manager.computeSha256(archiveFile)

        val manifest = manager.installOverlayArchive(
            engine = "box64",
            version = "box64-v0.3.2",
            archiveFile = archiveFile,
            expectedSha256 = checksum
        )

        assertEquals("box64", manifest.engine)
        assertEquals("box64-v0.3.2", manifest.version)
        assertEquals("bin/box64", manifest.interpreterRelativePath)
        assertEquals(ElfAbiType.GLIBC_AARCH64, manifest.abiType)
        assertFalse(manifest.hasThunkLibs)

        val active = manager.getActiveOverlay("box64")
        assertEquals("box64-v0.3.2", active?.version)
    }

    @Test(expected = IllegalStateException::class)
    fun testBionicElfRejected() {
        val bionicElfData = createSyntheticElf(listOf("FEXInterpreter", "/system/bin/linker64", "libc.so"))
        val tarGzData = createTarGzArchive(mapOf("bin/FEXInterpreter" to bionicElfData))
        val archiveFile = File(tempFolder.root, "fex-bionic.tar.gz").apply { writeBytes(tarGzData) }

        manager.installOverlayArchive(
            engine = "fex",
            version = "fex-bionic",
            archiveFile = archiveFile
        )
    }

    @Test(expected = SecurityException::class)
    fun testSha256MismatchThrows() {
        val elfData = createSyntheticElf(listOf("FEXInterpreter", "libc.so.6", "GLIBC_2.38"))
        val tarGzData = createTarGzArchive(mapOf("bin/FEXInterpreter" to elfData))
        val archiveFile = File(tempFolder.root, "fex-sha.tar.gz").apply { writeBytes(tarGzData) }

        manager.installOverlayArchive(
            engine = "fex",
            version = "fex-sha",
            archiveFile = archiveFile,
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }

    @Test
    fun testActivateAndDeactivateOverlay() {
        val elfData = createSyntheticElf(listOf("FEXInterpreter", "libc.so.6", "GLIBC_2.38"))
        val tarGz1 = createTarGzArchive(mapOf("bin/FEXInterpreter" to elfData))
        val tarGz2 = createTarGzArchive(mapOf("bin/FEXInterpreter" to elfData))

        val f1 = File(tempFolder.root, "fex-1.tar.gz").apply { writeBytes(tarGz1) }
        val f2 = File(tempFolder.root, "fex-2.tar.gz").apply { writeBytes(tarGz2) }

        manager.installOverlayArchive("fex", "fex-v1", f1)
        manager.installOverlayArchive("fex", "fex-v2", f2)

        manager.activateOverlay("fex", "fex-v2")
        assertEquals("fex-v2", manager.getActiveOverlay("fex")?.version)

        manager.activateOverlay("fex", "fex-v1")
        assertEquals("fex-v1", manager.getActiveOverlay("fex")?.version)

        manager.deactivateOverlay("fex")
        assertNull(manager.getActiveOverlay("fex"))
    }

    @Test
    fun testDeleteOverlay() {
        val elfData = createSyntheticElf(listOf("FEXInterpreter", "libc.so.6", "GLIBC_2.38"))
        val tarGz = createTarGzArchive(mapOf("bin/FEXInterpreter" to elfData))
        val f = File(tempFolder.root, "fex-del.tar.gz").apply { writeBytes(tarGz) }

        manager.installOverlayArchive("fex", "fex-del", f)
        assertTrue(manager.isOverlayInstalled("fex", "fex-del"))

        val deleted = manager.deleteOverlay("fex", "fex-del")
        assertTrue(deleted)
        assertFalse(manager.isOverlayInstalled("fex", "fex-del"))
        assertNull(manager.getActiveOverlay("fex"))
    }
}
