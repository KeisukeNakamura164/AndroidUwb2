package io.github.keisukenakamura164.androiduwb2.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.keisukenakamura164.androiduwb2.ui.viewmodel.AutoRoleViewModel

@Composable
fun PassingScreen(viewModel: AutoRoleViewModel = viewModel()) {
    val context: Context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // この画面が表示されたときに一度だけ実行される
    // 権限許可はMainActivityが保証している前提
    LaunchedEffect(Unit) {
        viewModel.startRoleDiscoveryAndUwbSession(context)
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

            // 現在の状態（役割）を表示
            Text(
                text = "現在の状態: ${uiState.role}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // 距離がまだ取得できていない（測距前）の場合
            if (uiState.distance == null) {
                Text(
                    text = "相手を探しています...",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            } else {
                // 距離が取得できた場合
                val distanceText = "距離: %.2f m".format(uiState.distance)
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.displayMedium
                )

                // 方角と高さの表示
                uiState.azimuth?.let {
                    Text(text = "方角: %.1f °".format(it), style = MaterialTheme.typography.bodyLarge)
                }
                uiState.elevation?.let {
                    Text(text = "高さ: %.1f °".format(it), style = MaterialTheme.typography.bodyLarge)
                }

                // 10m以内ならメッセージを表示
                if (uiState.distance!! <= 10) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "通信開始",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

