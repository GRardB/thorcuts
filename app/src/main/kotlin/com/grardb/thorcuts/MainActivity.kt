package com.grardb.thorcuts

import android.os.Bundle
import android.util.Log
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

        // Logged so a screen swap can be checked for an Activity RECREATE, which
        // task ids and ActivityRecord hashes cannot reveal: those are
        // window-manager-side and survive a client-side config-change relaunch.
        // The two panels are different sizes, so moving between them is exactly
        // the configuration change that would trigger one. A second line with a
        // different instance= value after a swap means the Activity was rebuilt
        // and in-memory UI state was lost.
        Log.i(
            "ThorcutsDiag",
            "ACTIVITY_CREATE instance=${System.identityHashCode(this)}" +
                " savedState=${savedInstanceState != null}",
        )

        setContent {
            // Always dark, on TRUE black rather than Material's dark grey: that is
            // the look this app wants, and on the Thor's AMOLED panels a black
            // pixel is an OFF pixel, so it costs no power either.
            //
            // The foreground colours are Material 3's dark defaults — near-white
            // on black, at full contrast.
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
