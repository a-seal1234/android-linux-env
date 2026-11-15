package com.alw.workstation.ui.wizard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.alw.workstation.core.proot.oci.OciDistroCatalog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun WizardScreen(
    viewModel: WizardViewModel,
    onComplete: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Header & Target Hardware Badge
            WizardHeader(currentStep = state.step)

            Spacer(modifier = Modifier.height(16.dp))

            // Step Indicator Stepper
            WizardStepIndicator(currentStep = state.step)

            Spacer(modifier = Modifier.height(20.dp))

            // Step Content
            Box(modifier = Modifier.weight(1f)) {
                when (state.step) {
                    WizardStep.DISTRO_PROFILE -> {
                        Step1DistroAndProfile(
                            state = state,
                            onPresetSelected = { viewModel.selectPreset(it) },
                            onSourceSelected = { type, path -> viewModel.setRootfsSource(type, path) },
                            onOciDistroSelected = { viewModel.selectOciDistro(it) },
                            onOciTagSelected = { viewModel.selectOciTag(it) },
                            onCustomOciChanged = { viewModel.setCustomOciImage(it) },
                            onFetchLiveTags = { viewModel.fetchLiveOciTags(scope) },
                            onUsernameChanged = { viewModel.setUsername(it) },
                            onScaleChanged = { viewModel.setScaleFactor(it) }
                        )
                    }
                    WizardStep.DRIVER_STORE -> {
                        Step2DriverStore(
                            state = state,
                            onOptionSelected = { viewModel.setDriverOption(it) },
                            onReleaseSelected = { viewModel.selectRelease(it) },
                            onLocalDriverSelected = { viewModel.selectLocalDriver(it) }
                        )
                    }
                    WizardStep.PROVISIONING, WizardStep.COMPLETED -> {
                        Step3Provisioning(
                            state = state,
                            onStartProvisioning = { viewModel.startProvisioning(scope) },
                            onRetry = { viewModel.retryProvisioning(scope) },
                            onFinish = onComplete
                        )
                    }
                }
            }

            // Navigation Buttons (only for step 1 & 2)
            if (state.step == WizardStep.DISTRO_PROFILE || state.step == WizardStep.DRIVER_STORE) {
                WizardNavigationButtons(
                    currentStep = state.step,
                    onBack = { viewModel.previousStep() },
                    onNext = { viewModel.nextStep() }
                )
            }
        }
    }
}

