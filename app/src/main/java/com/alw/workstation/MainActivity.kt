package com.alw.workstation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alw.workstation.core.session.SessionController
import com.alw.workstation.core.session.SessionState
import com.alw.workstation.core.session.model.StackProfile

class MainActivity : ComponentActivity() {

    private val sessionController = SessionController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WorkstationMainScreen(
                        sessionController = sessionController,
                        onLaunch = { profile ->
                            sessionController.updateState(SessionState.Starting(profile.id))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WorkstationMainScreen(
    sessionController: SessionController,
    onLaunch: (StackProfile) -> Unit
) {
    val state by sessionController.sessionState.collectAsState()
    val defaultProfile = remember { StackProfile.defaultArchXiaomiPad8() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Android Linux Workstation",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Target: Xiaomi Pad 8 (Snapdragon 8s Gen 4 / Adreno 825)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(0.8f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active Stack Profile: ${defaultProfile.name}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Distro: ${defaultProfile.distro.rootfsId} (${defaultProfile.distro.architecture})")
                Text(text = "Compositor: ${defaultProfile.compositor.type} (${defaultProfile.compositor.configMode})")
                Text(text = "GPU Acceleration: ${defaultProfile.gpu.driverMode} via ${defaultProfile.gpu.kgslNode}")
                Text(text = "Display: ${defaultProfile.display.customWidth}x${defaultProfile.display.customHeight} @ ${defaultProfile.display.refreshRateHz}Hz (Scale: ${defaultProfile.display.scaleFactor}x)")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Status: $state",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onLaunch(defaultProfile) },
            modifier = Modifier.fillMaxWidth(0.5f)
        ) {
            Text("Launch Workstation")
        }
    }
}
