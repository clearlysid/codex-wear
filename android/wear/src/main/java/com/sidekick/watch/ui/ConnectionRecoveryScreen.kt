package com.sidekick.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/**
 * A blocking recovery state, used whenever the watch cannot safely present server-backed data.
 * This is deliberately a state of the current destination rather than another navigation root.
 */
@Composable
fun ConnectionRecoveryScreen(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AppScaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Codex is offline",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
            Button(
                onClick = onRetry,
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                label = { Text("Retry") },
            )
            Button(
                onClick = onOpenSettings,
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("Settings") },
            )
        }
    }
}
