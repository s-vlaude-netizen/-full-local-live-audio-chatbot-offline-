package de.localvoice.livechat.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import de.localvoice.livechat.domain.ChatMessage
import de.localvoice.livechat.domain.History
import de.localvoice.livechat.domain.PromptBuilder
import de.localvoice.livechat.domain.Role
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Sprachmodell auf Basis von MediaPipe LLM Inference (Google AI Edge).
 *
 * Erwartet eine .task-Datei, wie sie fuer Gemma 3 1B, Gemma 2 2B, Phi-4 mini
 * oder Qwen 2.5 angeboten wird. Alles laeuft im Prozess der App, es geht kein
 * Byte ins Netz.
 *
 * Die Sitzung bleibt zwischen den Turns bestehen, damit der Verlauf nicht bei
 * jeder Frage neu durchgerechnet werden muss. Erst wenn das Kontextfenster
 * voll ist, wird sie verworfen und mit gekuerztem Verlauf neu aufgebaut.
 */
class MediaPipeLlmEngine private constructor(
    private val inference: LlmInference,
    private val config: LlmConfig,
    override val displayName: String,
) : LlmEngine {

    private var session: LlmInferenceSession? = null
    private var sessionPrimed = false

    /** Wird gesetzt, sobald das Modell das Ende der Antwort selbst meldet. */
    @Volatile
    private var finishedCleanly = false

    override fun generate(history: List<ChatMessage>): Flow<String> = callbackFlow {
        val active = ensureSession()
        val prompt = if (sessionPrimed) {
            PromptBuilder.buildTurn(config.template, history.lastUserText())
        } else {
            PromptBuilder.build(
                config.template,
                config.systemPrompt,
                History.trim(history, MAX_HISTORY_MESSAGES),
            )
        }

        finishedCleanly = false
        try {
            active.addQueryChunk(prompt)
            active.generateResponseAsync { partial, done ->
                if (!partial.isNullOrEmpty()) trySend(partial)
                if (done) {
                    finishedCleanly = true
                    close()
                }
            }
            sessionPrimed = true
        } catch (t: Throwable) {
            // Meist ein volles Kontextfenster: Sitzung verwerfen, der naechste
            // Turn baut sie mit gekuerztem Verlauf neu auf.
            Log.w(TAG, "Generierung fehlgeschlagen, Sitzung wird verworfen", t)
            invalidateSession()
            close(t)
        }

        awaitClose {
            // Ein laufender Aufruf laesst sich nicht zuverlaessig abbrechen.
            // Wird die Antwort verworfen, ist der Zwischenspeicher unbrauchbar.
            if (!finishedCleanly) invalidateSession()
        }
    }.flowOn(Dispatchers.IO)

    private fun ensureSession(): LlmInferenceSession {
        session?.let { return it }
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(config.topK)
            .setTopP(config.topP)
            .setTemperature(config.temperature)
            .build()
        val created = LlmInferenceSession.createFromOptions(inference, options)
        session = created
        sessionPrimed = false
        return created
    }

    private fun invalidateSession() {
        val old = session
        session = null
        sessionPrimed = false
        runCatching { old?.close() }
    }

    override fun resetSession() = invalidateSession()

    override fun close() {
        invalidateSession()
        runCatching { inference.close() }
    }

    private fun List<ChatMessage>.lastUserText(): String =
        lastOrNull { it.role == Role.USER }?.text.orEmpty()

    companion object {
        private const val TAG = "MediaPipeLlmEngine"
        private const val MAX_HISTORY_MESSAGES = 12

        /**
         * Laedt das Modell. Dauert je nach Geraet und Modellgroesse einige
         * Sekunden und gehoert deshalb in einen Hintergrund-Dispatcher.
         */
        suspend fun create(
            context: Context,
            modelFile: File,
            config: LlmConfig,
        ): MediaPipeLlmEngine = withContext(Dispatchers.IO) {
            val backend = if (config.useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(config.maxTokens)
                .setPreferredBackend(backend)
                .build()
            val inference = LlmInference.createFromOptions(context, options)
            MediaPipeLlmEngine(inference, config, modelFile.name)
        }
    }
}
