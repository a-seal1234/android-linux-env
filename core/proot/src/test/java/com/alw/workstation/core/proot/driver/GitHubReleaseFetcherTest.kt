package com.alw.workstation.core.proot.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class GitHubReleaseFetcherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCuratedReleasesContainAdreno825Mesa25() {
        val fetcher = GitHubReleaseFetcher()
        val releases = fetcher.listAvailableReleases()

        assertTrue(releases.isNotEmpty())
        val mesa25 = releases.find { it.version.contains("25.0.0") }
        assertNotNull(mesa25)
        assertTrue(mesa25!!.supportedGpus.contains("Adreno 825 (A8xx)"))
        assertTrue(mesa25.isRecommended)
        assertEquals("1.4.0", mesa25.apiVersion)
    }

    @Test
    fun testComputeSha256Checksum() {
        val fetcher = GitHubReleaseFetcher()
        val file = File(tempFolder.root, "test.bin").apply {
            writeText("Mesa Turnip KGSL Driver Payload")
        }

        val sha256 = fetcher.computeSha256(file)
        assertNotNull(sha256)
        assertEquals(64, sha256.length)

        assertTrue(fetcher.verifyChecksum(file, sha256))
        assertFalse(fetcher.verifyChecksum(file, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun testDownloadReleaseVerifiesChecksum() {
        val payload = "Simulated Driver Archive Data".toByteArray()
        val dummySha = GitHubReleaseFetcher().run {
            val temp = File(tempFolder.root, "temp.bin").apply { writeBytes(payload) }
            computeSha256(temp).also { temp.delete() }
        }

        val release = DriverRelease(
            version = "turnip-test-25.0",
            releaseTag = "v25.0",
            downloadUrl = "https://mock.example.com/driver.tar.xz",
            archiveName = "driver.tar.xz",
            sha256Checksum = dummySha
        )

        val fetcher = GitHubReleaseFetcher(streamOpener = { ByteArrayInputStream(payload) })
        val dest = File(tempFolder.root, "downloaded.tar.xz")

        val downloaded = fetcher.downloadRelease(release, dest)
        assertTrue(downloaded.exists())
        assertEquals(dummySha, fetcher.computeSha256(downloaded))
    }

    @Test(expected = SecurityException::class)
    fun testDownloadReleaseRejectsTamperedChecksum() {
        val payload = "Tampered Driver Data".toByteArray()
        val release = DriverRelease(
            version = "turnip-tampered",
            releaseTag = "v25.0",
            downloadUrl = "https://mock.example.com/driver.tar.xz",
            archiveName = "driver.tar.xz",
            sha256Checksum = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        )

        val fetcher = GitHubReleaseFetcher(streamOpener = { ByteArrayInputStream(payload) })
        val dest = File(tempFolder.root, "tampered.tar.xz")

        fetcher.downloadRelease(release, dest)
    }
}
