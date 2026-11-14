package com.alw.workstation.core.proot

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.ServerSocket

class RootfsDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dirs: RuntimeDirectories
    private var server: SimpleHttpServer? = null

    private class SimpleHttpServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        @Volatile private var running = true
        var handler: ((path: String) -> Pair<Int, ByteArray>)? = null

        init {
            Thread {
                while (running) {
                    try {
                        val socket = serverSocket.accept()
                        Thread {
                            try {
                                val reader = socket.getInputStream().bufferedReader()
                                val line = reader.readLine() ?: return@Thread
                                val path = line.split(" ").getOrNull(1) ?: "/"
                                val (code, data) = handler?.invoke(path) ?: Pair(404, ByteArray(0))
                                val out = socket.getOutputStream()
                                when (code) {
                                    302 -> {
                                        val location = String(data)
                                        out.write("HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    }
                                    200 -> {
                                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\n\r\n".toByteArray())
                                        out.write(data)
                                    }
                                    else -> {
                                        out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    }
                                }
                                out.flush()
                            } catch (e: Exception) {
                                // Socket stream closed
                            } finally {
                                try { socket.close() } catch (ignored: Exception) {}
                            }
                        }.start()
                    } catch (e: Exception) {
                        // Server closed
                    }
                }
            }.start()
        }

        override fun close() {
            running = false
            try { serverSocket.close() } catch (ignored: Exception) {}
        }
    }

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        val nativeLibDir = tempFolder.newFolder("lib").absolutePath
        dirs = RuntimeDirectories(filesDir, nativeLibDir)
        dirs.ensureDirectoriesExist()

        server = SimpleHttpServer()
    }

    @After
    fun tearDown() {
        server?.close()
    }

    @Test
    fun testCachedRootfsReturnedImmediately() = runBlocking {
        val downloader = RootfsDownloader(
            dirs = dirs,
            minValidSize = 512L
        )

        val targetFile = downloader.getCachedRootfsFile("cached-rootfs.tar.gz")
        targetFile.parentFile?.mkdirs()
        val dummyData = ByteArray(1024) { 0x42 }
        targetFile.writeBytes(dummyData)

        assertTrue(downloader.isRootfsCached("cached-rootfs.tar.gz"))

        var progressReported = false
        val result = downloader.downloadRootfs(
            mirrors = emptyList(), // No mirrors provided; should not be called
            targetFile = targetFile,
            onProgress = { progress ->
                progressReported = true
                assertEquals(100, progress.percent)
                assertTrue(progress.isComplete)
            }
        )

        assertEquals(targetFile.absolutePath, result.absolutePath)
        assertTrue(progressReported)
        assertArrayEquals(dummyData, result.readBytes())
    }

    @Test
    fun testDownloadSucceedsFromFirstMirror() = runBlocking {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        server?.handler = { path ->
            if (path == "/arch.tar.gz") {
                Pair(200, payload)
            } else {
                Pair(404, ByteArray(0))
            }
        }

        val targetFile = File(dirs.filesDir, "downloads/rootfs.tar.gz")
        val downloader = RootfsDownloader(
            dirs = dirs,
            minValidSize = 512L
        )

        val mirrorUrl = "http://127.0.0.1:${server!!.port}/arch.tar.gz"
        val progressList = mutableListOf<RootfsDownloader.DownloadProgress>()

        val downloaded = downloader.downloadRootfs(
            mirrors = listOf(mirrorUrl),
            targetFile = targetFile,
            onProgress = { progressList.add(it) }
        )

        assertTrue(downloaded.exists())
        assertEquals(2048L, downloaded.length())
        assertArrayEquals(payload, downloaded.readBytes())
        assertFalse(File(targetFile.parentFile, "${targetFile.name}.part").exists())
        assertTrue(progressList.isNotEmpty())
        assertTrue(progressList.last().isComplete)
    }

    @Test
    fun testMirrorFailoverWhenFirstMirrorFails() = runBlocking {
        val payload = ByteArray(1024) { 0x7F }
        server?.handler = { path ->
            when (path) {
                "/broken" -> Pair(500, ByteArray(0))
                "/backup.tar.gz" -> Pair(200, payload)
                else -> Pair(404, ByteArray(0))
            }
        }

        val targetFile = File(dirs.filesDir, "downloads/rootfs-failover.tar.gz")
        val downloader = RootfsDownloader(
            dirs = dirs,
            minValidSize = 512L
        )

        val mirrors = listOf(
            "http://127.0.0.1:${server!!.port}/broken",
            "http://127.0.0.1:${server!!.port}/backup.tar.gz"
        )

        val result = downloader.downloadRootfs(
            mirrors = mirrors,
            targetFile = targetFile
        )

        assertTrue(result.exists())
        assertArrayEquals(payload, result.readBytes())
    }

    @Test
    fun testRedirectFollowing() = runBlocking {
        val payload = ByteArray(1500) { 0x33 }
        server?.handler = { path ->
            when (path) {
                "/redirect-entry" -> Pair(302, "http://127.0.0.1:${server!!.port}/redirect-target.tar.gz".toByteArray())
                "/redirect-target.tar.gz" -> Pair(200, payload)
                else -> Pair(404, ByteArray(0))
            }
        }

        val targetFile = File(dirs.filesDir, "downloads/rootfs-redirect.tar.gz")
        val downloader = RootfsDownloader(
            dirs = dirs,
            minValidSize = 512L
        )

        val result = downloader.downloadRootfs(
            mirrors = listOf("http://127.0.0.1:${server!!.port}/redirect-entry"),
            targetFile = targetFile
        )

        assertTrue(result.exists())
        assertArrayEquals(payload, result.readBytes())
    }

    @Test
    fun testAllMirrorsFailThrowsIOException() = runBlocking {
        server?.handler = { _ -> Pair(404, ByteArray(0)) }

        val targetFile = File(dirs.filesDir, "downloads/rootfs-fail.tar.gz")
        val downloader = RootfsDownloader(
            dirs = dirs,
            minValidSize = 512L
        )

        try {
            downloader.downloadRootfs(
                mirrors = listOf("http://127.0.0.1:${server!!.port}/404"),
                targetFile = targetFile
            )
            fail("Expected IOException when all mirrors fail")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Failed to download rootfs from all mirrors") == true)
        }
    }
}
