package de.localvoice.livechat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Eine auf dem Geraet liegende Modelldatei. */
data class LocalModel(
    val file: File,
    val sizeBytes: Long,
) {
    val name: String get() = file.name
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1L shl 30 -> String.format("%.1f GB", sizeBytes.toDouble() / (1L shl 30))
            sizeBytes >= 1L shl 20 -> String.format("%.0f MB", sizeBytes.toDouble() / (1L shl 20))
            else -> "$sizeBytes B"
        }
}

/**
 * Verwaltet die Modelldateien im app-eigenen Ordner.
 *
 * Der Ordner liegt unter Android/data/<paket>/files/models und ist per USB oder
 * Dateimanager erreichbar - grosse Dateien lassen sich also auch ohne die App
 * dort ablegen, statt sie durch den Importdialog zu schieben.
 */
class ModelRepository(private val context: Context) {

    val modelsDir: File
        get() {
            val external = context.getExternalFilesDir(null)
            val base = external ?: context.filesDir
            return File(base, "models").apply { if (!exists()) mkdirs() }
        }

    fun list(): List<LocalModel> =
        modelsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .map { LocalModel(it, it.length()) }

    fun find(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val file = File(modelsDir, name)
        return file.takeIf { it.isFile }
    }

    /**
     * Kopiert eine ausgewaehlte Datei in den Modellordner.
     *
     * @param onProgress bekommt die bisher kopierten Bytes; die Gesamtgroesse
     * ist bei Inhalts-URIs nicht immer bekannt (dann -1).
     */
    suspend fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = displayName(uri) ?: "modell-${System.currentTimeMillis()}.litertlm"
                require(name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS) {
                    "Nicht unterstuetztes Format: $name"
                }
                val total = sizeOf(uri)
                val target = File(modelsDir, name)
                val partial = File(modelsDir, "$name.part")

                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Die Datei liess sich nicht oeffnen." }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "Die Datei liess sich nicht ablegen." }
                target
            }.onFailure {
                // Abbruch oder Fehler: angefangene Bruchstuecke nicht liegen lassen.
                modelsDir.listFiles().orEmpty()
                    .filter { it.name.endsWith(".part") }
                    .forEach { it.delete() }
            }
        }

    fun delete(model: LocalModel): Boolean = model.file.delete()

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sizeOf(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return -1L
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("litertlm")
    }
}
