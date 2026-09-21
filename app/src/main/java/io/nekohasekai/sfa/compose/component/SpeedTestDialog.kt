package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=20000000"
private const val TEST_BYTES = 20_000_000L

/**
 * Measures throughput by pulling a fixed-size payload. The tunnel carries the
 * app's own traffic, so this reflects what the user actually gets while
 * connected rather than the raw link speed.
 */
@Composable
fun SpeedTestDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runTest() {
        running = true
        result = null
        error = null
        scope.launch {
            try {
                val mbps =
                    withContext(Dispatchers.IO) {
                        var downloaded = 0L
                        val elapsed =
                            measureTimeMillis {
                                val connection = URL(TEST_URL).openConnection() as HttpURLConnection
                                connection.connectTimeout = 15_000
                                connection.readTimeout = 30_000
                                connection.inputStream.use { stream ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (downloaded < TEST_BYTES) {
                                        val read = stream.read(buffer)
                                        if (read <= 0) break
                                        downloaded += read
                                    }
                                }
                                connection.disconnect()
                            }
                        if (downloaded == 0L || elapsed == 0L) {
                            error("no data")
                        }
                        downloaded * 8.0 / elapsed / 1000.0
                    }
                result = "${(mbps * 10).roundToInt() / 10.0} Mbps"
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
            running = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.speed_test)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    running -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.speed_test_running))
                    }

                    result != null -> {
                        Text(
                            result!!,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.speed_test_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    error != null -> {
                        Text(
                            stringResource(R.string.speed_test_failed, error!!),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Text(stringResource(R.string.speed_test_hint))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { runTest() }, enabled = !running) {
                Text(stringResource(if (result == null && error == null) R.string.speed_test_start else R.string.speed_test_again))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
