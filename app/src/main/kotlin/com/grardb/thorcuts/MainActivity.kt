package com.grardb.thorcuts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Forced dark on TRUE black, not merely "dark theme": this screen sits
            // on an AMOLED panel for long periods during the device tasks, and a
            // black pixel is an OFF pixel — no burn-in risk, no power draw.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                )
            ) {
                Surface(color = Color.Black) {
                    DiagnosticsScreen(viewModel)
                }
            }
        }
    }
}
