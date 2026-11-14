package com.alw.workstation.core.proot

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle of spawned PRoot processes, IO stream pumps,
 * signal termination, and asynchronous execution with coroutines.
 */
class ProotProcessManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "ALW::ProotProcessMgr"
    }

    data class ExecutionResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>
    )

    interface ProcessListener {
        fun onStdoutLine(line: String) {}
        fun onStderrLine(line: String) {}
        fun onProcessExited(exitCode: Int) {}
    }

    class ManagedProcess(
        val process: Process,
        val command: ProotRunner.LaunchCommand
    ) {
        val isAlive: Boolean get() = process.isAlive

        private val _stdoutFlow = MutableSharedFlow<String>(
            replay = 100,
            extraBufferCapacity = 500,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val stdoutFlow: SharedFlow<String> = _stdoutFlow.asSharedFlow()

        private val _stderrFlow = MutableSharedFlow<String>(
            replay = 100,
            extraBufferCapacity = 500,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val stderrFlow: SharedFlow<String> = _stderrFlow.asSharedFlow()

        fun pid(): Long? {
            return try {
                val method = process.javaClass.getMethod("pid")
                (method.invoke(process) as? Number)?.toLong()
            } catch (e: Throwable) {
                try {
                    val field = process.javaClass.getDeclaredField("pid")
                    field.isAccessible = true
                    (field.get(process) as? Number)?.toLong()
                } catch (err: Throwable) {
                    null
                }
            }
        }

        fun writeInput(text: String) {
            try {
                val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
                writer.write(text)
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing input to process: ${e.message}")
            }
        }

        fun terminate(gracePeriodMs: Long = 2000): Int {
            if (!process.isAlive) {
                return try {
                    process.exitValue()
                } catch (e: Exception) {
                    0
                }
            }

            process.destroy()
            val finished = try {
                process.waitFor(gracePeriodMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }

            if (!finished && process.isAlive) {
                process.destroyForcibly()
                try {
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }

            return try {
                process.exitValue()
            } catch (e: Exception) {
                -1
            }
        }

        internal fun emitStdout(line: String) {
            _stdoutFlow.tryEmit(line)
        }

        internal fun emitStderr(line: String) {
            _stderrFlow.tryEmit(line)
        }
    }

    /**
     * Starts a PRoot process asynchronously and pumps stdout/stderr to listeners and flows.
     */
    fun startProcess(
        command: ProotRunner.LaunchCommand,
        listener: ProcessListener? = null
    ): ManagedProcess {
        val fullCmd = mutableListOf<String>()
        fullCmd.add(command.binary)
        fullCmd.addAll(command.arguments)

        val processBuilder = ProcessBuilder(fullCmd)
        if (command.workingDir.exists()) {
            processBuilder.directory(command.workingDir)
        }

        val procEnv = processBuilder.environment()
        for ((k, v) in command.environment) {
            procEnv[k] = v
        }

        val process = processBuilder.start()
        val managed = ManagedProcess(process, command)

        // Asynchronous stdout pump thread
        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        managed.emitStdout(line)
                        listener?.onStdoutLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Stdout reader ended: ${e.message}")
            }
        }, "ProotStdoutPump-${managed.pid() ?: "unknown"}").start()

        // Asynchronous stderr pump thread
        Thread({
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        managed.emitStderr(line)
                        listener?.onStderrLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Stderr reader ended: ${e.message}")
            }
        }, "ProotStderrPump-${managed.pid() ?: "unknown"}").start()

        // Asynchronous completion watcher
        Thread({
            try {
                val exitCode = process.waitFor()
                listener?.onProcessExited(exitCode)
            } catch (e: InterruptedException) {
                listener?.onProcessExited(-1)
            }
        }, "ProotWaitPump-${managed.pid() ?: "unknown"}").start()

        return managed
    }

    /**
     * Suspends while executing a command to completion, capturing all stdout and stderr.
     */
    suspend fun executeCommand(
        command: ProotRunner.LaunchCommand,
        timeoutMs: Long = 60_000,
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null
    ): ExecutionResult = withContext(ioDispatcher) {
        val stdoutLines = CopyOnWriteArrayList<String>()
        val stderrLines = CopyOnWriteArrayList<String>()

        val listener = object : ProcessListener {
            override fun onStdoutLine(line: String) {
                stdoutLines.add(line)
                onStdoutLine?.invoke(line)
            }

            override fun onStderrLine(line: String) {
                stderrLines.add(line)
                onStderrLine?.invoke(line)
            }
        }

        val managed = startProcess(command, listener)

        val completedExitCode = withTimeoutOrNull(timeoutMs) {
            try {
                managed.process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
        }

        val exitCode = if (completedExitCode != null) {
            completedExitCode
        } else {
            Log.w(TAG, "Command execution timed out after ${timeoutMs}ms, terminating process")
            managed.terminate()
            -1
        }

        ExecutionResult(
            exitCode = exitCode,
            stdout = stdoutLines.toList(),
            stderr = stderrLines.toList()
        )
    }
}
