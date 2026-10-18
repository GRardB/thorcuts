package com.grardb.thorcuts

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

/** The editor's and the picker's top bar: a back arrow and the title. `TopAppBar` is still
 *  experimental in material3 1.4; the opt-in is confined to this one composable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_glyph_back), contentDescription = "Back")
            }
        },
    )
}
