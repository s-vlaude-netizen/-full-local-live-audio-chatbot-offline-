package de.localvoice.livechat.llm

import de.localvoice.livechat.domain.ChatMessage
import de.localvoice.livechat.domain.PromptTemplate
import kotlinx.coroutines.flow.Flow

/** Laufzeit-Parameter der Textgenerierung. */
data class LlmConfig(
    val maxTokens: Int = 1024,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 0.8f,
    val useGpu: Boolean = false,
    val template: PromptTemplate = PromptTemplate.GEMMA,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT: String =
            "Du bist ein gesprochener Assistent und laeufst vollstaendig offline auf dem Telefon. " +
                "Antworte kurz, in ganzen Saetzen und in der Sprache des Nutzers. " +
                "Zwei bis drei Saetze reichen fast immer. " +
                "Keine Aufzaehlungszeichen, keine Ueberschriften, kein Markdown - alles wird vorgelesen."
    }
}

/**
 * Ein lokales Sprachmodell.
 *
 * Implementierungen halten ihre eigene Sitzung; [generate] darf immer nur einmal
 * gleichzeitig laufen.
 */
interface LlmEngine {

    /** Name fuer die Anzeige, z. B. der Dateiname des Modells. */
    val displayName: String

    /**
     * Erzeugt die Antwort auf den Verlauf. Der Flow liefert Bruchstuecke
     * (Deltas), nicht den bisher vollstaendigen Text.
     *
     * @param history endet mit der neuen Nutzer-Nachricht.
     */
    fun generate(history: List<ChatMessage>): Flow<String>

    /** Verwirft den Sitzungszustand, der naechste Aufruf beginnt von vorn. */
    fun resetSession()

    fun close()
}
