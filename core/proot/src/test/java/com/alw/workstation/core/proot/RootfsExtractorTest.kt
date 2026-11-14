package com.alw.workstation.core.proot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class RootfsExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var dirs: RuntimeDirectories
    private lateinit var extractor: RootfsExtractor

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        dirs = RuntimeDirectories(
            filesDir = filesDir,
            nativeLibDir = File(tempFolder.root, "nativeLib").apply { mkdirs() }.absolutePath
        )
        extractor = RootfsExtractor(dirs)
    }

    private fun createTestTarGzArchive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val byteOut = ByteArrayOutputStream()
        GzipCompressorOutputStream(byteOut).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                for ((name, data) in entries) {
                    val entry = TarArchiveEntry(name)
                    entry.size = data.size.toLong()
                    if (name.startsWith("bin/")) {
                        entry.mode = 0b111_101_101 // 0755
                    } else {
                        entry.mode = 0b110_100_100 // 0644
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(data)
                    tarOut.closeArchiveEntry()
                }
            }
        }
        return byteOut.toByteArray()
    }

    @Test
    fun testExtractTarGzStream() {
        val targetDir = File(filesDir, "rootfs/test-arch")
        val archiveBytes = createTestTarGzArchive(
            listOf(
                "bin/busybox" to "#!/bin/sh\necho busybox\n".toByteArray(),
                "etc/os-release" to "NAME=ArchLinuxARM\n".toByteArray()
            )
        )

        var progressCalls = 0
        val result = extractor.extractArchiveStream(
            stream = ByteArrayInputStream(archiveBytes),
            compressionType = RootfsExtractor.CompressionType.AUTO,
            targetDir = targetDir
        ) { progress ->
            progressCalls++
        }

        assertTrue(result.isSuccess)
        assertEquals(2, result.entriesExtracted)
        assertTrue(progressCalls > 0)

        val osRelease = File(targetDir, "etc/os-release")
        assertTrue(osRelease.exists())
        assertEquals("NAME=ArchLinuxARM\n", osRelease.readText())

        val busybox = File(targetDir, "bin/busybox")
        assertTrue(busybox.exists())
        assertTrue(busybox.canExecute())

        // Check auto-provisioned network files
        val resolv = File(targetDir, "etc/resolv.conf")
        assertTrue(resolv.exists())
        assertTrue(resolv.readText().contains("nameserver 1.1.1.1"))

        val hosts = File(targetDir, "etc/hosts")
        assertTrue(hosts.exists())
        assertTrue(hosts.readText().contains("127.0.0.1"))
    }

    @Test
    fun testExtractTarGzFile() {
        val archiveFile = File(tempFolder.root, "test-rootfs.tar.gz")
        val archiveBytes = createTestTarGzArchive(
            listOf(
                "etc/hostname" to "xiaomi-pad-8\n".toByteArray()
            )
        )
        FileOutputStream(archiveFile).use { it.write(archiveBytes) }

        val targetDir = File(filesDir, "rootfs/from-file")
        val result = extractor.extractArchiveFile(archiveFile, targetDir)

        assertTrue(result.isSuccess)
        assertEquals(1, result.entriesExtracted)
        assertEquals("xiaomi-pad-8\n", File(targetDir, "etc/hostname").readText())
    }

    @Test
    fun testZipSlipDirectoryTraversalBlocked() {
        val byteOut = ByteArrayOutputStream()
        GzipCompressorOutputStream(byteOut).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                val entry = TarArchiveEntry("../evil-escape.txt")
                entry.size = 4
                tarOut.putArchiveEntry(entry)
                tarOut.write("evil".toByteArray())
                tarOut.closeArchiveEntry()
            }
        }

        val targetDir = File(filesDir, "rootfs/sandbox")
        val result = extractor.extractArchiveStream(
            stream = ByteArrayInputStream(byteOut.toByteArray()),
            compressionType = RootfsExtractor.CompressionType.GZIP,
            targetDir = targetDir
        )

        assertFalse(result.isSuccess)
        assertTrue(result.errorMessage?.contains("traversal") == true)
        assertFalse(File(filesDir, "rootfs/evil-escape.txt").exists())
    }
}
