package com.alw.workstation.ui.configurator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alw.workstation.core.session.model.StackProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackConfiguratorScreen(
    viewModel: StackConfiguratorViewModel,
    onBack: () -> Unit,
    onOpenDriverStore: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var newEnvKey by remember { mutableStateOf("") }
    var newEnvValue by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.isNewProfile) "Create StackProfile" else "Edit StackProfile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.profile.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text("Back")
                    }
                },
                actions = {
                    if (!state.isNewProfile) {
                        OutlinedButton(
                            onClick = { viewModel.duplicateProfile() },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Duplicate")
                        }
                        OutlinedButton(
                            onClick = {
                                if (viewModel.deleteProfile()) {
                                    onBack()
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Delete")
                        }
                    }
                    Button(
                        onClick = {
                            if (viewModel.saveProfile()) {
                                onBack()
                            }
                        }
                    ) {
                        Text("Save Profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Message Banner
            if (state.message != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (state.validationErrors.isNotEmpty()) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = state.message ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.validationErrors.isNotEmpty()) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Section 1: General & Distro
                ConfigSectionCard(title = "1. General & Workstation Identity") {
                    OutlinedTextField(
                        value = state.profile.name,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Profile Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Profile ID: ${state.profile.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Bound Rootfs Workspace",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.availableRootfs.forEach { rootfs ->
                            FilterChip(
                                selected = state.profile.distro.rootfsId == rootfs.id,
                                onClick = { viewModel.updateRootfsId(rootfs.id) },
                                label = { Text(rootfs.name) }
                            )
                        }
                        if (state.availableRootfs.isEmpty()) {
                            Text(
                                text = "Default: ${state.profile.distro.rootfsId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 2: Compositor Engine
                ConfigSectionCard(title = "2. Wayland Compositor Engine") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SelectableCard(
                            title = "Hyprland (Dynamic Tiling)",
                            subtitle = "Hardware-accelerated Wayland desktop with dynamic tiling, animations, and tablet-v2 support.",
                            selected = state.profile.compositor.type == StackProfile.CompositorType.HYPRLAND,
                            onClick = { viewModel.updateCompositor(StackProfile.CompositorType.HYPRLAND) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableCard(
                            title = "LabWC Safe Mode",
                            subtitle = "Stacking Openbox-like compositor. Guaranteed to launch even without GPU acceleration.",
                            selected = state.profile.compositor.type == StackProfile.CompositorType.LABWC,
                            onClick = { viewModel.updateCompositor(StackProfile.CompositorType.LABWC) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 3: GPU Driver & Adreno 825 Acceleration
                ConfigSectionCard(title = "3. GPU Acceleration & Mesa Turnip") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SelectableCard(
                            title = "TURNIP_KGSL",
                            subtitle = "Direct /dev/kgsl-3d0 Adreno 825 Vulkan acceleration via Mesa Turnip.",
                            selected = state.profile.gpu.driverMode == StackProfile.DriverMode.TURNIP_KGSL,
                            onClick = { viewModel.updateDriverMode(StackProfile.DriverMode.TURNIP_KGSL) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableCard(
                            title = "PROPRIETARY_ZINK",
                            subtitle = "Qualcomm libvulkan_adreno.so paired with Mesa Zink OpenGL bridge.",
                            selected = state.profile.gpu.driverMode == StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK,
                            onClick = { viewModel.updateDriverMode(StackProfile.DriverMode.ADRENO_PROPRIETARY_ZINK) },
                            modifier = Modifier.weight(1f)
                        )
                        SelectableCard(
                            title = "SOFTWARE_FALLBACK",
                            subtitle = "Pure CPU software rasterization (llvmpipe). Bypasses GPU completely.",
                            selected = state.profile.gpu.driverMode == StackProfile.DriverMode.LLVMPIPE,
                            onClick = { viewModel.updateDriverMode(StackProfile.DriverMode.LLVMPIPE) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Snapdragon 8s Gen 4 / Adreno 825 Fix (TU_DEBUG=noconform)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Bypasses strict Vulkan CTS conformance checks for early Adreno 825 Turnip builds.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.gpu.tuDebugNoConform || state.profile.gpu.environmentOverrides["TU_DEBUG"] == "noconform",
                            onCheckedChange = { viewModel.updateTuDebugNoconform(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Driver Version: ${state.profile.gpu.externalDriverId}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = onOpenDriverStore,
                            colors = ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text("Manage Driver Store")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 4: Display & HiDPI Scale
                ConfigSectionCard(title = "4. Display & HiDPI Scaling") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HiDPI Display Scale: ${"%.2f".format(state.profile.display.scaleFactor)}x",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.profile.display.scaleFactor == 1.75f,
                                onClick = { viewModel.updateScaleFactor(1.75f) },
                                label = { Text("1.75x (Xiaomi Pad 8)") }
                            )
                            FilterChip(
                                selected = state.profile.display.scaleFactor == 1.5f,
                                onClick = { viewModel.updateScaleFactor(1.5f) },
                                label = { Text("1.5x") }
                            )
                            FilterChip(
                                selected = state.profile.display.scaleFactor == 2.0f,
                                onClick = { viewModel.updateScaleFactor(2.0f) },
                                label = { Text("2.0x") }
                            )
                        }
                    }

                    Slider(
                        value = state.profile.display.scaleFactor,
                        onValueChange = { viewModel.updateScaleFactor(it) },
                        valueRange = 1.0f..2.5f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Display Refresh Rate Target",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(144, 120, 90, 60).forEach { rate ->
                            FilterChip(
                                selected = state.profile.display.refreshRateHz == rate,
                                onClick = { viewModel.updateRefreshRate(rate) },
                                label = { Text(if (rate == 144) "144 Hz (Native Mode 2)" else "$rate Hz") }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 5: Hardware Input
                ConfigSectionCard(title = "5. Hardware Input & Keyboard Remapping") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Remap CapsLock -> Super Key (caps:super)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Bypasses HyperOS Super key interception by translating physical CapsLock scancode 58 to Linux KEY_LEFTMETA.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.input.rebindCapsToSuper,
                            onCheckedChange = { viewModel.updateInputRebind(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Touchpad Natural Scrolling",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Content moves in the same direction as fingers on external touchpad.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.input.touchpadNaturalScroll,
                            onCheckedChange = { viewModel.updateNaturalScroll(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 6: Storage Bridge & Compilation Cache
                ConfigSectionCard(title = "6. Storage Bridge & High-Speed Build Cache") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Internal UFS Build Cache Redirection",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Redirects Cargo, UV, PNPM, Go, and ccache to internal high-speed UFS storage (~1500MB/s) inside guest ~/.cache/.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.storage.cacheRedirection.enabled,
                            onCheckedChange = { viewModel.updateBuildCacheRedirection(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mount Android Shared Storage (/storage/emulated/0)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Mounts into /mnt/sdcard with Scoped Storage isolation masking Android/data to prevent FUSE deadlocks.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.storage.bindSdcard,
                            onCheckedChange = { viewModel.updateMountSdcard(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 7: x86_64 Translation Hook (-q)
                ConfigSectionCard(title = "7. x86_64 Translation Hook (-q)") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                text = "Enable x86_64 Translation",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Transparently executes x86_64 ELFs via PRoot -q. Zero overhead when executing native aarch64 binaries.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.profile.emulation.enabled,
                            onCheckedChange = { viewModel.updateEmulationEnabled(it) }
                        )
                    }

                    if (state.profile.emulation.enabled) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Translation Engine",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SelectableCard(
                                title = "FEX-Emu (Recommended)",
                                subtitle = "Fast JIT translation with Turnip Vulkan/GL thunking.",
                                selected = state.profile.emulation.engine == StackProfile.EmulationEngine.FEX,
                                onClick = { viewModel.updateEmulationEngine(StackProfile.EmulationEngine.FEX) },
                                modifier = Modifier.weight(1f)
                            )
                            SelectableCard(
                                title = "box64",
                                subtitle = "ARM64 Dynarec for Linux x86_64 binaries.",
                                selected = state.profile.emulation.engine == StackProfile.EmulationEngine.BOX64,
                                onClick = { viewModel.updateEmulationEngine(StackProfile.EmulationEngine.BOX64) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (state.profile.emulation.engine == StackProfile.EmulationEngine.FEX) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(
                                        text = "Vulkan & GL Hardware Thunking",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Route guest x86_64 Vulkan/OpenGL calls into host Turnip Adreno 825 driver (/opt/drivers/turnip).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = state.profile.emulation.thunkLibsEnabled,
                                    onCheckedChange = { viewModel.updateEmulationThunkLibs(it) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.profile.emulation.overlayVersion,
                            onValueChange = { viewModel.updateEmulationOverlayVersion(it) },
                            label = { Text("Overlay Version") },
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("Active overlay directory in files/overlays/") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section 8: Custom Environment Variables
                ConfigSectionCard(title = "8. Custom Environment Variables") {
                    Text(
                        text = "Active Environment Variables (${state.profile.gpu.environmentOverrides.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    state.profile.gpu.environmentOverrides.forEach { (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$k = $v",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.removeEnvVar(k) },
                                colors = ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("Remove")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newEnvKey,
                            onValueChange = { newEnvKey = it },
                            label = { Text("Variable Name") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newEnvValue,
                            onValueChange = { newEnvValue = it },
                            label = { Text("Value") },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (newEnvKey.isNotBlank()) {
                                    viewModel.setEnvVar(newEnvKey, newEnvValue)
                                    newEnvKey = ""
                                    newEnvValue = ""
                                }
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ConfigSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SelectableCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
