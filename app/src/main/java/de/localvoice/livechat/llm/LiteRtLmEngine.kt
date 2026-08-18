package de.localvoice.livechat.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import de.localvoice.livechat.domain.ChatMessage
import de.localvoice.livechat.domain.Role
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Sprachmodell auf Basis von LiteRT-LM (Google AI Edge).
 *
 * Erwartet eine .litertlm-Datei, etwa Gemma 3 1B. Alles laeuft im Prozess der
 * App, es geht kein Byte ins Netz.
 *
 * Die Laufzeit fuehrt Chat-Vorlage, Systemanweisung und Gespraechsverlauf
 * selbst mit - die App schickt pro Zug nur die neue Aeusserung und bekommt die
 * Antwort stueckweise zurueck. Ein eigener Prompt-Zusammenbau waere hier nicht
 * nur ueberfluessig, sondern wuerde die Vorlage des Modells doppelt anwenden.
 */
class LiteRtLmEngine private constructor(
    private val engine: Engine,
    private val config: LlmConfig,
    override val displayName: String,
) : LlmEngine {

    private var conversation: Conversation? = null

    override fun generate(history: List<ChatMessage>): Flow<String> = flow {
        val userText = history.lastOrNull { it.role == Role.USER }?.text.orEmpty()
        if (userText.isBlank()) return@flow

        val active = ensureConversation()
        try {
            active.sendMessageAsync(userText).collect { message ->
                val chunk = message.text
                if (!chunk.isNullOrEmpty()) emit(chunk)
            }
        } catch (t: Throwable) {
            // Meist ein volles Kontextfenster. Der Zwischenspeicher ist danach
            // unbrauchbar, also verwerfen - das naechste Gespraech faengt frisch an.
            Log.w(TAG, "Generierung fehlgeschlagen, Gespraech wird verworfen", t)
            closeConversation()
            throw t
        }
    }.flowOn(Dispatchers.IO)

    private fun ensureConversation(): Conversation {
        conversation?.let { return it }
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(config.systemPrompt),
            samplerConfig = SamplerConfig(
                topK = config.topK,
                topP = config.topP.toDouble(),
                temperature = config.temperature.toDouble(),
            ),
        )
        return engine.createConversation(conversationConfig).also { conversation = it }
    }

    private fun closeConversation() {
        val old = conversation
        conversation = null
        runCatching { old?.close() }
    }

    override fun resetSession() = closeConversation()

    override fun close() {
        closeConversation()
        runCatching { engine.close() }
    }

    companion object {
        private const val TAG = "LiteRtLmEngine"

        /**
         * Laedt das Modell. Das dauert je nach Geraet und Modellgroesse einige
         * Sekunden und gehoert deshalb in einen Hintergrund-Dispatcher.
         */
        suspend fun create(
            context: Context,
            modelFile: File,
            config: LlmConfig,
        ): LiteRtLmEngine = withContext(Dispatchers.IO) {
            val engineConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = if (config.useGpu) Backend.GPU() else Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            )
            val engine = Engine(engineConfig)
            engine.initialize()
            LiteRtLmEngine(engine, config, modelFile.name)
        }
    }
}
