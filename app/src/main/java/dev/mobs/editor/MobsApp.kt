package dev.mobs.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun MobsApp() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8FE3C3),
            background = Color(0xFF101916),
            surface = Color(0xFF101916),
        ),
    ) {
        Scaffold { insets ->
            CapturePermissionGate {
                CameraScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(insets),
                )
            }
        }
    }
}
