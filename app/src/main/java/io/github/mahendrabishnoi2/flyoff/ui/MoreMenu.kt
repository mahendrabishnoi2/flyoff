package io.github.mahendrabishnoi2.flyoff.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.mahendrabishnoi2.flyoff.R

/**
 * The main screen's "More options" button and its menu: the things used rarely, so they don't
 * crowd the map. Tonal, so it shows on any map colour.
 */
@Composable
fun MoreMenu(onOpenCredits: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalIconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.credits_title)) },
                onClick = {
                    open = false
                    onOpenCredits()
                },
            )
        }
    }
}
