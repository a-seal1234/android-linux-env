package com.alw.workstation.ui.terminal

/**
 * UI State for the interactive PRoot PTY Terminal Console.
 */
data class TerminalState(
    val isRunning: Boolean = false,
    val rootfsId: String = "archlinux-default",
    val rootfsValid: Boolean = false,
    val outputText: String = "",
    val errorMessage: String? = null,
    val isExtractingRootfs: Boolean = false,
    val extractionProgress: String? = null,
    val cols: Int = 100,
    val rows: Int = 30,
    val isCtrlActive: Boolean = false,
    val isAltActive: Boolean = false
)
