package de.localvoice.livechat.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import de.localvoice.livechat.R
import de.localvoice.livechat.data.AppSettings
import de.localvoice.livechat.data.ModelRepository
import de.localvoice.livechat.data.SettingsStore
import de.localvoice.livechat.domain.ChatMessage
import de.localvoice.livechat.domain.Role
import de.localvoice.livechat.domain.SentenceChunker
import de.localvoice.livechat.domain.SpeechText
import de.localvoice.livechat.domain.VoiceCommands
import de.localvoice.livechat.llm.FallbackLlmEngine
import de.localvoice.livechat.llm.LlmEngine
import de.localvoice.livechat.llm.LiteRtLmEngine
import de.localvoice.livechat.speech.AndroidSpeaker
import de.localvoice.livechat.speech.AndroidSpeechToText
import de.localvoice.livechat.speech.Speaker
import de.localvoice.livechat.speech.SpeechToText
import de.localvoice.livechat.speech.SttResult
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Wo die Schleife gerade steht. */
enum class LiveState {
    /** Nichts laeuft. */
    IDLE,

    /** Modell und Sprachdienste werden hochgefahren. */
    PREPARING,

    /** Mikrofon offen. */
    LISTENING,

    /** Modell rechnet, es kam aber noch kein sprechbarer Satz heraus. */
    THINKING,

    /** Antwort wird vorgelesen. */
    SPEAKING,
}

/**
 * Die eigentliche Freihand-Schleife: zuhoeren, antworten, vorlesen, wieder
 * zuhoeren - bis der Nutzer stoppt.
 *
 * Lebt so lange wie die Anwendung, damit ein Bildschirmdreh oder der Wechsel in
 * den Hintergrund das Gespraech nicht abreisst. Der Vordergrunddienst haelt den
 * Prozess dabei am Leben.
 */
