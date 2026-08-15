package de.localvoice.livechat.domain

/**
 * Haelt den Verlauf kurz genug, damit der Prompt in das Kontextfenster des
 * kleinen Modells passt.
 */
object History {

    /**
     * Behaelt die letzten [maxMessages] Nachrichten und sorgt dafuer, dass der
     * Ausschnitt mit einem Nutzer-Turn beginnt - sonst faengt der Prompt mit
     * einer Antwort ohne Frage an.
     */
    fun trim(history: List<ChatMessage>, maxMessages: Int): List<ChatMessage> {
        require(maxMessages > 0) { "maxMessages muss positiv sein" }
        if (history.size <= maxMessages) return history
        var window = history.subList(history.size - maxMessages, history.size)
        val firstUser = window.indexOfFirst { it.role == Role.USER }
        if (firstUser > 0) window = window.subList(firstUser, window.size)
        return window.toList()
    }
}

/** Sprachbefehle, die den Live-Modus ohne Bildschirmberuehrung beenden. */
object VoiceCommands {

    private val STOP_PHRASES = listOf(
        "stopp", "stop", "beende das gespräch", "beende das gespraech",
        "gespräch beenden", "gespraech beenden", "live modus beenden",
        "auf wiedersehen", "tschüss", "tschuess", "ende der aufnahme",
        "stop the conversation", "goodbye",
    )

    /** true, wenn die Aeusserung nichts als ein Stopp-Befehl ist. */
    fun isStopCommand(utterance: String): Boolean {
        val normalized = utterance.trim().lowercase()
            .trimEnd('.', '!', '?', ',', ' ')
        if (normalized.isEmpty()) return false
        return STOP_PHRASES.any { it == normalized }
    }
}
