package de.localvoice.livechat.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Sprachausgabe ueber die Android-TTS-Engine.
 *
 * Fuer den Offline-Betrieb wird bevorzugt eine Stimme gewaehlt, die keine
 * Netzverbindung braucht ([Voice.isNetworkConnectionRequired] false). Findet
 * sich keine solche Stimme, laeuft die Ausgabe trotzdem weiter - aber
 * [warning] sagt, dass ohne Netz nichts zu hoeren sein wird.
 */
class AndroidSpeaker(
    private val context: Context,
    private val locale: Locale = Locale.GERMAN,
    private val speechRate: Float = 1.0f,
    private val pitch: Float = 1.0f,
) : Speaker {

    private var tts: TextToSpeech? = null

    private val pending = AtomicInteger(0)
    private val pendingFlow = MutableStateFlow(0)
    private val utteranceCounter = AtomicLong(0)

    private val _warning = MutableStateFlow<String?>(null)
    override val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    override suspend fun prepare(): Boolean {
        tts?.let { return true }
        // Der Konstruktor meldet den Erfolg erst spaeter ueber den Listener.
        val holder = arrayOfNulls<TextToSpeech>(1)
        val status: Int = suspendCancellableCoroutine { continuation ->
            holder[0] = TextToSpeech(context) { code ->
                if (continuation.isActive) continuation.resume(code)
            }
        }
        val engine = holder[0]?.takeIf { status == TextToSpeech.SUCCESS } ?: run {
            runCatching { holder[0]?.shutdown() }
            _warning.value = "Auf diesem Geraet ist keine Sprachausgabe eingerichtet."
            return false
        }

        val languageStatus = engine.setLanguage(locale)
        if (
            languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            _warning.value =
                "Fuer ${locale.displayLanguage} fehlen die Sprachdaten der Sprachausgabe. " +
                    "In den Systemeinstellungen unter Text-in-Sprache nachinstallieren."
        }

        selectOfflineVoice(engine)
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _busy.value = true
            }

            override fun onDone(utteranceId: String?) = release()

            @Deprecated("Von der Plattform ersetzt, muss aber ueberschrieben werden.")
            override fun onError(utteranceId: String?) = release()

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Sprachausgabe meldet Fehler $errorCode")
                release()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = release()

            private fun release() {
                val left = pending.updateAndGet { if (it > 0) it - 1 else 0 }
                pendingFlow.value = left
                if (left == 0) _busy.value = false
            }
        })

        tts = engine
        return true
    }

    /** Sucht eine Stimme, die ohne Netzverbindung auskommt. */
    private fun selectOfflineVoice(engine: TextToSpeech) {
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return
        val matching = voices.filter { voice ->
            voice.locale.language == locale.language && !voice.isNetworkConnectionRequired
        }
        val best = matching.maxByOrNull { it.quality }
        if (best != null) {
            runCatching { engine.setVoice(best) }
            _warning.value = null
        } else if (voices.any { it.locale.language == locale.language }) {
            _warning.value =
                "Es gibt nur eine Online-Stimme fuer ${locale.displayLanguage}. " +
                    "Ohne Netz bleibt die App stumm - offline nutzbare Stimme nachinstallieren."
        }
    }

    override fun enqueue(text: String) {
        val engine = tts ?: return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val id = "utt-" + utteranceCounter.incrementAndGet()
        pendingFlow.value = pending.incrementAndGet()
        _busy.value = true
        val params = Bundle()
        val status = engine.speak(clean, TextToSpeech.QUEUE_ADD, params, id)
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() abgelehnt: $status")
            pendingFlow.value = pending.updateAndGet { if (it > 0) it - 1 else 0 }
            if (pending.get() == 0) _busy.value = false
        }
    }

    override suspend fun awaitIdle() {
        pendingFlow.map { it == 0 }.first { it }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        pending.set(0)
        pendingFlow.value = 0
        _busy.value = false
    }

    override fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private companion object {
        const val TAG = "AndroidSpeaker"
    }
}
