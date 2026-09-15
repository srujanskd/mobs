package dev.mobs.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mobs.R

/**
 * Camera and microphone are required before the media session can start. Notification permission
 * is optional and does not block capture.
 */
private val RequiredPermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val CaptureCritical = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
fun CapturePermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            CaptureCritical.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val activity = LocalActivity.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var needsSettings by rememberSaveable { mutableStateOf(false) }

    fun refreshPermissions() {
        val denied = CaptureCritical.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        granted = denied.isEmpty()
        needsSettings = asked && denied.any {
            activity?.shouldShowRequestPermissionRationale(it) == false
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshPermissions()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        asked = true
        refreshPermissions()
    }

    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(RequiredPermissions.toTypedArray())
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(if (needsSettings) R.string.permissions_settings_body else R.string.permissions_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                if (needsSettings) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                } else {
                    launcher.launch(RequiredPermissions.toTypedArray())
                }
            }) {
                Text(
                    stringResource(
                        when {
                            needsSettings -> R.string.permissions_settings
                            asked -> R.string.permissions_retry
                            else -> R.string.permissions_grant
                        },
                    ),
                )
            }
        }
    }
}
