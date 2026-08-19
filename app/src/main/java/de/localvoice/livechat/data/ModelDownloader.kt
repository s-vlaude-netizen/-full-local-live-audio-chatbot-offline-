package de.localvoice.livechat.data

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Fortschritt eines laufenden Downloads. */
data class DownloadProgress(
    val entry: CatalogEntry,
    val fileName: String,
    val copiedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** Grund, warum ein Download nicht geklappt hat. */
sealed interface DownloadError {
    /** Die Lizenz ist noch nicht angenommen oder das Token fehlt. */
    data class NeedsToken(val entry: CatalogEntry) : DownloadError

    data class Message(val text: String) : DownloadError
}

class DownloadException(val error: DownloadError, message: String) : IOException(message)

/**
 * Laedt Modelldateien von HuggingFace in den Modellordner.
 *
 * Zwei Schritte: erst das Dateiverzeichnis der Ablage abfragen und die passende
 * Datei auswaehlen, dann herunterladen. Abgebrochene Downloads werden beim
 * naechsten Versuch fortgesetzt, statt von vorn zu beginnen - bei einem halben
 * Gigabyte ueber Mobilfunk ist das der Unterschied zwischen aergerlich und
 * unbrauchbar.
 */
class ModelDownloader(private val models: ModelRepository) {

    /**
     * @param token HuggingFace-Zugangstoken; fuer Gemma zwingend, sonst optional.
     * @param onProgress wird waehrend des Ladens laufend aufgerufen.
     */
    suspend fun download(
        entry: CatalogEntry,
        token: String?,
        onProgress: (copied: Long, total: Long, fileName: String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveFileName(entry, token)
            val target = File(models.modelsDir, fileName)
            if (target.isFile && target.length() > 0) return@runCatching target

            val partial = File(models.modelsDir, "$fileName.part")
            fetchTo(
                url = "https://huggingface.co/${entry.repoId}/resolve/main/$fileName",
                token = token,
                partial = partial,
                entry = entry,
            ) { copied, total -> onProgress(copied, total, fileName) }

            check(partial.renameTo(target)) { "Die geladene Datei liess sich nicht ablegen." }
            target
        }
    }

    /** Fragt das Dateiverzeichnis der Ablage ab und waehlt die passende Datei. */
    private suspend fun resolveFileName(entry: CatalogEntry, token: String?): String {
        val body = readText("https://huggingface.co/api/models/${entry.repoId}", token, entry)
        val siblings = JSONObject(body).optJSONArray("siblings")
            ?: throw DownloadException(
                DownloadError.Message("Die Ablage ${entry.repoId} liefert kein Dateiverzeichnis."),
                "keine siblings",
            )
        val names = buildList {
            for (i in 0 until siblings.length()) {
                siblings.optJSONObject(i)?.optString("rfilename")?.takeIf { it.isNotEmpty() }
                    ?.let { add(it) }
            }
        }
        return ModelFileChooser.pick(names) ?: throw DownloadException(
            DownloadError.Message(
                "In ${entry.repoId} liegt keine .litertlm-Datei. " +
                    "Moeglicherweise wurde die Ablage umgebaut.",
            ),
            "keine passende Datei",
        )
    }

    private suspend fun readText(url: String, token: String?, entry: CatalogEntry): String {
        val connection = open(url, token, entry, rangeFrom = 0)
        return connection.inputStream.use { it.readBytes().decodeToString() }
    }

    private suspend fun fetchTo(
        url: String,
        token: String?,
        partial: File,
        entry: CatalogEntry,
        onProgress: (copied: Long, total: Long) -> Unit,
    ) {
        var already = if (partial.isFile) partial.length() else 0L
        val connection = open(url, token, entry, rangeFrom = already)

        // Beantwortet der Server den Range-Wunsch nicht mit 206, faengt er von
        // vorn an - dann muss auch die Datei von vorn geschrieben werden.
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed) already = 0L

        val remaining = connection.contentLengthLong
        val total = if (remaining > 0) already + remaining else -1L

        connection.inputStream.use { input ->
            java.io.FileOutputStream(partial, resumed).use { output ->
                val buffer = ByteArray(1 shl 16)
                var copied = already
                onProgress(copied, total)
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
    }

    /**
     * Oeffnet die Verbindung und folgt Weiterleitungen selbst.
     *
     * HuggingFace leitet auf ein signiertes CDN um. Das Zugangstoken darf dabei
     * nicht mitwandern - der CDN lehnt fremde Authorization-Kopfzeilen ab.
     */
    private fun open(
        url: String,
        token: String?,
        entry: CatalogEntry,
        rangeFrom: Long,
    ): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "lokaler-live-chat")
                if (!token.isNullOrBlank() && URL(current).host.endsWith("huggingface.co")) {
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }

            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL,
                -> return connection

                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw DownloadException(
                            DownloadError.Message("Der Server leitete ins Leere weiter."),
                            "leere Weiterleitung",
                        )
                    }
                    current = URL(URL(current), location).toString()
                }

                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> {
                    connection.disconnect()
                    throw DownloadException(
                        DownloadError.NeedsToken(entry),
                        "HTTP $code fuer ${entry.repoId}",
                    )
                }

                else -> {
                    connection.disconnect()
                    throw DownloadException(
                        DownloadError.Message("Der Server antwortete mit HTTP $code."),
                        "HTTP $code",
                    )
                }
            }
        }
        throw DownloadException(
            DownloadError.Message("Zu viele Weiterleitungen."),
            "redirect loop",
        )
    }

    /** Loescht angefangene Bruchstuecke, etwa nach einem endgueltigen Abbruch. */
    fun discardPartials() {
        models.modelsDir.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .forEach {
                if (!it.delete()) Log.w(TAG, "Bruchstueck ${it.name} liess sich nicht loeschen")
            }
    }

    private companion object {
        const val TAG = "ModelDownloader"
        const val MAX_REDIRECTS = 5
    }
}
