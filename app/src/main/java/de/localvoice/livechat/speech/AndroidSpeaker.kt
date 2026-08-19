package de.localvoice.livechat.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * Fuer den Offline-Betrieb wird eine Stimme gesucht, die keine Netzverbindung
 * braucht. Entscheidend ist dabei die Pruefung auf
 * [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED]: Android fuehrt Stimmen auch
 * dann in [TextToSpeech.getVoices] auf, wenn ihre Sprachdaten gar nicht auf dem
 * Geraet liegen. Setzt man so eine Stimme, meldet speak() brav Erfolg - und zu
 * hoeren ist nichts.
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

    private val _diagnostics = MutableStateFlow("Sprachausgabe noch nicht gestartet")
    override val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

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
            _diagnostics.value = "Keine TTS-Engine gefunden"
            return false
        }

        engine.setAudioAttributes(audioAttributes)
        val languageStatus = engine.setLanguage(locale)
        val languageMissing = languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED

        val voice = selectUsableVoice(engine)
        if (voice != null) runCatching { engine.setVoice(voice) }

        _diagnostics.value = buildString {
            append("Engine: ").append(engine.defaultEngine ?: "unbekannt")
            append(" | Stimme: ").append(voice?.name ?: "Vorgabe der Engine")
            append(" | Sprache: ").append(locale.toLanguageTag())
        }

        _warning.value = when {
            voice != null -> null
            languageMissing -> "Fuer ${locale.displayLanguage} fehlen die Sprachdaten der " +
                "Sprachausgabe. In den Systemeinstellungen unter Text-in-Sprache installieren."
            else -> "Fuer ${locale.displayLanguage} ist keine offline nutzbare Stimme " +
                "installiert. Ohne sie bleibt die App stumm."
        }

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
                _warning.value = "Die Sprachausgabe brach mit Fehler $errorCode ab."
                release()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = release()

            private fun release() {
                val left = pending.updateAndGet { if (it > 0) it - 1 else 0 }
                pendingFlow.value = left
                if (left == 0) {
                    _busy.value = false
                    abandonFocus()
                }
            }
        })

        tts = engine
        return true
    }

    /**
     * Eine Stimme, die zur Sprache passt, ohne Netz auskommt und deren Daten
     * tatsaechlich installiert sind.
     */
    private fun selectUsableVoice(engine: TextToSpeech): Voice? {
        val voices: Set<Voice> = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language == locale.language }
            .filter { !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .maxByOrNull { it.quality }
    }

    override fun enqueue(text: String) = speak(text, TextToSpeech.QUEUE_ADD)

    override fun speakNow(text: String) = speak(text, TextToSpeech.QUEUE_FLUSH)

    private fun speak(text: String, queueMode: Int) {
        val engine = tts ?: run {
            _warning.value = "Die Sprachausgabe war nicht bereit, der Satz ging verloren."
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            pending.set(0)
            pendingFlow.value = 0
        }
        val id = "utt-" + utteranceCounter.incrementAndGet()
        pendingFlow.value = pending.incrementAndGet()
        _busy.value = true
        requestFocus()
        val status = engine.speak(clean, queueMode, Bundle(), id)
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() abgelehnt: $status")
            _warning.value = "Die Sprachausgabe nahm den Satz nicht an (Code $status)."
            pendingFlow.value = pending.updateAndGet { if (it > 0) it - 1 else 0 }
            if (pending.get() == 0) {
                _busy.value = false
                abandonFocus()
            }
        }
    }

    /**
     * Ohne Audiofokus duckt oder verschluckt das System die Ausgabe, wenn
     * gerade etwas anderes laeuft - etwa direkt nach der Spracherkennung.
     */
    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()
        focusRequest = request
        runCatching { audioManager?.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    override suspend fun awaitIdle() {
        pendingFlow.map { it == 0 }.first { it }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        pending.set(0)
        pendingFlow.value = 0
        _busy.value = false
        abandonFocus()
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
