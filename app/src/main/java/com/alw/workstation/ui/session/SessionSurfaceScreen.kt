package com.alw.workstation.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.alw.workstation.bridge.wayland.InputDispatcher
import com.alw.workstation.bridge.wayland.WaylandServerManager
import com.alw.workstation.bridge.wayland.WaylandSurfaceView
import com.alw.workstation.ui.dashboard.DashboardViewModel

@Composable
fun SessionSurfaceScreen(
    dashboardViewModel: DashboardViewModel,
    serverManager: WaylandServerManager,
    inputDispatcher: InputDispatcher,
    onStopSession: () -> Unit
) {
    val state by dashboardViewModel.uiState.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Native Embedded Wayland SurfaceView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WaylandSurfaceView(context).apply {
                    this.serverManager = serverManager
                    this.inputDispatcher = inputDispatcher
                    this.inputProfile = state.inputProfile
                }
            },
            update = { surfaceView ->
                if (surfaceView.inputProfile != state.inputProfile) {
                    surfaceView.inputProfile = state.inputProfile
                }
            }
        )

        var renderScaleIndex by remember { mutableIntStateOf(1) }
        var ctrlLatched by remember { mutableStateOf(false) }
        val renderScales = remember { listOf(0.5f, 0.75f, 0.85f, 1.0f) }

        // Collapsible Floating Control Pill Overlay (hosted in Popup to float above SurfaceView)
        Popup(
            alignment = Alignment.TopEnd,
            properties = PopupProperties(focusable = false)
        ) {
            FloatingControlPill(
                inputProfile = state.inputProfile,
                audioStatus = state.audioStatus,
                sessionState = state.sessionState,
                onSwitchInputProfile = { newProfile ->
                    dashboardViewModel.switchInputProfile(newProfile)
                },
                onSetVolume = { vol ->
                    dashboardViewModel.audioBridgeManager.setVolume(vol)
                },
                onReloadHyprland = {
                    dashboardViewModel.sendHyprlandIpc("reload", scope)
                },
                onRegenerateDotfiles = {
                    dashboardViewModel.regenerateDefaultDotfiles(scope)
                },
                onToggleSafeMode = {
                    dashboardViewModel.triggerSafeMode()
                },
                onStopSession = {
                    dashboardViewModel.stopSession()
                    onStopSession()
                },
                renderScale = renderScales[renderScaleIndex],
                onCycleRenderScale = {
                    renderScaleIndex = (renderScaleIndex + 1) % renderScales.size
                    serverManager.setRenderScale(renderScales[renderScaleIndex])
                },
                ctrlLatched = ctrlLatched,
                onToggleCtrl = {
                    ctrlLatched = !ctrlLatched
                    // KEY_LEFTCTRL (29) with META_CTRL_ON when latched.
                    serverManager.sendKey(29, ctrlLatched, if (ctrlLatched) 0x1000 else 0)
                },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}
