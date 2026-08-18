package de.localvoice.livechat.data

import android.content.Context
import de.localvoice.livechat.llm.LlmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Alles, was der Nutzer einstellen kann. */
data class AppSettings(
    val modelFileName: String? = null,
    val useGpu: Boolean = false,
    val temperature: Float = 0.8f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val systemPrompt: String = LlmConfig.DEFAULT_SYSTEM_PROMPT,
    val sttLanguageTag: String = "de-DE",
    val ttsLanguageTag: String = "de-DE",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Nach der Antwort automatisch wieder zuhoeren - das eigentliche Freihand-Verhalten. */
    val handsFree: Boolean = true,
    /** Verlauf bei jedem Start des Live-Modus leeren. */
    val freshStart: Boolean = false,
) {
    fun toLlmConfig(): LlmConfig = LlmConfig(
        topK = topK,
        topP = topP,
        temperature = temperature,
        useGpu = useGpu,
        systemPrompt = systemPrompt,
    )
}

/**
 * Einstellungen in SharedPreferences - klein, synchron und ohne weitere
 * Abhaengigkeit. Der aktuelle Stand liegt als [StateFlow] fuer die Oberflaeche bereit.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            modelFileName = prefs.getString(KEY_MODEL, null),
            useGpu = prefs.getBoolean(KEY_GPU, defaults.useGpu),
            temperature = prefs.getFloat(KEY_TEMPERATURE, defaults.temperature),
            topK = prefs.getInt(KEY_TOP_K, defaults.topK),
            topP = prefs.getFloat(KEY_TOP_P, defaults.topP),
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, defaults.systemPrompt)
                ?: defaults.systemPrompt,
            sttLanguageTag = prefs.getString(KEY_STT_LANG, defaults.sttLanguageTag)
                ?: defaults.sttLanguageTag,
            ttsLanguageTag = prefs.getString(KEY_TTS_LANG, defaults.ttsLanguageTag)
                ?: defaults.ttsLanguageTag,
            speechRate = prefs.getFloat(KEY_SPEECH_RATE, defaults.speechRate),
            pitch = prefs.getFloat(KEY_PITCH, defaults.pitch),
            handsFree = prefs.getBoolean(KEY_HANDS_FREE, defaults.handsFree),
            freshStart = prefs.getBoolean(KEY_FRESH_START, defaults.freshStart),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit().apply {
            putString(KEY_MODEL, settings.modelFileName)
            putBoolean(KEY_GPU, settings.useGpu)
            putFloat(KEY_TEMPERATURE, settings.temperature)
            putInt(KEY_TOP_K, settings.topK)
            putFloat(KEY_TOP_P, settings.topP)
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            putString(KEY_STT_LANG, settings.sttLanguageTag)
            putString(KEY_TTS_LANG, settings.ttsLanguageTag)
            putFloat(KEY_SPEECH_RATE, settings.speechRate)
            putFloat(KEY_PITCH, settings.pitch)
            putBoolean(KEY_HANDS_FREE, settings.handsFree)
            putBoolean(KEY_FRESH_START, settings.freshStart)
        }.apply()
    }

    private companion object {
        const val KEY_MODEL = "model_file"
        const val KEY_GPU = "use_gpu"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_K = "top_k"
        const val KEY_TOP_P = "top_p"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_STT_LANG = "stt_lang"
        const val KEY_TTS_LANG = "tts_lang"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_HANDS_FREE = "hands_free"
        const val KEY_FRESH_START = "fresh_start"
    }
}
