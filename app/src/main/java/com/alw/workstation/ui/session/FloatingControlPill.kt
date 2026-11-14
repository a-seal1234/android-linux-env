package com.alw.workstation.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alw.workstation.bridge.wayland.audio.AudioStatus
import com.alw.workstation.bridge.wayland.input.InputProfile
import com.alw.workstation.core.session.SessionState

@Composable
fun FloatingControlPill(
    inputProfile: InputProfile,
    audioStatus: AudioStatus,
    sessionState: SessionState,
    onSwitchInputProfile: (InputProfile) -> Unit,
    onSetVolume: (Float) -> Unit,
    onStopSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(1.0f) }

    val isSafeMode = sessionState is SessionState.SafeModeFallback
    val statusColor = if (isSafeMode) Color(0xFFFFB300) else Color(0xFF00E676)

    Box(modifier = modifier) {
        if (!isExpanded) {
            // Collapsed Compact Pill
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { isExpanded = true }
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                color = Color.Black.copy(alpha = 0.75f),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = "ALW",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "·",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Text(
                        text = when (inputProfile) {
                            InputProfile.CREATIVE_PEN -> "PEN"
                            InputProfile.GAMING_RAW -> "RAW"
                        },
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "·",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${String.format("%.1f", audioStatus.latencyMs)}ms",
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            // Expanded Control Sheet
            Card(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E1E)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Workstation Controls",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Text(
                            text = "Collapse",
                            color = Color(0xFF80D8FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { isExpanded = false }
                                .padding(4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Safe Mode Notice
                    if (isSafeMode) {
                        val fallback = sessionState as SessionState.SafeModeFallback
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0x44FFB300)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Safe Mode Active",
                                    color = Color(0xFFFFB300),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = fallback.reason,
                                    color = Color(0xFFFFE082),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Input Profile Switcher
                    Text(
                        text = "Hardware Input Mode",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InputModeButton(
                            label = "Creative Pen",
                            subLabel = "Stylus tablet-v2",
                            isSelected = inputProfile == InputProfile.CREATIVE_PEN,
                            onClick = { onSwitchInputProfile(InputProfile.CREATIVE_PEN) },
                            modifier = Modifier.weight(1f)
                        )

                        InputModeButton(
                            label = "Gaming / Raw",
                            subLabel = "Relative capture",
                            isSelected = inputProfile == InputProfile.GAMING_RAW,
                            onClick = { onSwitchInputProfile(InputProfile.GAMING_RAW) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Audio Status & Volume
                    Text(
                        text = "PulseAudio Bridge (48kHz stereo · ${String.format("%.1f", audioStatus.latencyMs)} ms)",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            onSetVolume(it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Disconnect / Terminate Session
                    Button(
                        onClick = {
                            isExpanded = false
                            onStopSession()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Terminate Session", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun InputModeButton(
    label: String,
    subLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) Color(0xFF80D8FF) else Color.Gray.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0x3380D8FF) else Color(0x22FFFFFF)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                color = if (isSelected) Color(0xFF80D8FF) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = subLabel,
                color = Color.LightGray,
                fontSize = 9.sp
            )
        }
    }
}
