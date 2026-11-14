package com.alw.workstation.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alw.workstation.bridge.wayland.PtyBridge
import com.alw.workstation.bridge.wayland.PtySession
import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.proot.RootfsExtractor
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.session.ProfileRepository
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TerminalViewModel(
    private val dirs: RuntimeDirectories,
    private val rootfsManager: RootfsManager,
    private val rootfsExtractor: RootfsExtractor,
    private val prootRunner: ProotRunner,
    private val profileRepository: ProfileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(TerminalState())
    val uiState: StateFlow<TerminalState> = _uiState.asStateFlow()

    private var ptySession: PtySession? = null

    init {
        refreshRootfsStatus()
    }

    fun refreshRootfsStatus() {
        val rootfsId = _uiState.value.rootfsId
        val isValid = rootfsManager.isRootfsValid(rootfsId)
        _uiState.update { it.copy(rootfsValid = isValid) }
    }

    fun startSession(profile: StackProfile? = null) {
        if (_uiState.value.isRunning && ptySession != null) return

        val activeProfile = profile ?: profileRepository.getProfile("arch-xiaomi-pad-8")
        ?: StackProfile.defaultArchXiaomiPad8()
        val rootfsId = activeProfile.distro.rootfsId
        val rootfsDir = dirs.getRootfsDir(rootfsId)

        if (!rootfsManager.isRootfsValid(rootfsId)) {
            _uiState.update {
                it.copy(
                    rootfsValid = false,
                    errorMessage = "Rootfs '$rootfsId' is not provisioned or extracted yet. Please extract a base rootfs archive."
                )
            }
            return
        }

        viewModelScope.launch(ioDispatcher) {
            try {
                val runtimeDir = dirs.getRuntimeSocketDir("terminal-$rootfsId")
                if (!runtimeDir.exists()) runtimeDir.mkdirs()

                // Check shell binary inside guest: bash first, sh fallback
                val bashFile = File(rootfsDir, "bin/bash")
                val usrBashFile = File(rootfsDir, "usr/bin/bash")
                val shellBinary = if (bashFile.exists() || usrBashFile.exists()) "/bin/bash" else "/bin/sh"

                val launchCmd = prootRunner.buildLaunchCommand(
                    profile = activeProfile,
                    sessionRuntimeDir = runtimeDir,
                    customExecCommand = shellBinary,
                    customArgs = listOf("-l")
                )

                val cols = _uiState.value.cols
                val rows = _uiState.value.rows

                val result = PtyBridge.forkSubprocess(
                    command = launchCmd.binary,
                    args = launchCmd.arguments,
                    env = launchCmd.environment,
                    cwd = launchCmd.workingDir.absolutePath,
                    cols = cols,
                    rows = rows
                )

                if (result == null) {
                    withContext(mainDispatcher) {
                        _uiState.update { it.copy(errorMessage = "Failed to fork PTY subprocess") }
                    }
                    return@launch
                }

                val session = PtySession(
                    pid = result.pid,
                    masterFd = result.masterFd,
                    scope = viewModelScope,
                    ioDispatcher = ioDispatcher
                )
                ptySession = session

                withContext(mainDispatcher) {
                    _uiState.update {
                        it.copy(
                            isRunning = true,
                            errorMessage = null,
                            outputText = it.outputText + "\n[Workstation PTY attached - pid ${result.pid}]\n"
                        )
                    }
                }

                // Collect terminal output stream
                session.outputFlow.collect { chunk ->
                    withContext(mainDispatcher) {
                        _uiState.update { current ->
                            val updated = current.outputText + chunk
                            // Cap buffer size at 50,000 characters to keep Compose responsive
                            val capped = if (updated.length > 50_000) updated.takeLast(40_000) else updated
                            current.copy(outputText = capped)
                        }
                    }
                }
            } catch (e: Throwable) {
                withContext(mainDispatcher) {
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = "Terminal launch error: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    fun sendInput(text: String) {
        ptySession?.write(text)
    }

    fun sendRawBytes(bytes: ByteArray) {
        ptySession?.write(bytes)
    }

    fun sendCtrlKey(char: Char) {
        val code = char.uppercaseChar().code - 64
        if (code in 1..26) {
            ptySession?.write(byteArrayOf(code.toByte()))
        }
        _uiState.update { it.copy(isCtrlActive = false) }
    }

    fun toggleCtrl() {
        _uiState.update { it.copy(isCtrlActive = !it.isCtrlActive) }
    }

    fun toggleAlt() {
        _uiState.update { it.copy(isAltActive = !it.isAltActive) }
    }

    fun resize(cols: Int, rows: Int) {
        _uiState.update { it.copy(cols = cols, rows = rows) }
        ptySession?.resize(cols, rows)
    }

    fun clearScreen() {
        _uiState.update { it.copy(outputText = "") }
    }

    fun extractArchive(archiveFile: File) {
        val rootfsId = _uiState.value.rootfsId
        val rootfsDir = dirs.getRootfsDir(rootfsId)
        if (!rootfsDir.exists()) rootfsDir.mkdirs()

        _uiState.update {
            it.copy(
                isExtractingRootfs = true,
                extractionProgress = "Starting extraction of ${archiveFile.name}..."
            )
        }

        viewModelScope.launch(ioDispatcher) {
            val result = rootfsExtractor.extractArchiveFile(
                archiveFile = archiveFile,
                targetDir = rootfsDir,
                onProgress = { progress ->
                    val desc = "Extracting ${archiveFile.name}: ${progress.entriesExtracted} entries"
                    _uiState.update { it.copy(extractionProgress = desc) }
                }
            )

            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(
                        isExtractingRootfs = false,
                        extractionProgress = null
                    )
                }
                if (result.isSuccess) {
                    rootfsManager.markProvisioned(rootfsId)
                    refreshRootfsStatus()
                    startSession()
                } else {
                    _uiState.update {
                        it.copy(errorMessage = "Extraction failed: ${result.errorMessage}")
                    }
                }
            }
        }
    }

    fun stopSession() {
        ptySession?.close()
        ptySession = null
        _uiState.update {
            it.copy(
                isRunning = false,
                outputText = it.outputText + "\n[Workstation PTY session ended]\n"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }
}
