package com.grardb.thorcuts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lower this window's brightness only. See BurnIn.kt for why.
        dimWindow(window)

        // Hide the status and navigation bars. With the app's own content dimmed to
        // mid-grey on black, the system chrome — a white clock, a battery
        // percentage, three nav icons — becomes the brightest and most static thing
        // on the panel, which is exactly the burn-in pattern the rest of this is
        // avoiding. They stay reachable by swiping from an edge.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            // Forced dark on TRUE black, not merely "dark theme": this screen sits
            // on an AMOLED panel for long periods during the device tasks, and a
            // black pixel is an OFF pixel — no burn-in risk, no power draw.
            //
            // onSurface/onBackground are overridden too: Material 3's dark default
            // is near-white, which would make the text the brightest thing on an
            // otherwise unlit panel.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color.Black,
                    onBackground = DIM_TEXT,
                    onSurface = DIM_TEXT,
                )
            ) {
                Surface(color = Color.Black) {
                    DiagnosticsScreen(viewModel)
                }
            }
        }
    }
}
