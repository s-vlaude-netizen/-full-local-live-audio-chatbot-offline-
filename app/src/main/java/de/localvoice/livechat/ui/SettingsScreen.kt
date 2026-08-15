package de.localvoice.livechat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.localvoice.livechat.data.LocalModel
import de.localvoice.livechat.domain.PromptTemplate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importModel(uri) }

    val modelsDir = remember { viewModel.modelsDirPath }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Zurueck")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(title = "Modell") {
                if (models.isEmpty()) {
                    Text(
                        "Noch keine Modelldatei gefunden. Ohne Modell antwortet nur der " +
                            "Platzhalter - die Sprachschleife laesst sich damit aber schon testen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    models.forEach { model ->
                        ModelRow(
                            model = model,
                            selected = settings.modelFileName == model.name,
                            onSelect = { viewModel.selectModel(model.name) },
                            onDelete = { viewModel.deleteModel(model) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { viewModel.selectModel(null) }) {
                        Text("Kein Modell verwenden")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = importProgress == null,
                    ) { Text("Modelldatei waehlen") }
                    OutlinedButton(onClick = { viewModel.refreshModels() }) {
                        Text("Ordner neu einlesen")
                    }
                }

                importProgress?.let { progress ->
                    Spacer(Modifier.height(8.dp))
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Kopiert: ${(fraction * 100).roundToInt()} %",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Wird kopiert…", style = MaterialTheme.typography.labelSmall)
                    }
                }

                importError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { viewModel.dismissImportError() }) { Text("OK") }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Grosse Dateien lassen sich auch direkt hierher kopieren, ohne Import:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    modelsDir,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Section(title = "Chat-Vorlage") {
                Text(
                    "Muss zum Modell passen. Gemma-Buendel brauchen GEMMA, " +
                        "Qwen und Phi meist CHATML.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptTemplate.entries.forEach { template ->
                        FilterChip(
                            selected = settings.template == template,
                            onClick = { viewModel.updateSettings { it.copy(template = template) } },
                            label = { Text(template.name) },
                        )
                    }
                }
            }

            Section(title = "Verhalten") {
                SwitchRow(
                    title = "Freihand-Modus",
                    subtitle = "Nach jeder Antwort automatisch weiter zuhoeren",
                    checked = settings.handsFree,
                    onChange = { value -> viewModel.updateSettings { it.copy(handsFree = value) } },
                )
                SwitchRow(
                    title = "GPU benutzen",
                    subtitle = "Schneller, aber nicht auf jedem Geraet stabil",
                    checked = settings.useGpu,
                    onChange = { value -> viewModel.updateSettings { it.copy(useGpu = value) } },
                )
            }

            Section(title = "Systemanweisung") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(systemPrompt = value) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }

            Section(title = "Sprache") {
                OutlinedTextField(
                    value = settings.sttLanguageTag,
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(sttLanguageTag = value.trim()) }
                    },
                    label = { Text("Spracherkennung (z. B. de-DE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.ttsLanguageTag,
                    onValueChange = { value ->
                        viewModel.updateSettings { it.copy(ttsLanguageTag = value.trim()) }
                    },
                    label = { Text("Sprachausgabe (z. B. de-DE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SliderRow(
                    label = "Sprechtempo",
                    value = settings.speechRate,
                    range = 0.5f..2.0f,
                    format = { String.format("%.2fx", it) },
                    onChange = { value -> viewModel.updateSettings { it.copy(speechRate = value) } },
                )
            }

            Section(title = "Generierung") {
                SliderRow(
                    label = "Temperatur",
                    value = settings.temperature,
                    range = 0.1f..1.5f,
                    format = { String.format("%.2f", it) },
                    onChange = { value ->
                        viewModel.updateSettings { it.copy(temperature = value) }
                    },
                )
                SliderRow(
                    label = "Maximale Laenge (Token)",
                    value = settings.maxTokens.toFloat(),
                    range = 256f..4096f,
                    steps = 14,
                    format = { it.roundToInt().toString() },
                    onChange = { value ->
                        viewModel.updateSettings { it.copy(maxTokens = value.roundToInt()) }
                    },
                )
                SliderRow(
                    label = "Top-K",
                    value = settings.topK.toFloat(),
                    range = 1f..80f,
                    format = { it.roundToInt().toString() },
                    onChange = { value ->
                        viewModel.updateSettings { it.copy(topK = value.roundToInt()) }
                    },
                )
            }

            Text(
                "Aenderungen an Modell oder Generierung wirken ab dem naechsten Turn; " +
                    "das Modell wird dann neu geladen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ModelRow(
    model: LocalModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    model.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Modell loeschen")
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}
