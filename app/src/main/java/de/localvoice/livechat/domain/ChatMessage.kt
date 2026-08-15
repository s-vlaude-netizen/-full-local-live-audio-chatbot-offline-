package de.localvoice.livechat.domain

/** Wer hat gesprochen. */
enum class Role { USER, ASSISTANT }

/**
 * Eine Zeile im Gespraechsverlauf.
 *
 * @param streaming true, solange die Antwort noch Token fuer Token waechst.
 */
data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val timestampMs: Long,
    val streaming: Boolean = false,
)
