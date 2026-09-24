package io.nekohasekai.sfa.compose.component

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.update.UpdateState
import kotlinx.coroutines.delay

/**
 * Downloading, then installing: stays open until the install succeeds (the
 * app restarts on the new version) or fails with the system's reason —
 * closing right after handing the file to the installer hid every failure.
 */
@Composable
fun UpdateProgressDialog(downloadError: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val status by UpdateState.installStatus
    val progress by UpdateState.downloadProgress
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        slow = false
        if (status == UpdateState.InstallStatus.Installing) {
            delay(20_000)
            slow = true
        }
    }

    val failed = (status as? UpdateState.InstallStatus.Failed)?.error
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update)) },
        text = {
            Column {
                when {
                    downloadError != null -> Text(downloadError, color = MaterialTheme.colorScheme.error)
                    failed != null -> Text(stringResource(R.string.update_install_failed, failed), color = MaterialTheme.colorScheme.error)
                    status == UpdateState.InstallStatus.Installing -> {
                        Text(stringResource(R.string.update_installing))
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (slow) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        val p = progress
                        Text(if (p != null) "${stringResource(R.string.downloading)} ${(p * 100).toInt()}%" else stringResource(R.string.downloading))
                        Spacer(modifier = Modifier.height(8.dp))
                        if (p != null) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (slow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.update_open_settings)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                UpdateState.downloadProgress.value = null
                UpdateState.setInstallStatus(UpdateState.InstallStatus.Idle)
                onClose()
            }) {
                val done = downloadError != null || failed != null
                Text(stringResource(if (done) R.string.ok else android.R.string.cancel))
            }
        },
    )
}
