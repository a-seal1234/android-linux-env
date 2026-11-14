package com.alw.workstation.ui.terminal

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
    onOpenWizard: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var inputBuffer by remember { mutableStateOf("") }

    // Auto-scroll to bottom on new output
    LaunchedEffect(state.outputText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Auto-start session if rootfs is valid and not running
    LaunchedEffect(state.rootfsValid) {
        if (state.rootfsValid && !state.isRunning) {
            viewModel.startSession()
        }
    }

    // Auto-focus to receive hardware key events
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

                val isCtrl = keyEvent.isCtrlPressed || state.isCtrlActive
                val isAlt = keyEvent.isAltPressed || state.isAltActive

                when (keyEvent.key) {
                    Key.Enter -> {
                        viewModel.sendInput("\r")
                        true
                    }
                    Key.Backspace -> {
                        viewModel.sendRawBytes(byteArrayOf(127))
                        true
                    }
                    Key.Tab -> {
                        viewModel.sendInput("\t")
                        true
                    }
                    Key.Escape -> {
                        viewModel.sendRawBytes(byteArrayOf(27))
                        true
                    }
                    Key.DirectionUp -> {
                        viewModel.sendInput("\u001b[A")
                        true
                    }
                    Key.DirectionDown -> {
                        viewModel.sendInput("\u001b[B")
                        true
                    }
                    Key.DirectionRight -> {
                        viewModel.sendInput("\u001b[C")
                        true
                    }
                    Key.DirectionLeft -> {
                        viewModel.sendInput("\u001b[D")
                        true
                    }
                    Key.C -> {
                        if (isCtrl) {
                            viewModel.sendCtrlKey('C')
                            true
                        } else false
                    }
                    Key.D -> {
                        if (isCtrl) {
                            viewModel.sendCtrlKey('D')
                            true
                        } else false
                    }
                    Key.Z -> {
                        if (isCtrl) {
                            viewModel.sendCtrlKey('Z')
                            true
                        } else false
                    }
                    Key.L -> {
                        if (isCtrl) {
                            viewModel.sendCtrlKey('L')
                            true
                        } else false
                    }
                    else -> {
                        val codePoint = keyEvent.utf16CodePoint
                        if (codePoint > 0) {
                            val char = codePoint.toChar()
                            if (isCtrl && char.isLetter()) {
                                viewModel.sendCtrlKey(char)
                            } else {
                                viewModel.sendInput(char.toString())
                            }
                            true
                        } else false
                    }
                }
            }
    ) {
        // --- Top Header Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Dashboard")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Linux Console (PRoot /bin/bash)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (state.isRunning) "PTY Active · /dev/pts" else "PTY Stopped",
                    color = if (state.isRunning) Color(0xFF3FB950) else Color(0xFF8B949E),
                    fontSize = 12.sp
                )
            }

            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.clearScreen() },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Clear")
                }
                Button(
                    onClick = { viewModel.stopSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDA3633)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Exit Shell")
                }
            } else {
                Button(
                    onClick = { viewModel.startSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Start Shell")
                }
            }
        }

        // --- Main Terminal Content Area ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                }
        ) {
            if (!state.rootfsValid) {
                // Rootfs Unprovisioned Prompt
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Guest Rootfs Not Extracted",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No Linux binaries found in ${state.rootfsId}. Extract a rootfs archive or use Setup Wizard.",
                                color = Color(0xFF8B949E),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            val tmpArchive = File("/data/local/tmp/rootfs.tar.gz")
                            if (tmpArchive.exists()) {
                                Button(
                                    onClick = { viewModel.extractArchive(tmpArchive) },
                                    enabled = !state.isExtractingRootfs,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636))
                                ) {
                                    if (state.isExtractingRootfs) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(state.extractionProgress ?: "Extracting...")
                                    } else {
                                        Text("Extract /data/local/tmp/rootfs.tar.gz (${tmpArchive.length() / 1024 / 1024} MB)")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            OutlinedButton(onClick = onOpenWizard) {
                                Text("Open Setup Wizard")
                            }
                        }
                    }
                }
            } else {
                // Active Terminal Text Buffer
                Text(
                    text = state.outputText.ifEmpty { "Workstation PTY ready. Tap here or use keyboard..." },
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                )
            }

            // Hidden BasicTextField to summon software keyboard when tapped
            BasicTextField(
                value = inputBuffer,
                onValueChange = { newVal ->
                    if (newVal.length > inputBuffer.length) {
                        val typed = newVal.substring(inputBuffer.length)
                        viewModel.sendInput(typed)
                    }
                    inputBuffer = newVal
                    if (inputBuffer.length > 50) inputBuffer = ""
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester)
            )
        }

        // --- Bottom Virtual Keys Bar (Tablet Optimized) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VirtualKeyButton("ESC") { viewModel.sendRawBytes(byteArrayOf(27)) }
            VirtualKeyButton("TAB") { viewModel.sendInput("\t") }

            VirtualKeyButton(
                label = "CTRL",
                isActive = state.isCtrlActive
            ) { viewModel.toggleCtrl() }

            VirtualKeyButton(
                label = "ALT",
                isActive = state.isAltActive
            ) { viewModel.toggleAlt() }

            VirtualKeyButton("Ctrl+C") { viewModel.sendCtrlKey('C') }
            VirtualKeyButton("Ctrl+D") { viewModel.sendCtrlKey('D') }
            VirtualKeyButton("Ctrl+Z") { viewModel.sendCtrlKey('Z') }
            VirtualKeyButton("Ctrl+L") { viewModel.sendCtrlKey('L') }

            VirtualKeyButton("↑") { viewModel.sendInput("\u001b[A") }
            VirtualKeyButton("↓") { viewModel.sendInput("\u001b[B") }
            VirtualKeyButton("←") { viewModel.sendInput("\u001b[D") }
            VirtualKeyButton("→") { viewModel.sendInput("\u001b[C") }

            VirtualKeyButton("|") { viewModel.sendInput("|") }
            VirtualKeyButton("/") { viewModel.sendInput("/") }
            VirtualKeyButton("-") { viewModel.sendInput("-") }
            VirtualKeyButton("~") { viewModel.sendInput("~") }
        }
    }
}

@Composable
private fun VirtualKeyButton(
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) Color(0xFF1F6FEB) else Color(0xFF21262D),
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                color = if (isActive) Color.White else Color(0xFFC9D1D9),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
