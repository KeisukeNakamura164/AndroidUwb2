package io.github.keisukenakamura164.androiduwb2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.keisukenakamura164.androiduwb2.ui.screen.PassingScreen
import io.github.keisukenakamura164.androiduwb2.ui.theme.AndroidBleAndUwbSampleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidBleAndUwbSampleTheme {
                PassingScreen()
            }
        }
    }
}