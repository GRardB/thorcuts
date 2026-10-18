package com.grardb.thorcuts

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val viewModel: ThorcutsViewModel by viewModels {
        ThorcutsViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent system bars with the right icon contrast for the system's light or dark
        // setting; the shell pads for them (safeDrawingPadding), so nothing sits under one.
        enableEdgeToEdge()
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

        // The theme (light, or dark with the system) is the shell's: Theme.kt, Shell.kt.
        setContent { ThorcutsScreen(viewModel) }
    }
}
