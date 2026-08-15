package de.localvoice.livechat.llm

import de.localvoice.livechat.domain.ChatMessage
import de.localvoice.livechat.domain.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Platzhalter, solange keine Modelldatei geladen ist.
 *
 * Damit laesst sich die komplette Sprachschleife - Mikrofon, Erkennung,
 * Sprachausgabe - schon nach der Installation testen, ohne 500 MB Modell.
 * Der Motor sagt selbst deutlich, dass er kein echtes Sprachmodell ist.
 */
class FallbackLlmEngine : LlmEngine {

    override val displayName: String = "Kein Modell geladen (Testmodus)"

    override fun generate(history: List<ChatMessage>): Flow<String> = flow {
        val question = history.lastOrNull { it.role == Role.USER }?.text.orEmpty().trim()
        val answer = reply(question)
        // Wortweise ausgeben, damit die Sprachausgabe genauso stueckelt wie beim echten Modell.
        val words = answer.split(" ")
        words.forEachIndexed { index, word ->
            emit(if (index == 0) word else " $word")
            delay(25)
        }
    }

    override fun resetSession() = Unit

    override fun close() = Unit

    private fun reply(question: String): String {
        val q = question.lowercase(Locale.GERMAN)
        return when {
            question.isEmpty() ->
                "Ich habe nichts verstanden. Sag es gern noch einmal."

            q.contains("uhr") || q.contains("zeit") ->
                "Es ist " + SimpleDateFormat("HH:mm", Locale.GERMAN).format(Date()) + " Uhr. " +
                    "Mehr kann ich ohne geladenes Modell nicht."

            q.contains("datum") || q.contains("welcher tag") ->
                "Heute ist der " + SimpleDateFormat("d. MMMM yyyy", Locale.GERMAN).format(Date()) + "."

            q.contains("test") ->
                "Test angekommen. Mikrofon, Erkennung und Sprachausgabe funktionieren. " +
                    "Fuer echte Antworten fehlt noch die Modelldatei."

            else ->
                "Ich habe verstanden: $question. Ich bin aber nur der Platzhalter. " +
                    "Lade in den Einstellungen eine Modelldatei, dann antworte ich richtig."
        }
    }
}
