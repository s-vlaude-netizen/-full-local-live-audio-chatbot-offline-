package de.localvoice.livechat.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Fortschritt eines laufenden Downloads. */
data class DownloadProgress(
    val entry: CatalogEntry,
    val fileName: String,
    val copiedBytes: Long,
    val totalBytes: Long,
    /** Der wievielte Versuch am Stueck gerade laeuft; 1 = der erste. */
    val attempt: Int = 1,
    /** true, waehrend nach einem Abbruch auf den naechsten Versuch gewartet wird. */
    val waitingForRetry: Boolean = false,
) {
    val fraction: Float?
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** Grund, warum ein Download nicht geklappt hat. */
sealed interface DownloadError {
    /** Die Lizenz ist noch nicht angenommen oder das Token fehlt. */
    data class NeedsToken(val entry: CatalogEntry) : DownloadError

    /** @param retryable ob ein spaeterer Versuch Aussicht auf Erfolg hat. */
    data class Message(val text: String, val retryable: Boolean = false) : DownloadError
}

class DownloadException(val error: DownloadError, message: String) : IOException(message)

/**
 * Laedt Modelldateien von HuggingFace in den Modellordner.
 *
 * Zwei Schritte: erst das Dateiverzeichnis der Ablage abfragen und die passende
 * Datei auswaehlen, dann herunterladen.
 *
 * Faellt die Verbindung waehrenddessen weg - ein Router, der sich neu
 * einwaehlt, reicht schon - wird nach [DownloadRetryPolicy.WAIT_MILLIS] von
 * selbst weitergemacht, und zwar an der Stelle, an der es aufgehoert hat. Ein
 * halbes Gigabyte noch einmal von vorn zu laden waere sonst die Regel.
 */
class ModelDownloader(private val models: ModelRepository) {

    /**
     * @param token HuggingFace-Zugangstoken; fuer Gemma zwingend, sonst optional.
     * @param onProgress laufender Fortschritt samt Versuchszaehler.
     */
    suspend fun download(
        entry: CatalogEntry,
        token: String?,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = withRetry(entry, onProgress) { resolveFileName(entry, token) }
            val target = File(models.modelsDir, fileName)
            if (target.isFile && target.length() > 0) return@runCatching target

            val partial = File(models.modelsDir, "$fileName.part")
            withRetry(entry, onProgress) { attempt ->
                fetchTo(
                    url = "https://huggingface.co/${entry.repoId}/resolve/main/$fileName",
                    token = token,
                    partial = partial,
                    entry = entry,
                ) { copied, total ->
                    onProgress(DownloadProgress(entry, fileName, copied, total, attempt))
                }
            }

            check(partial.renameTo(target)) { "Die geladene Datei liess sich nicht ablegen." }
            target
        }
    }

    /**
     * Fuehrt [block] aus und wiederholt es nach einem Abbruch.
     *
     * Der Versuchszaehler wird zurueckgesetzt, sobald wieder Bytes ankommen -
     * ein langer Download ueberlebt damit beliebig viele kurze Aussetzer, waehrend
     * eine dauerhaft tote Leitung nach drei Anlaeufen aufgibt.
     */
    private suspend fun <T> withRetry(
        entry: CatalogEntry,
        onProgress: (DownloadProgress) -> Unit,
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        var bytesBeforeAttempt = -1L
        while (true) {
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val error = (e as? DownloadException)?.error
                if (!DownloadRetryPolicy.isRetryable(error)) throw e

                val bytesNow = partialBytes()
                attempt = DownloadRetryPolicy.nextAttempt(
                    previousAttempt = attempt,
                    madeProgress = bytesNow > bytesBeforeAttempt,
                )
                bytesBeforeAttempt = bytesNow
                if (DownloadRetryPolicy.givingUp(attempt)) {
                    Log.w(TAG, "Nach ${attempt - 1} Versuchen am Stueck aufgegeben", e)
                    throw e
                }

                Log.i(TAG, "Verbindung weg, Versuch $attempt in ${DownloadRetryPolicy.WAIT_MILLIS} ms", e)
                onProgress(
                    DownloadProgress(
                        entry = entry,
                        fileName = "",
                        copiedBytes = bytesNow.coerceAtLeast(0),
                        totalBytes = -1,
                        attempt = attempt,
                        waitingForRetry = true,
                    ),
                )
                delay(DownloadRetryPolicy.WAIT_MILLIS)
            }
        }
    }

    /** Wie viel schon auf der Platte liegt - Massstab dafuer, ob es vorangeht. */
    private fun partialBytes(): Long =
        models.modelsDir.listFiles().orEmpty()
            .filter { it.name.endsWith(".part") }
            .sumOf { it.length() }

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
                "In ${entry.repoId} liegt keine .litertlm-Datei - dieses Modell laesst " +
                    "sich hier nicht verwenden.",
            ),
            "keine passende Datei",
        )
    }

    /**
     * Holt die aktuell zu LiteRT-LM passenden Modelle von HuggingFace.
     *
     * Fest eingetragene Ablagen veralten: Dateien werden umbenannt, Modelle
     * kommen dazu. Deshalb wird gefragt statt geraten. Modelle mit
     * Lizenzzustimmung bleiben aussen vor - die sind schon im festen Katalog.
     */
    suspend fun fetchOnlineCatalog(): Result<List<CatalogEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = readText(
                "https://huggingface.co/api/models?filter=litert-lm&sort=downloads&limit=60",
                token = null,
                entry = ModelCatalog.DEFAULT,
            )
            val array = JSONArray(body)
            val known = ModelCatalog.ENTRIES.map { it.repoId }.toSet()
            buildList {
                for (i in 0 until array.length()) {
                    val model = array.optJSONObject(i) ?: continue
                    val id = model.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    if (id in known) continue
                    // "gated" ist false, "auto" oder "manual" - alles ausser false
                    // verlangt eine Zustimmung im Browser.
                    if (model.opt("gated")?.toString() != "false") continue
                    add(
                        CatalogEntry(
                            repoId = id,
                            title = id.substringAfter('/'),
                            sizeLabel = "Groesse unbekannt",
                            note = "Von HuggingFace gefunden, ohne Lizenzzustimmung ladbar.",
                            gated = false,
                        ),
                    )
                }
            }
        }
    }

    private fun readText(url: String, token: String?, entry: CatalogEntry): String {
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
            FileOutputStream(partial, resumed).use { output ->
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
                // Erzwingt, dass das Geschriebene die Platte erreicht. Sonst
                // steht nach einem Absturz weniger da, als der Zaehler behauptet.
                output.fd.sync()
            }
        }

        if (total > 0 && partial.length() < total) {
            throw DownloadException(
                DownloadError.Message("Die Verbindung brach mitten im Download ab.", retryable = true),
                "unvollstaendig: ${partial.length()} von $total",
            )
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
                connectTimeout = 15_000
                // Kurz halten: bei einem stillen Verbindungsabbruch haengt der
                // Download sonst eine Minute, bevor der Wiederanlauf greift.
                readTimeout = 20_000
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
                    // 5xx und Ueberlastung gehen vorbei, 404 nicht.
                    throw DownloadException(
                        DownloadError.Message(
                            "Der Server antwortete mit HTTP $code.",
                            retryable = code >= 500 || code == 429,
                        ),
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
