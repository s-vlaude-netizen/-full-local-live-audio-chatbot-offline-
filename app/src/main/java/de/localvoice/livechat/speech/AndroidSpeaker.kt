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
import de.localvoice.livechat.R
import java.util.Collections
import java.util.Locale
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
 * Zwei Fallstricke bestimmen den Aufbau:
 *
 * Erstens fuehrt Android Stimmen auch dann in [TextToSpeech.getVoices] auf,
 * wenn ihre Sprachdaten gar nicht auf dem Geraet liegen. Setzt man so eine
 * Stimme, meldet speak() brav Erfolg - und zu hoeren ist nichts. Deshalb die
 * Pruefung auf [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED].
 *
 * Zweitens kommen die Rueckmeldungen abgebrochener Aeusserungen verspaetet.
 * Ein blosser Zaehler wuerde von ihnen faelschlich heruntergezaehlt und die
 * gerade laufende Ausgabe fuer beendet erklaeren. Deshalb werden die
 * Aeusserungen einzeln ueber ihre Kennung verfolgt: eine Rueckmeldung, deren
 * Kennung nicht mehr aussteht, laeuft ins Leere.
 */
class AndroidSpeaker(
    private val context: Context,
    private val locale: Locale = Locale.GERMAN,
    private val speechRate: Float = 1.0f,
    private val pitch: Float = 1.0f,
) : Speaker {

    private var tts: TextToSpeech? = null

    /** Kennungen der Aeusserungen, die noch aussteht. */
    private val outstanding: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val pendingFlow = MutableStateFlow(0)
    private val utteranceCounter = AtomicLong(0)

    private val _warning = MutableStateFlow<String?>(null)
    override val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _diagnostics = MutableStateFlow(context.getString(R.string.tts_not_started))
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
            _warning.value = context.getString(R.string.tts_no_engine)
            _diagnostics.value = context.getString(R.string.tts_no_engine_short)
            return false
        }

        engine.setAudioAttributes(audioAttributes)
        val languageStatus = engine.setLanguage(locale)
        val languageMissing = languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED

        val voice = selectUsableVoice(engine)
        if (voice != null) runCatching { engine.setVoice(voice) }

        _diagnostics.value = context.getString(
            R.string.tts_diagnostics,
            engine.defaultEngine ?: context.getString(R.string.unknown),
            voice?.name ?: context.getString(R.string.tts_engine_default_voice),
            locale.toLanguageTag(),
        )

        _warning.value = when {
            voice != null -> null
            languageMissing -> context.getString(R.string.tts_language_missing, locale.displayLanguage)
            else -> context.getString(R.string.tts_no_offline_voice, locale.displayLanguage)
        }

        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != null && utteranceId in outstanding) _busy.value = true
            }

            override fun onDone(utteranceId: String?) = release(utteranceId)

            @Deprecated("Von der Plattform ersetzt, muss aber ueberschrieben werden.")
            override fun onError(utteranceId: String?) = release(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "Sprachausgabe meldet Fehler $errorCode")
                if (utteranceId != null && utteranceId in outstanding) {
                    _warning.value = context.getString(R.string.tts_failed, errorCode)
                }
                release(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = release(utteranceId)
        })

        tts = engine
        return true
    }

    /**
     * Streicht eine Aeusserung von der Liste.
     *
     * Kennungen, die nicht mehr aufgefuehrt sind, stammen von abgebrochenen
     * Aeusserungen und werden ignoriert - sonst wuerde ihre verspaetete
     * Rueckmeldung die gerade laufende Ausgabe fuer beendet erklaeren.
     */
    private fun release(utteranceId: String?) {
        if (utteranceId == null || !outstanding.remove(utteranceId)) return
        val left = outstanding.size
        pendingFlow.value = left
        if (left == 0) {
            _busy.value = false
            abandonFocus()
        }
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
            _warning.value = context.getString(R.string.tts_not_ready)
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            outstanding.clear()
            pendingFlow.value = 0
        }
        val id = "utt-" + utteranceCounter.incrementAndGet()
        outstanding.add(id)
        pendingFlow.value = outstanding.size
        _busy.value = true
        requestFocus()
        val status = engine.speak(clean, queueMode, Bundle(), id)
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() abgelehnt: $status")
            _warning.value = context.getString(R.string.tts_rejected, status)
            release(id)
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
        // Erst die Liste leeren, dann anhalten: die Rueckmeldungen der
        // abgebrochenen Aeusserungen laufen danach ins Leere.
        outstanding.clear()
        pendingFlow.value = 0
        _busy.value = false
        runCatching { tts?.stop() }
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
