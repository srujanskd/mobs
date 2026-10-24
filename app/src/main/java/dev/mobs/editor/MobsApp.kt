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
            primary = StudioAccent,
            background = StudioBackground,
            surface = StudioPanel,
            secondary = Color(0xFF68DEC0),
            onPrimary = Color(0xFF151D3B),
            surfaceContainer = StudioPanel,
            outline = Color(0xFF39445B),
        ),
    ) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = StudioBackground) {
            CapturePermissionGate {
                CameraScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
