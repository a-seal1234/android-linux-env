package com.alw.workstation.core.proot.driver

import com.alw.workstation.core.proot.RuntimeDirectories
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

class TurnipDriverManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private lateinit var manager: TurnipDriverManager
    private lateinit var fetcher: GitHubReleaseFetcher

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("alw-files")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        dirs.ensureDirectoriesExist()
        fetcher = GitHubReleaseFetcher()
        manager = TurnipDriverManager(dirs, fetcher)
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
        // 16..17: e_type (ET_DYN)
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
    fun testInstallTurnipDriverSuccess() {
        val elfData = createSyntheticElf(listOf("libvulkan_freedreno.so", "libc.so.6", "GLIBC_2.38"))
        val tarGzData = createTarGzArchive(mapOf("libvulkan_freedreno.so" to elfData))

        val archiveFile = File(tempFolder.root, "turnip-25.0.0.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = fetcher.computeSha256(archiveFile)

        val manifest = manager.installDriverArchive(
            version = "turnip-mesa-25.0.0",
            archiveFile = archiveFile,
            expectedSha256 = checksum,
            releaseTag = "v25.0.0"
        )

        assertEquals("turnip-mesa-25.0.0", manifest.version)
        assertEquals(ElfAbiType.GLIBC_AARCH64, manifest.abiType)
        assertEquals("freedreno_icd.aarch64.json", manifest.icdFileName)
        assertEquals("libvulkan_freedreno.so", manifest.libraryFileName)

        // Verify driver directory in shared store
        val driverDir = dirs.getTurnipDriverDir("turnip-mesa-25.0.0")
        assertTrue(driverDir.exists())
        assertTrue(File(driverDir, "libvulkan_freedreno.so").exists())
        assertTrue(File(driverDir, "freedreno_icd.aarch64.json").exists())
        assertTrue(File(driverDir, "manifest.json").exists())

        // Verify listInstalledDrivers
        val installed = manager.listInstalledDrivers()
        assertEquals(1, installed.size)
        assertEquals("turnip-mesa-25.0.0", installed[0].version)
    }

    @Test(expected = SecurityException::class)
    fun testInstallRejectsSha256Mismatch() {
        val elfData = createSyntheticElf(listOf("libvulkan_freedreno.so", "libc.so.6"))
        val tarGzData = createTarGzArchive(mapOf("libvulkan_freedreno.so" to elfData))
        val archiveFile = File(tempFolder.root, "tampered.tar.gz").apply { writeBytes(tarGzData) }

        manager.installDriverArchive(
            version = "turnip-tampered",
            archiveFile = archiveFile,
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
    }

    @Test(expected = IllegalStateException::class)
    fun testInstallRejectsBionicDriverForArchLinux() {
        val bionicElf = createSyntheticElf(listOf("libvulkan_freedreno.so", "/system/bin/linker64", "liblog.so", "libc.so"))
        val tarGzData = createTarGzArchive(mapOf("libvulkan_freedreno.so" to bionicElf))

        val archiveFile = File(tempFolder.root, "bionic-driver.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = fetcher.computeSha256(archiveFile)

        manager.installDriverArchive(
            version = "bionic-driver",
            archiveFile = archiveFile,
            expectedSha256 = checksum
        )
    }

    @Test
    fun testActivateAndGetActiveDriver() {
        val elfData = createSyntheticElf(listOf("libvulkan_freedreno.so", "libc.so.6", "GLIBC_2.38"))
        val tarGzData = createTarGzArchive(mapOf("libvulkan_freedreno.so" to elfData))
        val archiveFile = File(tempFolder.root, "turnip-25.0.0.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = fetcher.computeSha256(archiveFile)

        manager.installDriverArchive("turnip-mesa-25.0.0", archiveFile, checksum)

        assertNull(manager.getActiveDriver())

        val activatedDir = manager.activateDriver("turnip-mesa-25.0.0")
        assertTrue(activatedDir.exists())

        val active = manager.getActiveDriver()
        assertNotNull(active)
        assertEquals("turnip-mesa-25.0.0", active!!.version)
    }

    @Test
    fun testDeleteDriver() {
        val elfData = createSyntheticElf(listOf("libvulkan_freedreno.so", "libc.so.6", "GLIBC_2.38"))
        val tarGzData = createTarGzArchive(mapOf("libvulkan_freedreno.so" to elfData))
        val archiveFile = File(tempFolder.root, "turnip-del.tar.gz").apply { writeBytes(tarGzData) }
        val checksum = fetcher.computeSha256(archiveFile)

        manager.installDriverArchive("turnip-to-delete", archiveFile, checksum)
        manager.activateDriver("turnip-to-delete")

        assertTrue(manager.deleteDriver("turnip-to-delete"))
        assertFalse(dirs.getTurnipDriverDir("turnip-to-delete").exists())
        assertNull(manager.getActiveDriver())
    }
}
