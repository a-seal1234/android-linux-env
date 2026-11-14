package com.alw.workstation.bridge.wayland

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages an active PTY session with asynchronous reading/writing,
 * lifecycle management, window resizing, and coroutine stream pumping.
 */
class PtySession(
    val pid: Int,
    val masterFd: Int,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    customInputStream: InputStream? = null,
    customOutputStream: OutputStream? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "ALW::PtySession"
        private const val BUFFER_SIZE = 4096
    }

    private val isClosed = AtomicBoolean(false)
    private var readJob: Job? = null

    private val pfd: ParcelFileDescriptor? = try {
        if (masterFd >= 0) ParcelFileDescriptor.adoptFd(masterFd) else null
    } catch (e: Throwable) {
        null
    }

    private val inputStream: InputStream = customInputStream
        ?: (pfd?.let { FileInputStream(it.fileDescriptor) } ?: InputStream.nullInputStream())

    private val outputStream: OutputStream = customOutputStream
        ?: (pfd?.let { FileOutputStream(it.fileDescriptor) } ?: OutputStream.nullOutputStream())

    private val _outputFlow = MutableSharedFlow<String>(
        replay = 200,
        extraBufferCapacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    init {
        startReading()
    }

    private fun startReading() {
        readJob = scope.launch(ioDispatcher) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isActive && !isClosed.get()) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    val text = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
                    _outputFlow.emit(text)
                }
            } catch (e: Throwable) {
                if (!isClosed.get()) {
                    Log.d(TAG, "PTY stream closed: ${e.message}")
                }
            }
        }
    }

    /**
     * Sends input characters or escape sequences to the PTY stdin.
     */
    fun write(text: String) {
        if (isClosed.get()) return
        scope.launch(ioDispatcher) {
            try {
                outputStream.write(text.toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed writing to PTY: ${e.message}")
            }
        }
    }

    /**
     * Sends raw bytes to the PTY stdin (useful for special control keys).
     */
    fun write(bytes: ByteArray) {
        if (isClosed.get()) return
        scope.launch(ioDispatcher) {
            try {
                outputStream.write(bytes)
                outputStream.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed writing bytes to PTY: ${e.message}")
            }
        }
    }

    /**
     * Resizes the terminal window (rows and columns).
     */
    fun resize(cols: Int, rows: Int): Boolean {
        if (isClosed.get()) return false
        return PtyBridge.setWindowSize(masterFd, cols, rows)
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        readJob?.cancel()
        try {
            inputStream.close()
        } catch (_: Throwable) {}
        try {
            outputStream.close()
        } catch (_: Throwable) {}
        try {
            pfd?.close()
        } catch (_: Throwable) {}
        PtyBridge.closeFd(masterFd)
    }
}
