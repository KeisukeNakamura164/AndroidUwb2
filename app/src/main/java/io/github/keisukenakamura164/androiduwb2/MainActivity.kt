package io.github.keisukenakamura164.androiduwb2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.keisukenakamura164.androiduwb2.ui.screen.PassingScreen
import io.github.keisukenakamura164.androiduwb2.ui.theme.AndroidBleAndUwbSampleTheme

class MainActivity : ComponentActivity() {

    private var showLocationDialog by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            android.util.Log.d("MainActivity", "All permissions granted")
            Toast.makeText(this, "すべてのパーミッションが許可されました", Toast.LENGTH_SHORT).show()
            checkLocationServices()
        } else {
            android.util.Log.e("MainActivity", "Some permissions denied: $permissions")
            Toast.makeText(
                this,
                "パーミッションが拒否されました。アプリが正常に動作しない可能性があります",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            AndroidBleAndUwbSampleTheme {
                PassingScreen()

                if (showLocationDialog) {
                    AlertDialog(
                        onDismissRequest = { showLocationDialog = false },
                        title = { Text("位置情報サービスが無効です") },
                        text = {
                            Text("BLEスキャンを行うには、位置情報サービスを有効にする必要があります。設定画面を開きますか？")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showLocationDialog = false
                                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            ) {
                                Text("設定を開く")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showLocationDialog = false }
                            ) {
                                Text("キャンセル")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.UWB_RANGING,
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Requesting permissions: $permissionsToRequest")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            android.util.Log.d("MainActivity", "All permissions already granted")
            checkLocationServices()
        }
    }

    private fun checkLocationServices() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            android.util.Log.w("MainActivity", "Location services are disabled!")
            showLocationDialog = true
        } else {
            android.util.Log.d("MainActivity", "Location services are enabled")
            showLocationDialog = false
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
    }
}
