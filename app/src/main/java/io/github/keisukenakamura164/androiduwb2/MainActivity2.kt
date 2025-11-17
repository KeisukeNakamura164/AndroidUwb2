package io.github.keisukenakamura164.androiduwb2;

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.keisukenakamura164.androiduwb2.ui.screen.PassingScreen
import io.github.keisukenakamura164.androiduwb2.ui.theme.AndroidBleAndUwbSampleTheme


public class MainActivity2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidBleAndUwbSampleTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "passing") {
        composable("passing") {
            PassingScreen()
        }
    }
}
