package io.github.keisukenakamura164.androiduwb2.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.keisukenakamura164.androiduwb2.ui.viewmodel.AutoRoleViewModel

private val REQUIRED_PERMISSION = listOf(
    Manifest.permission.BLUETOOTH,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.UWB_RANGING
)

@Composable
fun PassingScreen(viewModel: AutoRoleViewModel = viewModel()) {
    val context = LocalContext.current

    // --- 権限確認のロジック ---
    val isGranted = remember {
        mutableStateOf(REQUIRED_PERMISSION.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val permissionRequest = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions -> isGranted.value = permissions.all { it.value } }
    )
    LaunchedEffect(key1 = Unit) {
        if (!isGranted.value) {
            permissionRequest.launch(REQUIRED_PERMISSION.toTypedArray())
        }
    }


    // --- ViewModelとの連携 ---
    // ViewModelが持つUIの状態(UwbUiState)を監視する
    val uiState by viewModel.uiState.collectAsState()
    val distance = uiState.distance

    // 権限が許可されたら、一度だけUWBセッションを開始する
    LaunchedEffect(isGranted.value) {
        if (isGranted.value) {
            viewModel.startRoleDiscoveryAndUwbSession(context)
        }
    }

    // --- UIの表示部分 ---
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isGranted.value) {
                Text(
                    text = "権限を許可してください",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (distance == null) {
                Text(
                    text = "接続中...",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                val distanceText = "距離: %.2f m".format(distance)
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (distance <= 10) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "通信開始",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
