package de.localvoice.livechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.localvoice.livechat.service.LiveSessionService
import de.localvoice.livechat.session.LiveState
import de.localvoice.livechat.ui.LiveScreen
import de.localvoice.livechat.ui.MainViewModel
import de.localvoice.livechat.ui.SettingsScreen
import de.localvoice.livechat.ui.theme.LocalLiveChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalLiveChatTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val state by viewModel.session.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var startAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true && startAfterPermission) {
            startAfterPermission = false
            startLive(context, viewModel)
        }
    }

    // Beim Oeffnen der App schon einmal das Modell laden, damit die erste Frage
    // nicht auf den Ladevorgang wartet.
    LaunchedEffect(Unit) {
        viewModel.session.warmUp()
        viewModel.refreshModels()
    }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            onBack = {
                showSettings = false
                viewModel.refreshModels()
            },
        )
    } else {
        LiveScreen(
            viewModel = viewModel,
            onOpenSettings = { showSettings = true },
            onToggleLive = {
                if (state != LiveState.IDLE) {
                    viewModel.session.stop()
                    LiveSessionService.stop(context)
                } else if (hasMicPermission(context)) {
                    startLive(context, viewModel)
                } else {
                    startAfterPermission = true
                    permissionLauncher.launch(requiredPermissions())
                }
            },
        )
    }
}

private fun startLive(context: Context, viewModel: MainViewModel) {
    LiveSessionService.start(context)
    viewModel.session.start()
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }
