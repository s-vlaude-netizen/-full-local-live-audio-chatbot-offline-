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
import androidx.compose.ui.unit.dp
import de.localvoice.livechat.data.CatalogEntry
import de.localvoice.livechat.data.DownloadProgress
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
        title = { Text("Sprachmodell laden?") },
        text = {
            Column {
                Text(
                    "Es liegt noch kein Sprachmodell auf dem Geraet. Ohne eines " +
                        "antwortet nur ein Platzhalter.",
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
                    "Der Download ist einmalig und braucht Netz. Danach laeuft alles " +
                        "offline. Am besten im WLAN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(selected) }) { Text("Herunterladen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Spaeter") }
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
                    entry.sizeLabel + if (entry.gated) " · Lizenz noetig" else " · frei",
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
        if (fraction != null) {
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
                    progress.entry.title + " wird geladen",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        append(megabytes(progress.copiedBytes))
                        if (progress.totalBytes > 0) {
                            append(" von ").append(megabytes(progress.totalBytes))
                            append(" (").append((fraction ?: 0f).times(100).roundToInt())
                            append(" %)")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Ein Abbruch ist kein Verlust - beim naechsten Versuch geht es an " +
                "derselben Stelle weiter.",
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
        title = { Text("Zugang fehlt") },
        text = {
            Column {
                Text(
                    "${entry.title} gibt HuggingFace erst heraus, wenn du der Lizenz " +
                        "zugestimmt hast und ein Zugangstoken hinterlegt ist.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Modellseite oeffnen und der Lizenz zustimmen.\n" +
                        "2. Auf huggingface.co unter Settings ein Access Token mit " +
                        "Leserecht anlegen.\n" +
                        "3. Das Token unten in den Einstellungen eintragen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenLicense(entry.licenseUrl) }) {
                Text("Modellseite oeffnen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schliessen") } },
    )
}
