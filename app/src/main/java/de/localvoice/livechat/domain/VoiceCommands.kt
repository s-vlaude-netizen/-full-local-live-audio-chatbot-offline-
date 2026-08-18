package de.localvoice.livechat.domain

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
