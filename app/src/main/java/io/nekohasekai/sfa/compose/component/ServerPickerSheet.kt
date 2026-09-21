package io.nekohasekai.sfa.compose.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.utils.ProxyLinkParser
import io.nekohasekai.sfa.utils.ServerSelection
import io.nekohasekai.sfa.utils.ServerSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Probe(val delay: Int, val error: String)

/**
 * Pick the server of a profile, with "Ping all" right next to the list: every
 * server is measured by sending real traffic through it (VPN on or off), and a
 * tap makes it the one in use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPickerSheet(
    profile: Profile,
    vpnRunning: Boolean,
    pingOnOpen: Boolean,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var servers by remember { mutableStateOf<ServerSelection.Servers?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<String?>(null) }
    var probes by remember { mutableStateOf<Map<String, Probe>?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var pingError by remember { mutableStateOf<String?>(null) }

    fun ping() {
        if (pinging) return
        pinging = true
        pingError = null
        scope.launch {
            try {
                probes =
                    withContext(Dispatchers.IO) {
                        val config = File(profile.typed.path).readText()
                        val iterator = Libbox.urlTestConfig(config, ProxyLinkParser.TEST_URL, 8000)
                        buildMap {
                            while (iterator.hasNext()) {
                                val r = iterator.next()
                                put(r.tag, Probe(r.delay, r.error))
                            }
                        }
                    }
            } catch (e: Exception) {
                pingError = e.message ?: e.toString()
            }
            pinging = false
        }
    }

    LaunchedEffect(profile.id) {
        val read =
            withContext(Dispatchers.IO) {
                runCatching { ServerSelection.read(File(profile.typed.path).readText()) }.getOrNull()
            }
        servers = read
        current = read?.let { ServerSelection.current(it, ServerSelectionStore.stored(context, profile.id)) }
        loaded = true
        if (read != null && pingOnOpen) ping()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.server_picker_title), style = MaterialTheme.typography.titleLarge)
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val list = servers
            when {
                !loaded -> CircularProgressIndicator(modifier = Modifier.size(24.dp))

                list == null -> Text(
                    stringResource(R.string.ping_all_no_servers),
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    FilledTonalButton(
                        onClick = { ping() },
                        enabled = !pinging,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (pinging) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ping_all_running))
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ping_all))
                        }
                    }

                    PingSummary(probes, list.serverCount, pingError, vpnRunning)

                    HorizontalDivider()

                    val rows = sortedEntries(list, probes)
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        items(rows, key = { it.tag }) { entry ->
                            ServerRow(
                                entry = entry,
                                selected = entry.tag == current,
                                probe = probes?.get(entry.tag),
                                bestDelay = probes?.values?.filter { it.delay > 0 }?.minOfOrNull { it.delay },
                                pinged = probes != null,
                                onClick = {
                                    scope.launch {
                                        try {
                                            ServerSelectionStore.select(context, profile, list.selectorTag, entry.tag, vpnRunning)
                                            current = entry.tag
                                            val shown = if (entry.isAuto) context.getString(R.string.server_auto) else entry.tag
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.server_selected_toast, shown),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onSelected(entry.tag)
                                            onDismiss()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Auto first, then — once pinged — working servers fastest first, then the rest. */
private fun sortedEntries(
    servers: ServerSelection.Servers,
    probes: Map<String, Probe>?,
): List<ServerSelection.Entry> {
    if (probes == null) return servers.entries
    val auto = servers.entries.filter { it.isAuto }
    val rest = servers.entries.filterNot { it.isAuto }
    val (working, failing) = rest.partition { (probes[it.tag]?.delay ?: 0) > 0 }
    return auto + working.sortedBy { probes[it.tag]!!.delay } + failing
}

@Composable
private fun PingSummary(
    probes: Map<String, Probe>?,
    total: Int,
    error: String?,
    vpnRunning: Boolean,
) {
    if (error != null) {
        Text(stringResource(R.string.ping_all_failed, error), color = MaterialTheme.colorScheme.error)
        return
    }
    probes ?: return
    val working = probes.values.count { it.delay > 0 }
    Text(stringResource(R.string.ping_all_summary, working, total), fontWeight = FontWeight.SemiBold)
    if (working == 0) {
        // When nothing answers, the reason matters more than the list.
        probes.values.firstOrNull { it.error.isNotBlank() }?.let {
            Text(it.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (vpnRunning) {
        Text(
            stringResource(R.string.ping_all_through_vpn),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerRow(
    entry: ServerSelection.Entry,
    selected: Boolean,
    probe: Probe?,
    bestDelay: Int?,
    pinged: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        if (entry.isAuto) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (entry.isAuto) stringResource(R.string.server_auto) else entry.tag,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!entry.isAuto && entry.type.isNotEmpty()) {
                Text(
                    entry.type.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val delay = if (entry.isAuto) bestDelay else probe?.delay?.takeIf { it > 0 }
        when {
            delay != null -> Text(
                "$delay ms",
                color = delayColor(delay),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            pinged && !entry.isAuto -> Text(
                stringResource(R.string.ping_all_no_response),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun delayColor(delay: Int): Color = when {
    delay < 300 -> Color(0xFF22C55E)
    delay < 800 -> Color(0xFFEAB308)
    else -> Color(0xFFEF4444)
}
