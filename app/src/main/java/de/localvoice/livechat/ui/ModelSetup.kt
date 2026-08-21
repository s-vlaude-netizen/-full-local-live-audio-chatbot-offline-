package de.localvoice.livechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.localvoice.livechat.R
import androidx.compose.ui.unit.dp
import de.localvoice.livechat.data.CatalogEntry
import de.localvoice.livechat.data.DownloadProgress
import de.localvoice.livechat.data.DownloadRetryPolicy
import kotlin.math.roundToInt

/**
 * Die Frage beim ersten Start: Modell jetzt laden?
 *
 * Der Download ist der einzige Moment, in dem die App ins Netz geht - deshalb
 * steht das hier ausdruecklich drin, statt es beilaeufig zu erledigen.
 */
@Composable
fun ModelDownloadDialog(
    entries: List<CatalogEntry>,
    onDownload: (CatalogEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(entries.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_download_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.dialog_download_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                entries.forEach { entry ->
                    CatalogRow(
                        entry = entry,
                        selected = entry == selected,
                        onSelect = { selected = entry },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.dialog_download_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(selected) }) { Text(stringResource(R.string.download_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
    )
}

@Composable
fun CatalogRow(
    entry: CatalogEntry,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    entry.sizeLabel + " · " + stringResource(
                        if (entry.gated) R.string.licence_needed else R.string.licence_free,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Fortschrittsanzeige waehrend des Downloads. */
@Composable
fun DownloadProgressRow(progress: DownloadProgress, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val fraction = progress.fraction
        if (fraction != null && !progress.waitingForRetry) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (progress.waitingForRetry) {
                        stringResource(R.string.download_retry_waiting)
                    } else {
                        stringResource(R.string.download_loading, progress.entry.title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        if (progress.totalBytes > 0) {
                            append(
                                stringResource(
                                    R.string.download_of,
                                    megabytes(progress.copiedBytes),
                                    megabytes(progress.totalBytes),
                                    (fraction ?: 0f).times(100).roundToInt(),
                                ),
                            )
                        } else {
                            append(megabytes(progress.copiedBytes))
                        }
                        if (progress.attempt > 1) {
                            append(
                                stringResource(
                                    R.string.download_attempt,
                                    progress.attempt,
                                    DownloadRetryPolicy.MAX_ATTEMPTS,
                                ),
                            )
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (progress.waitingForRetry) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.download_pause)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.download_retry_hint,
                (DownloadRetryPolicy.WAIT_MILLIS / 1000).toInt(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes >= 1L shl 30) {
        String.format("%.2f GB", bytes.toDouble() / (1L shl 30))
    } else {
        String.format("%.0f MB", bytes.toDouble() / (1L shl 20))
    }

/** Hinweis, wenn die Ablage erst nach Lizenzzustimmung herausrueckt. */
@Composable
fun TokenNeededDialog(
    entry: CatalogEntry,
    onOpenLicense: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.token_dialog_body, entry.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.token_dialog_steps),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenLicense(entry.licenseUrl) }) {
                Text(stringResource(R.string.open_model_page))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
