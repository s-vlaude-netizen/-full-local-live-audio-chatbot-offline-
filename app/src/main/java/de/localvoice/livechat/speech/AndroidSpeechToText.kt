package de.localvoice.livechat.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spracherkennung ueber die Android-Bordmittel.
 *
 * Ab Android 13 wird der geraeteinterne Erkenner benutzt
 * ([SpeechRecognizer.createOnDeviceSpeechRecognizer]), darunter der normale
 * Erkenner mit [RecognizerIntent.EXTRA_PREFER_OFFLINE]. In beiden Faellen
 * braucht es das Offline-Sprachpaket der jeweiligen Sprache; ohne Paket meldet
 * das System einen Fehler, statt heimlich online zu gehen.
 *
 * [SpeechRecognizer] ist an den Hauptthread gebunden - alle Aufrufe laufen
 * deshalb ueber [Dispatchers.Main].
 */
class AndroidSpeechToText(
    private val context: Context,
    private val languageTag: String = "de-DE",
    private val maxListenMs: Long = 30_000,
) : SpeechToText {

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun listenOnce(): SttResult = withContext(Dispatchers.Main) {
        _partialText.value = ""
        _level.value = 0f

        val engine = try {
            obtainRecognizer()
        } catch (t: Throwable) {
            Log.e(TAG, "Spracherkennung nicht verfuegbar", t)
            return@withContext SttResult.Failure(
                "Auf diesem Geraet ist keine Spracherkennung eingerichtet.",
                recoverable = false,
            )
        }

        val result = try {
            withTimeoutOrNull(maxListenMs) { awaitResult(engine) }
        } catch (e: TimeoutCancellationException) {
            null
        }

        _level.value = 0f
        result ?: run {
            runCatching { engine.cancel() }
            SttResult.Silence
        }
    }

    private suspend fun awaitResult(engine: SpeechRecognizer): SttResult =
        suspendCancellableCoroutine { continuation ->
            val listener = object : RecognitionListener {
                private var finished = false

                private fun finish(result: SttResult) {
                    if (finished) return
                    finished = true
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onEndOfSpeech() {
                    _level.value = 0f
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Der Erkenner liefert grob -2..10 dB; auf 0..1 abbilden.
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    firstResult(partialResults)?.let { _partialText.value = it }
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results)?.trim().orEmpty()
                    finish(if (text.isEmpty()) SttResult.Silence else SttResult.Text(text))
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    finish(mapError(error))
                }
            }

            continuation.invokeOnCancellation {
                runCatching { engine.cancel() }
            }

            engine.setRecognitionListener(listener)
            try {
                engine.startListening(buildIntent())
            } catch (t: Throwable) {
                Log.e(TAG, "startListening fehlgeschlagen", t)
                if (continuation.isActive) {
                    continuation.resume(
                        SttResult.Failure("Die Spracherkennung liess sich nicht starten.", true)
                    )
                }
            }
        }

    private fun obtainRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val created = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        return created
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Entscheidend fuer den Offline-Betrieb.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Wie lange Stille als Satzende gilt (Hinweis, nicht jeder Erkenner beachtet ihn).
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                1_200L,
            )
        }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun mapError(error: Int): SttResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SttResult.Silence

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
            // Der Erkenner haengt gelegentlich; beim naechsten Durchgang neu aufbauen.
            recreateRecognizer()
            SttResult.Failure("Die Spracherkennung war belegt.", recoverable = true)
        }

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SttResult.Failure("Die App darf das Mikrofon nicht benutzen.", recoverable = false)

        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        -> SttResult.Failure(
            "Fuer $languageTag ist kein Offline-Sprachpaket installiert. " +
                "In den Systemeinstellungen unter Spracheingabe herunterladen.",
            recoverable = false,
        )

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SttResult.Failure(
            "Der Erkenner wollte ins Netz. Offline-Spracherkennung fuer $languageTag installieren.",
            recoverable = false,
        )

        SpeechRecognizer.ERROR_CLIENT -> {
            recreateRecognizer()
            SttResult.Silence
        }

        else -> SttResult.Failure("Spracherkennung meldet Fehler $error.", recoverable = true)
    }

    private fun recreateRecognizer() {
        val old = recognizer
        recognizer = null
        runCatching { old?.destroy() }
    }

    override fun abort() {
        onMainThread { runCatching { recognizer?.cancel() } }
    }

    override fun destroy() {
        onMainThread { recreateRecognizer() }
    }

    /** [SpeechRecognizer] duldet Aufrufe nur vom Hauptthread. */
    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "AndroidSpeechToText"
    }
}
