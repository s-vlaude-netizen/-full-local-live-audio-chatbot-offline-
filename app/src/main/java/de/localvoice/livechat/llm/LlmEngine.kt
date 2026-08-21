package de.localvoice.livechat.llm

import de.localvoice.livechat.domain.ChatMessage
import kotlinx.coroutines.flow.Flow

/** Laufzeit-Parameter der Textgenerierung. */
data class LlmConfig(
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 0.8f,
    val useGpu: Boolean = false,
    /** Kommt aus den Einstellungen; dort wird sie mit der Vorgabe der Sprache belegt. */
    val systemPrompt: String = "",
)

/**
 * Ein lokales Sprachmodell.
 *
 * Implementierungen halten ihre eigene Sitzung samt Verlauf; [generate] darf
 * immer nur einmal gleichzeitig laufen.
 */
interface LlmEngine {

    /** Name fuer die Anzeige, z. B. der Dateiname des Modells. */
    val displayName: String

    /**
     * Erzeugt die Antwort auf den Verlauf. Der Flow liefert Bruchstuecke
     * (Deltas), nicht den bisher vollstaendigen Text.
     *
     * @param history endet mit der neuen Nutzer-Nachricht. Motoren, die den
     * Verlauf selbst mitfuehren, brauchen davon nur den letzten Eintrag.
     */
    fun generate(history: List<ChatMessage>): Flow<String>

    /** Verwirft den Sitzungszustand, das naechste Gespraech beginnt von vorn. */
    fun resetSession()

    fun close()
}