class LiveSessionController(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val models: ModelRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(LiveState.IDLE)
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _statusDetail = MutableStateFlow("")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _engineLabel = MutableStateFlow(context.getString(R.string.engine_no_model))
    val engineLabel: StateFlow<String> = _engineLabel.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partial.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _speechDiagnostics = MutableStateFlow(context.getString(R.string.tts_not_started))
    val speechDiagnostics: StateFlow<String> = _speechDiagnostics.asStateFlow()

    private var stt: SpeechToText? = null
    private var sttSignature: String? = null
    private var speaker: Speaker? = null
    private var speakerSignature: String? = null
    private var engine: LlmEngine? = null
    private var engineSignature: String? = null

    private val idCounter = AtomicLong(0)
    private val pendingTypedInput = AtomicReference<String?>(null)
    private val turnMutex = Mutex()

    private var loopJob: Job? = null
    private var turnJob: Job? = null
    private var relayJob: Job? = null
    private var speakerRelayJob: Job? = null

    val isRunning: Boolean get() = loopJob?.isActive == true

    // -------------------------------------------------------------- Steuerung

    /** Startet den Freihand-Modus. */
    fun start() {
        if (isRunning) return
        if (!hasMicrophonePermission()) {
            _error.value = context.getString(R.string.error_no_mic_permission)
            return
        }
        _error.value = null
        loopJob = scope.launch { runLoop() }
    }

    /** Beendet den Freihand-Modus, der Verlauf bleibt stehen. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        turnJob?.cancel()
        turnJob = null
        stt?.abort()
        speaker?.stop()
        _partial.value = ""
        _level.value = 0f
        _statusDetail.value = ""
        _state.value = LiveState.IDLE
    }

    fun toggle() {
        if (isRunning) stop() else start()
    }

    /** Bricht nur die laufende Antwort ab; die Schleife hoert danach weiter zu. */
    fun interruptCurrentTurn() {
        speaker?.stop()
        turnJob?.cancel()
    }

    /** Tastatureingabe - funktioniert auch bei ausgeschaltetem Live-Modus. */
    fun sendTypedMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (isRunning) {
            // Die Schleife greift die Eingabe auf, sobald das Zuhoeren endet.
            pendingTypedInput.set(clean)
            stt?.abort()
        } else {
            scope.launch {
                _error.value = null
                prepareForTurn()
                runTurn(clean, speakAloud = true)
                _state.value = LiveState.IDLE
            }
        }
    }

    fun clearConversation() {
        _messages.value = emptyList()
        engine?.resetSession()
    }

    /** Laedt das Modell im Voraus, damit die erste Frage nicht wartet. */
    fun warmUp() {
        scope.launch {
            runCatching { ensureEngine() }
                .onFailure { Log.w(TAG, "Vorladen fehlgeschlagen", it) }
        }
    }

    fun shutdown() {
        stop()
        speaker?.shutdown()
        stt?.destroy()
        engine?.close()
        speaker = null
        stt = null
        engine = null
    }

    // ---------------------------------------------------------------- Schleife

    private suspend fun runLoop() {
        var silentRounds = 0
        try {
            prepareForTurn()
            while (currentCoroutineContext().isActive) {
                val typed = pendingTypedInput.getAndSet(null)
                if (typed != null) {
                    silentRounds = 0
                    runTurnInChildJob(typed)
                    if (!settingsStore.current.handsFree) break
                    continue
                }

                _state.value = LiveState.LISTENING
                _statusDetail.value = context.getString(R.string.status_listening)
                val recognizer = stt ?: break
                when (val result = recognizer.listenOnce()) {
                    is SttResult.Text -> {
                        silentRounds = 0
                        _partial.value = ""
                        if (VoiceCommands.isStopCommand(result.text)) {
                            _statusDetail.value = context.getString(R.string.status_stopped_by_voice)
                            break
                        }
                        runTurnInChildJob(result.text)
                        if (!settingsStore.current.handsFree) break
                    }

                    SttResult.Silence -> {
                        // Eine Tastatureingabe kann das Zuhoeren absichtlich abgebrochen haben.
                        if (pendingTypedInput.get() != null) continue
                        silentRounds++
                        if (silentRounds >= MAX_SILENT_ROUNDS) {
                            _statusDetail.value = context.getString(R.string.status_paused_after_silence)
                            break
                        }
                        _statusDetail.value = context.getString(R.string.status_nothing_heard)
                    }

                    is SttResult.Failure -> {
                        _error.value = result.message
                        if (!result.recoverable) break
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Live-Schleife abgebrochen", t)
            _error.value = t.message ?: context.getString(R.string.error_unexpected)
        } finally {
            _partial.value = ""
            _level.value = 0f
            _state.value = LiveState.IDLE
            loopJob = null
        }
    }

    /**
     * Der Turn laeuft in einem eigenen Job, damit "Unterbrechen" nur ihn trifft
     * und nicht die ganze Schleife.
     */
    private suspend fun runTurnInChildJob(userText: String) {
        val job = scope.launch { runTurn(userText, speakAloud = true) }
        turnJob = job
        job.join()
        turnJob = null
    }

    private suspend fun runTurn(userText: String, speakAloud: Boolean) = turnMutex.withLock {
        // Manche Erkenner halten die Aufnahme offen, bis man sie ausdruecklich
        // abbricht. Dann kommt die Sprachausgabe nicht durch.
        if (speakAloud) stt?.abort()
        appendMessage(Role.USER, userText, streaming = false)
        _state.value = LiveState.THINKING
        _statusDetail.value = context.getString(R.string.status_thinking)

        val assistantId = appendMessage(Role.ASSISTANT, "", streaming = true)
        val chunker = SentenceChunker()
        val collected = StringBuilder()

        try {
            val llm = ensureEngine()
            llm.generate(_messages.value.dropLast(1)).collect { delta ->
                collected.append(delta)
                updateMessage(assistantId, SpeechText.forDisplay(collected.toString()), streaming = true)
                if (speakAloud) {
                    chunker.append(delta).forEach { speakChunk(it) }
                }
            }
            if (speakAloud) chunker.flush()?.let { speakChunk(it) }
        } catch (e: CancellationException) {
            updateMessage(
                assistantId,
                SpeechText.forDisplay(collected.toString()).ifEmpty { context.getString(R.string.cancelled) },
                streaming = false,
            )
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Generierung fehlgeschlagen", t)
            _error.value = t.message ?: context.getString(R.string.error_model_no_answer)
        }

        updateMessage(assistantId, SpeechText.forDisplay(collected.toString()), streaming = false)

        if (speakAloud) {
            _state.value = LiveState.SPEAKING
            _statusDetail.value = context.getString(R.string.status_speaking)
            speaker?.awaitIdle()
            // Kurz Ruhe lassen: sonst greift der Erkenner nach dem Audiogeraet,
            // waehrend die Sprachausgabe noch ausklingt.
            delay(SETTLE_AFTER_SPEECH_MS)
        }
    }

    private fun speakChunk(chunk: String) {
        val text = SpeechText.forSpeech(chunk)
        if (text.isEmpty()) return
        _state.value = LiveState.SPEAKING
        _statusDetail.value = context.getString(R.string.status_speaking)
        speaker?.enqueue(text)
    }

    // --------------------------------------------------------- Bausteine laden

    private suspend fun prepareForTurn() {
        _state.value = LiveState.PREPARING
        _statusDetail.value = context.getString(R.string.status_preparing_speech)
        val settings = settingsStore.current
        ensureSpeaker(settings)
        ensureStt(settings)
        _statusDetail.value = context.getString(R.string.status_loading_model)
        ensureEngine()
    }

    private fun ensureStt(settings: AppSettings) {
        val signature = settings.sttLanguageTag
        if (stt != null && sttSignature == signature) return
        stt?.destroy()
        val created = AndroidSpeechToText(context, settings.sttLanguageTag)
        stt = created
        sttSignature = signature
        relayJob?.cancel()
        relayJob = scope.launch {
            launch { created.partialText.collect { _partial.value = it } }
            launch { created.level.collect { _level.value = it } }
        }
    }

    private suspend fun ensureSpeaker(settings: AppSettings) {
        val signature = "${settings.ttsLanguageTag}|${settings.speechRate}|${settings.pitch}"
        if (speaker != null && speakerSignature == signature) return
        speaker?.shutdown()
        val created = AndroidSpeaker(
            context = context,
            locale = Locale.forLanguageTag(settings.ttsLanguageTag),
            speechRate = settings.speechRate,
            pitch = settings.pitch,
        )
        val ok = created.prepare()
        speaker = created
        speakerSignature = signature
        if (!ok) _error.value = context.getString(R.string.error_speech_output_failed)
        speakerRelayJob?.cancel()
        speakerRelayJob = scope.launch {
            launch { created.warning.collect { it?.let { message -> _error.value = message } } }
            launch { created.diagnostics.collect { _speechDiagnostics.value = it } }
        }
    }

    /** Spricht einen festen Satz - damit laesst sich die Ausgabe pruefen. */
    fun testSpeech() {
        scope.launch {
            _error.value = null
            ensureSpeaker(settingsStore.current)
            speaker?.speakNow(context.getString(R.string.test_speech_sentence))
        }
    }

    private suspend fun ensureEngine(): LlmEngine {
        val settings = settingsStore.current
        val signature = buildString {
            append(settings.modelFileName).append('|')
            append(settings.useGpu).append('|')
            append(settings.temperature).append('|')
            append(settings.topK).append('|')
            append(settings.topP).append('|')
            append(settings.systemPrompt.hashCode())
        }
        engine?.let { if (engineSignature == signature) return it }

        engine?.close()
        engine = null

        val modelFile = models.find(settings.modelFileName)
        val created: LlmEngine = if (modelFile == null) {
            FallbackLlmEngine(context)
        } else {
            runCatching {
                LiteRtLmEngine.create(context, modelFile, settings.toLlmConfig())
            }.getOrElse { t ->
                Log.e(TAG, "Modell ${modelFile.name} liess sich nicht laden", t)
                _error.value =
                    context.getString(R.string.error_model_load_failed, modelFile.name, t.message ?: "")
                FallbackLlmEngine(context)
            }
        }
        engine = created
        engineSignature = signature
        _engineLabel.value = created.displayName
        return created
    }

    // ---------------------------------------------------------------- Verlauf

    private fun appendMessage(role: Role, text: String, streaming: Boolean): Long {
        val id = idCounter.incrementAndGet()
        _messages.value = _messages.value + ChatMessage(
            id = id,
            role = role,
            text = text,
            timestampMs = System.currentTimeMillis(),
            streaming = streaming,
        )
        return id
    }

    private fun updateMessage(id: Long, text: String, streaming: Boolean) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = text, streaming = streaming) else it
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun dismissError() {
        _error.value = null
    }

    private companion object {
        const val TAG = "LiveSessionController"
        const val MAX_SILENT_ROUNDS = 6
        const val RETRY_DELAY_MS = 800L
        const val SETTLE_AFTER_SPEECH_MS = 250L
    }
}