@Composable
fun WizardHeader(currentStep: WizardStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Android Linux Workstation",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Zero-Terminal One-Tap Setup",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Hardware target pill
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = "Xiaomi Pad 8 · Adreno 825",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun WizardStepIndicator(currentStep: WizardStep) {
    val steps = listOf(
        WizardStep.DISTRO_PROFILE to "1. Distro & Profile",
        WizardStep.DRIVER_STORE to "2. Turnip KGSL GPU",
        WizardStep.PROVISIONING to "3. Provisioning"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, (step, label) ->
            val isActive = currentStep == step || (step == WizardStep.PROVISIONING && currentStep == WizardStep.COMPLETED)
            val isPassed = currentStep.ordinal > step.ordinal

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isActive -> MaterialTheme.colorScheme.primary
                                isPassed -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun Step1DistroAndProfile(
    state: WizardUiState,
    onPresetSelected: (WizardPreset) -> Unit,
    onSourceSelected: (RootfsSourceType, String?) -> Unit,
    onOciDistroSelected: (String) -> Unit,
    onOciTagSelected: (String) -> Unit,
    onCustomOciChanged: (String) -> Unit,
    onFetchLiveTags: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onScaleChanged: (Float) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "Step 1: Choose Workstation Rootfs & Preset",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Select an official Linux rootfs distribution and your workflow profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Rootfs Source Selection ---
        Text(
            text = "Distribution Source",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.rootfsSource == RootfsSourceType.DEFAULT_ARCH_ARM,
                onClick = { onSourceSelected(RootfsSourceType.DEFAULT_ARCH_ARM, null) },
                label = { Text("Arch Linux ARM") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = state.rootfsSource == RootfsSourceType.OCI_REGISTRY,
                onClick = { onSourceSelected(RootfsSourceType.OCI_REGISTRY, null) },
                label = { Text("OCI Registry Catalog") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = state.rootfsSource == RootfsSourceType.LOCAL_ARCHIVE,
                onClick = { onSourceSelected(RootfsSourceType.LOCAL_ARCHIVE, "/data/local/tmp/rootfs.tar.gz") },
                label = { Text("Local Archive") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // OCI Registry Catalog Browser
        if (state.rootfsSource == RootfsSourceType.OCI_REGISTRY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Curated Linux Distributions (arm64)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OciDistroCatalog.ENTRIES.take(4).forEach { distro ->
                            FilterChip(
                                selected = state.selectedOciDistroId == distro.id,
                                onClick = { onOciDistroSelected(distro.id) },
                                label = { Text(distro.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OciDistroCatalog.ENTRIES.drop(4).forEach { distro ->
                            FilterChip(
                                selected = state.selectedOciDistroId == distro.id,
                                onClick = { onOciDistroSelected(distro.id) },
                                label = { Text(distro.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Selected Distro Tags
                    val curDistro = OciDistroCatalog.findById(state.selectedOciDistroId)
                    if (curDistro != null) {
                        Text(
                            text = "${curDistro.name} (${curDistro.repository}) Tags:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            state.availableOciTags.take(4).forEach { tag ->
                                FilterChip(
                                    selected = state.selectedOciTag == tag,
                                    onClick = { onOciTagSelected(tag) },
                                    label = { Text(tag) }
                                )
                            }

                            OutlinedButton(
                                onClick = onFetchLiveTags,
                                enabled = !state.isFetchingOciTags
                            ) {
                                if (state.isFetchingOciTags) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Query All Tags")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = state.customOciImage,
                        onValueChange = onCustomOciChanged,
                        label = { Text("Or Enter Custom OCI Image (e.g. library/ubuntu:noble)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Workstation Presets ---
        Text(
            text = "Workstation Preset",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Preset 1: Creative Workstation
        PresetCard(
            title = "Creative Workstation (Recommended)",
            description = "Hyprland Wayland compositor + zwp_tablet_tool_v2 pen engine active. Tuned for Krita, Blender, Inkscape on 144Hz panel.",
            isSelected = state.selectedPreset == WizardPreset.CREATIVE_WORKSTATION,
            onClick = { onPresetSelected(WizardPreset.CREATIVE_WORKSTATION) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Preset 2: Minimal Developer
        PresetCard(
            title = "Minimal Developer",
            description = "Lightweight Hyprland + Kitty terminal environment with high-speed UFS build cache redirection for Rust/Node/Go.",
            isSelected = state.selectedPreset == WizardPreset.MINIMAL_DEV,
            onClick = { onPresetSelected(WizardPreset.MINIMAL_DEV) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Preset 3: Safe Mode
        PresetCard(
            title = "Safe Mode (LabWC + llvmpipe)",
            description = "Failsafe stacking compositor using CPU software rasterization. Guaranteed to boot if hardware GPU fails.",
            isSelected = state.selectedPreset == WizardPreset.SAFE_MODE,
            onClick = { onPresetSelected(WizardPreset.SAFE_MODE) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Username and Scale factor
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChanged,
                label = { Text("Linux Default User") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HiDPI Display Scale: ${String.format("%.2f", state.scaleFactor)}x (Xiaomi Pad 8: 1.75x)",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = state.scaleFactor,
                    onValueChange = onScaleChanged,
                    valueRange = 1.0f..2.5f,
                    steps = 5
                )
            }
        }
    }
}

@Composable
fun PresetCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Step2DriverStore(
    state: WizardUiState,
    onOptionSelected: (DriverStoreOption) -> Unit,
    onReleaseSelected: (String) -> Unit,
    onLocalDriverSelected: (File) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        Text(
            text = "Step 2: Adreno 825 Turnip KGSL Driver Store",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Direct user-space Vulkan acceleration via /dev/kgsl-3d0. Verified against glibc aarch64 ABI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Option A: Curated Online Mesa Turnip releases
        DriverOptionCard(
            title = "Mesa 25.0+ Turnip KGSL (Recommended)",
            description = "Download and activate curated Turnip release for Snapdragon 8s Gen 4 (Adreno 825). Includes SHA256 verification and glibc ELF validation.",
            isSelected = state.driverOption == DriverStoreOption.ONLINE_RELEASE,
            onClick = { onOptionSelected(DriverStoreOption.ONLINE_RELEASE) }
        ) {
            if (state.driverOption == DriverStoreOption.ONLINE_RELEASE) {
                Spacer(modifier = Modifier.height(12.dp))
                state.availableReleases.forEach { release ->
                    val isReleaseSelected = state.selectedReleaseTag == release.releaseTag
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onReleaseSelected(release.releaseTag) }
                            .border(
                                width = if (isReleaseSelected) 1.5.dp else 0.5.dp,
                                color = if (isReleaseSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = release.version, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                if (release.isRecommended) {
                                    Text(text = "Recommended", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text(text = release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = "SHA256: ${release.sha256Checksum.take(16)}...", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Option B: Software Rasterizer Fallback
        DriverOptionCard(
            title = "Software Fallback (llvmpipe)",
            description = "Skip external GPU driver setup. Runs pure CPU rasterization for initial debugging or troubleshooting.",
            isSelected = state.driverOption == DriverStoreOption.SOFTWARE_FALLBACK,
            onClick = { onOptionSelected(DriverStoreOption.SOFTWARE_FALLBACK) }
        )
    }
}

@Composable
fun DriverOptionCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
fun Step3Provisioning(
    state: WizardUiState,
    onStartProvisioning: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.provisioningLogs.size) {
        if (state.provisioningLogs.isNotEmpty()) {
            listState.animateScrollToItem(state.provisioningLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Step 3: Automated Rootfs Provisioning",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Executing non-interactive setup: pacman keyrings, core packages, sudoers, and Wayland bridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Progress Bar & Step Milestone
        val progressFraction = when {
            state.isComplete -> 1f
            state.isDownloadingRootfs -> (state.downloadProgressFraction * 0.5f).coerceIn(0f, 0.5f)
            state.isExtractingRootfs -> (0.5f + state.extractionProgressFraction * 0.5f).coerceIn(0.5f, 1f)
            state.provisioningTotalSteps > 0 -> (state.provisioningStep.toFloat() / state.provisioningTotalSteps.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

        LinearProgressIndicator(
            progress = { if (state.isComplete) 1f else progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Step ${state.provisioningStep}/${state.provisioningTotalSteps}: ${state.provisioningStepDescription}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${(progressFraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live Terminal Log View
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                if (state.provisioningLogs.isEmpty()) {
                    item {
                        Text(
                            text = if (state.isProvisioning) "Connecting to PRoot environment..." else "Ready to provision. Tap 'Start Provisioning' below.",
                            color = Color(0xFF888888),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    items(state.provisioningLogs) { logLine ->
                        val isStep = logLine.startsWith("==>")
                        val isWarn = logLine.contains("warning", ignoreCase = true) || logLine.contains("error", ignoreCase = true)
                        Text(
                            text = logLine,
                            color = when {
                                isStep -> Color(0xFF00FF66) // Green
                                isWarn -> Color(0xFFFFCC00) // Amber
                                else -> Color(0xFFCCCCCC)   // Gray
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        if (state.isComplete) {
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Launch Workstation Dashboard", fontWeight = FontWeight.Bold)
            }
        } else if (state.provisioningError != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Error: ${state.provisioningError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Retry")
                }
            }
        } else if (!state.isProvisioning) {
            Button(
                onClick = onStartProvisioning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Provisioning", fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Provisioning in progress...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun WizardNavigationButtons(
    currentStep: WizardStep,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (currentStep != WizardStep.DISTRO_PROFILE) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }

        Button(onClick = onNext) {
            Text(if (currentStep == WizardStep.DRIVER_STORE) "Proceed to Provisioning" else "Next")
        }
    }
}
