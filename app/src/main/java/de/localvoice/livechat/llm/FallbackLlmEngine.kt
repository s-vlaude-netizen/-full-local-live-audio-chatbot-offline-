package de.localvoice.livechat.llm

import android.content.Context
import de.localvoice.livechat.R

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
class FallbackLlmEngine(private val context: Context) : LlmEngine {

    override val displayName: String = context.getString(R.string.engine_fallback_name)

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
        val q = question.lowercase(Locale.getDefault())
        return when {
            question.isEmpty() -> context.getString(R.string.fallback_nothing_understood)

            q.contains("uhr") || q.contains("zeit") || q.contains("time") ->
                context.getString(
                    R.string.fallback_time,
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                )

            q.contains("datum") || q.contains("welcher tag") || q.contains("date") ->
                context.getString(
                    R.string.fallback_date,
                    SimpleDateFormat("d. MMMM yyyy", Locale.getDefault()).format(Date()),
                )

            q.contains("test") -> context.getString(R.string.fallback_test)

            else -> context.getString(R.string.fallback_generic, question)
        }
    }
}
