package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.ProxyLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class PingRow(val tag: String, val delay: Int, val error: String)

/**
 * "Ping all": measures every server of a profile by sending real traffic
 * through it, like the desktop app's check — so a dead server that still
 * accepts TCP is shown as not responding instead of with a latency.
 *
 * Works with the VPN off: the core runs a throwaway instance with no TUN.
 */
@Composable
fun PingAllDialog(
    configPath: String,
    vpnRunning: Boolean,
    onDismiss: () -> Unit,
) {
    var rows by remember { mutableStateOf<List<PingRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(configPath, attempt) {
        rows = null
        error = null
        try {
            rows =
                withContext(Dispatchers.IO) {
                    val config = File(configPath).readText()
                    val iterator = Libbox.urlTestConfig(config, ProxyLinkParser.TEST_URL, 8000)
                    buildList {
                        while (iterator.hasNext()) {
                            val r = iterator.next()
                            add(PingRow(r.tag, r.delay, r.error))
                        }
                    }
                }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ping_all)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = rows
                when {
                    error != null -> Text(
                        stringResource(R.string.ping_all_failed, error!!),
                        color = MaterialTheme.colorScheme.error,
                    )

                    current == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ping_all_running))
                    }

                    current.isEmpty() -> Text(stringResource(R.string.ping_all_no_servers))

                    else -> {
                        val working = current.count { it.delay > 0 }
                        Text(
                            stringResource(R.string.ping_all_summary, working, current.size),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (vpnRunning) {
                            Text(
                                stringResource(R.string.ping_all_through_vpn),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            items(current, key = { it.tag }) { row -> PingResultRow(row) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            if (rows != null || error != null) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.ping_all_again)) }
            }
        },
    )
}

@Composable
private fun PingResultRow(row: PingRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            row.tag,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box {
            if (row.delay > 0) {
                Text(
                    "${row.delay} ms",
                    color = delayColor(row.delay),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(R.string.ping_all_no_response),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun delayColor(delay: Int): Color = when {
    delay < 300 -> Color(0xFF22C55E)
    delay < 800 -> Color(0xFFEAB308)
    else -> Color(0xFFEF4444)
}
