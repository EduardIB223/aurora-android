package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

/** Connect button plus the status line and active server name beneath it. */
@Composable
fun AuroraPowerPanel(
    serviceStatus: Status,
    profileName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state =
        when (serviceStatus) {
            Status.Started -> PowerState.Connected
            Status.Starting, Status.Stopping -> PowerState.Connecting
            else -> PowerState.Disconnected
        }

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuroraPowerButton(
            state = state,
            icon = Icons.Default.PowerSettingsNew,
            contentDescription =
                stringResource(
                    if (state == PowerState.Connected) R.string.stop else R.string.action_start,
                ),
            onClick = onClick,
            enabled = serviceStatus != Status.Stopping,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text =
                stringResource(
                    when (state) {
                        PowerState.Connected -> R.string.status_connected
                        PowerState.Connecting -> R.string.status_connecting
                        PowerState.Disconnected -> R.string.status_disconnected
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
            color =
                if (state == PowerState.Connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
        )

        if (!profileName.isNullOrBlank()) {
            Text(
                text = profileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
