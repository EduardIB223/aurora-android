package io.nekohasekai.sfa.compose.component

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.utils.ProxyLinkParser
import io.nekohasekai.sfa.utils.ServerSections
import io.nekohasekai.sfa.utils.ServerSelection
import io.nekohasekai.sfa.utils.ServerSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Probe(val delay: Int, val error: String)

/**
 * Pick the server of a profile, laid out like the desktop: a section per
 * subscription, its "⚡ Auto" tile first, then its servers as tiles with the
 * flag, name and ping. "Ping all" measures every server with real traffic
 * (VPN on or off); a tap makes the tile the one in use — while connected
 * that's a selector switch, so open connections keep going.
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

                    val sections = ServerSections.of(list)
                    fun choose(entry: ServerSelection.Entry, shownName: String) {
                        scope.launch {
                            try {
                                ServerSelectionStore.select(context, profile, list.selectorTag, entry.tag, vpnRunning)
                                current = entry.tag
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.server_selected_toast, shownName),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onSelected(entry.tag)
                                onDismiss()
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (section in sections) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "h-${section.title}") {
                                Text(
                                    section.title ?: stringResource(R.string.server_section_all),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            section.auto?.let { auto ->
                                // The first Auto covers every server; a subscription's only its own.
                                val measured = if (section.title == null) probes?.values
                                else section.servers.mapNotNull { probes?.get(it.tag) }
                                val best = measured?.filter { it.delay > 0 }?.minOfOrNull { it.delay }
                                item(span = { GridItemSpan(maxLineSpan) }, key = auto.tag) {
                                    val label = if (section.title == null) stringResource(R.string.server_auto)
                                    else stringResource(R.string.server_group_auto)
                                    AutoTile(label, selected = auto.tag == current, delay = best) { choose(auto, label) }
                                }
                            }
                            items(sortedServers(section.servers, probes), key = { it.tag }) { entry ->
                                ServerTile(
                                    entry = entry,
                                    selected = entry.tag == current,
                                    probe = probes?.get(entry.tag),
                                    pinged = probes != null,
                                ) { choose(entry, entry.tag) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Once pinged: working servers fastest first, then the rest; list order before. */
private fun sortedServers(
    servers: List<ServerSelection.Entry>,
    probes: Map<String, Probe>?,
): List<ServerSelection.Entry> {
    if (probes == null) return servers
    val (working, failing) = servers.partition { (probes[it.tag]?.delay ?: 0) > 0 }
    return working.sortedBy { probes[it.tag]!!.delay } + failing
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
private fun AutoTile(label: String, selected: Boolean, delay: Int?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.9f else 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            delay?.let { Text("$it ms", color = delayColor(it), fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** One server: big flag, name, protocol, ping — highlighted when in use. */
@Composable
private fun ServerTile(
    entry: ServerSelection.Entry,
    selected: Boolean,
    probe: Probe?,
    pinged: Boolean,
    onClick: () -> Unit,
) {
    val (flag, name) = ServerSections.splitFlag(entry.tag)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(flag ?: "\uD83C\uDF10", fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name.ifBlank { entry.tag },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                val delay = probe?.delay?.takeIf { it > 0 }
                when {
                    delay != null -> Text(
                        "$delay ms",
                        color = delayColor(delay),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    pinged -> Text(
                        stringResource(R.string.ping_all_no_response),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    entry.type.isNotEmpty() -> Text(
                        entry.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun delayColor(delay: Int): Color = when {
    delay < 300 -> Color(0xFF22C55E)
    delay < 800 -> Color(0xFFEAB308)
    else -> Color(0xFFEF4444)
}
