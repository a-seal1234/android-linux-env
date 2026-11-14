package com.alw.workstation.ui.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alw.workstation.core.proot.RootfsManager.RootfsInfo
import com.alw.workstation.core.session.model.StackProfile
import java.io.File

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onLaunchSession: () -> Unit,
    onOpenWizard: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState)
        ) {
            // Dashboard Header
            DashboardTopBar(
                onOpenWizard = onOpenWizard,
                onRefresh = { viewModel.refresh() }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Quick Launch Hero Card
            ActiveProfileHeroCard(
                profile = state.activeProfile,
                onLaunch = {
                    viewModel.launchSession(scope)
                    onLaunchSession()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Workspaces Management Section
            WorkspacesSection(
                workspaces = state.workspaces,
                onCreate = { viewModel.openCreateDialog() },
                onClone = { viewModel.openCloneDialog(it) },
                onDelete = { viewModel.openDeleteDialog(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Stack Profiles Section
            StackProfilesSection(
                profiles = state.profiles,
                activeProfile = state.activeProfile,
                onSelectProfile = { viewModel.selectProfile(it) }
            )
        }
    }

    // Workspace Dialogs
    if (state.showCreateDialog) {
        CreateWorkspaceDialog(
            onDismiss = { viewModel.dismissCreateDialog() },
            onConfirm = { id, archive -> viewModel.createWorkspace(id, archive) }
        )
    }

    if (state.showCloneDialog && state.selectedWorkspace != null) {
        CloneWorkspaceDialog(
            source = state.selectedWorkspace!!,
            onDismiss = { viewModel.dismissCloneDialog() },
            onConfirm = { sourceId, targetId -> viewModel.cloneWorkspace(sourceId, targetId) }
        )
    }

    if (state.showDeleteDialog && state.selectedWorkspace != null) {
        DeleteWorkspaceDialog(
            workspace = state.selectedWorkspace!!,
            onDismiss = { viewModel.dismissDeleteDialog() },
            onConfirm = { viewModel.deleteWorkspace(it.id) }
        )
    }
}

@Composable
fun DashboardTopBar(
    onOpenWizard: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Workstation Dashboard",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Xiaomi Pad 8 · Snapdragon 8s Gen 4 · Adreno 825 (A8xx)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) {
                Text("Refresh")
            }
            Button(onClick = onOpenWizard) {
                Text("+ Setup Wizard")
            }
        }
    }
}

@Composable
fun ActiveProfileHeroCard(
    profile: StackProfile?,
    onLaunch: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Active Stack Profile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = profile?.name ?: "Arch Linux ARM (Hyprland + Turnip KGSL)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Launch Workstation", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (profile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoPill(label = "Compositor", value = "${profile.compositor.type}")
                    InfoPill(label = "GPU Acceleration", value = "${profile.gpu.driverMode}")
                    InfoPill(label = "Resolution", value = "${profile.display.customWidth ?: 3200}x${profile.display.customHeight ?: 2136} @ ${profile.display.refreshRateHz}Hz")
                    InfoPill(label = "Scale", value = "${profile.display.scaleFactor}x")
                }
            }
        }
    }
}

@Composable
fun InfoPill(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun WorkspacesSection(
    workspaces: List<RootfsInfo>,
    onCreate: () -> Unit,
    onClone: (RootfsInfo) -> Unit,
    onDelete: (RootfsInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Rootfs Workspaces (${workspaces.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = onCreate) {
                Text("+ Add Workspace")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (workspaces.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "No rootfs workspaces found. Run the Setup Wizard to provision your first workstation.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            workspaces.forEach { ws ->
                WorkspaceCard(
                    workspace = ws,
                    onClone = { onClone(ws) },
                    onDelete = { onDelete(ws) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun WorkspaceCard(
    workspace: RootfsInfo,
    onClone: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = workspace.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    if (workspace.isProvisioned) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Text(
                                text = "Provisioned",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val sizeMb = workspace.sizeBytes / (1024 * 1024)
                Text(
                    text = "Path: ${workspace.path.name} · Size: ${sizeMb} MB · Valid: ${workspace.isValid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClone) {
                    Text("Clone")
                }
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
fun StackProfilesSection(
    profiles: List<StackProfile>,
    activeProfile: StackProfile?,
    onSelectProfile: (StackProfile) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Stack Profiles (${profiles.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        profiles.forEach { p ->
            val isActive = activeProfile?.id == p.id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelectProfile(p) }
                    .border(
                        width = if (isActive) 1.5.dp else 0.5.dp,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp)
                    ),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = p.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Rootfs: ${p.distro.rootfsId} · Compositor: ${p.compositor.type} · Driver: ${p.gpu.driverMode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isActive) {
                        Text(text = "Active", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    } else {
                        TextButton(onClick = { onSelectProfile(p) }) {
                            Text("Select")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateWorkspaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, File?) -> Unit
) {
    var workspaceId by remember { mutableStateOf("") }
    var archivePath by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Workspace") },
        text = {
            Column {
                OutlinedTextField(
                    value = workspaceId,
                    onValueChange = { workspaceId = it },
                    label = { Text("Workspace ID (e.g. archlinux-dev)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = archivePath,
                    onValueChange = { archivePath = it },
                    label = { Text("Base Archive Path (optional .tar.xz)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val archive = if (archivePath.isNotBlank()) File(archivePath) else null
                    onConfirm(workspaceId.trim(), archive)
                },
                enabled = workspaceId.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CloneWorkspaceDialog(
    source: RootfsInfo,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var targetId by remember { mutableStateOf("${source.id}-clone") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone Workspace") },
        text = {
            Column {
                Text(text = "Cloning from: ${source.id}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetId,
                    onValueChange = { targetId = it },
                    label = { Text("New Target Workspace ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(source.id, targetId.trim()) },
                enabled = targetId.isNotBlank()
            ) {
                Text("Clone")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteWorkspaceDialog(
    workspace: RootfsInfo,
    onDismiss: () -> Unit,
    onConfirm: (RootfsInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Workspace") },
        text = {
            Text(
                "Are you sure you want to delete workspace '${workspace.name}' (${workspace.id})? All uncommitted data in this rootfs will be permanently deleted."
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(workspace) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
