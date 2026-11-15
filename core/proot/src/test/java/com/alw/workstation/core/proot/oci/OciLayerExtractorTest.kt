package com.alw.workstation.core.proot.oci

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class OciLayerExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val extractor = OciLayerExtractor()

    private fun createTarArchive(file: File, entries: Map<String, ByteArray>) {
        FileOutputStream(file).use { fos ->
            TarArchiveOutputStream(fos).use { tarOut ->
                entries.forEach { (name, bytes) ->
                    val entry = TarArchiveEntry(name).apply {
                        size = bytes.size.toLong()
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(bytes)
                    tarOut.closeArchiveEntry()
                }
            }
        }
    }

    @Test
    fun testStandardAndOpaqueWhiteoutResolution() = runBlocking {
        val rootfsDir = tempFolder.newFolder("rootfs")
        val layersDir = tempFolder.newFolder("layers")

        // Layer 1: Lower base layer
        val layer1File = File(layersDir, "layer1.tar")
        createTarArchive(
            layer1File,
            mapOf(
                "etc/kept.conf" to "keep me\n".toByteArray(),
                "etc/deleted.conf" to "delete me\n".toByteArray(),
                "var/log/old.log" to "old log\n".toByteArray(),
                "var/log/another.log" to "another old log\n".toByteArray()
            )
        )

        // Layer 2: Upper layer with standard whiteout (.wh.deleted.conf) and opaque whiteout (var/log/.wh..wh..opq)
        val layer2File = File(layersDir, "layer2.tar")
        createTarArchive(
            layer2File,
            mapOf(
                "etc/.wh.deleted.conf" to ByteArray(0),
                "var/log/.wh..wh..opq" to ByteArray(0),
                "var/log/new.log" to "new log\n".toByteArray()
            )
        )

        val result = extractor.extractLayers(listOf(layer1File, layer2File), rootfsDir)
        assertTrue(result.isSuccess)

        // Verify standard whiteout: etc/deleted.conf must NOT exist
        assertFalse(File(rootfsDir, "etc/deleted.conf").exists())
        assertFalse(File(rootfsDir, "etc/.wh.deleted.conf").exists())
        assertTrue(File(rootfsDir, "etc/kept.conf").exists())

        // Verify opaque whiteout: all old files in var/log must be deleted, new.log preserved
        assertFalse(File(rootfsDir, "var/log/old.log").exists())
        assertFalse(File(rootfsDir, "var/log/another.log").exists())
        assertFalse(File(rootfsDir, "var/log/.wh..wh..opq").exists())
        assertTrue(File(rootfsDir, "var/log/new.log").exists())
        assertEquals("new log\n", File(rootfsDir, "var/log/new.log").readText())
    }

    @Test
    fun testAntiZipSlipGuard() {
        val rootfsDir = tempFolder.newFolder("rootfs-sec")
        val layerFile = File(tempFolder.root, "malicious.tar")

        createTarArchive(
            layerFile,
            mapOf(
                "../../malicious.sh" to "echo pwned\n".toByteArray()
            )
        )

        val result = runBlocking { extractor.extractLayers(listOf(layerFile), rootfsDir) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }
}
