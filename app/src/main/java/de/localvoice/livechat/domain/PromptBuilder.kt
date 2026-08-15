package de.localvoice.livechat.domain

/** Chat-Vorlage des jeweiligen Modells. */
enum class PromptTemplate {
    /** Gemma 2 / Gemma 3 (auch die .task-Buendel von Google AI Edge). */
    GEMMA,

    /** Qwen, Phi und Verwandte im ChatML-Format. */
    CHATML,

    /** Roher Text ohne Rollen-Marker - Notnagel fuer unbekannte Modelle. */
    PLAIN,
}

/**
 * Baut aus Systemanweisung + Verlauf den Prompt-String, den die Inferenz bekommt.
 *
 * Gemma kennt keine System-Rolle: die Anweisung wandert deshalb an den Anfang
 * des ersten Nutzer-Turns.
 */
object PromptBuilder {

    fun build(
        template: PromptTemplate,
        systemPrompt: String,
        history: List<ChatMessage>,
    ): String = when (template) {
        PromptTemplate.GEMMA -> buildGemma(systemPrompt, history)
        PromptTemplate.CHATML -> buildChatMl(systemPrompt, history)
        PromptTemplate.PLAIN -> buildPlain(systemPrompt, history)
    }

    /**
     * Nur den neuen Nutzer-Turn plus die Eroeffnung der Modell-Antwort.
     *
     * Wird benutzt, wenn die Sitzung den bisherigen Verlauf schon im
     * Zwischenspeicher haelt und nicht neu berechnet werden soll.
     */
    fun buildTurn(template: PromptTemplate, userText: String): String {
        val text = userText.trim()
        return when (template) {
            PromptTemplate.GEMMA -> "<start_of_turn>user\n$text<end_of_turn>\n<start_of_turn>model\n"
            PromptTemplate.CHATML -> "<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\n"
            PromptTemplate.PLAIN -> "Nutzer: $text\nAssistent:"
        }
    }

    private fun buildGemma(systemPrompt: String, history: List<ChatMessage>): String {
        val sb = StringBuilder()
        var systemPending = systemPrompt.isNotBlank()
        for (message in history) {
            when (message.role) {
                Role.USER -> {
                    sb.append("<start_of_turn>user\n")
                    if (systemPending) {
                        sb.append(systemPrompt.trim()).append("\n\n")
                        systemPending = false
                    }
                    sb.append(message.text.trim()).append("<end_of_turn>\n")
                }

                Role.ASSISTANT -> sb.append("<start_of_turn>model\n")
                    .append(message.text.trim())
                    .append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildChatMl(systemPrompt: String, history: List<ChatMessage>): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(systemPrompt.trim()).append("<|im_end|>\n")
        }
        for (message in history) {
            val role = if (message.role == Role.USER) "user" else "assistant"
            sb.append("<|im_start|>").append(role).append("\n")
                .append(message.text.trim()).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildPlain(systemPrompt: String, history: List<ChatMessage>): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) sb.append(systemPrompt.trim()).append("\n\n")
        for (message in history) {
            val role = if (message.role == Role.USER) "Nutzer" else "Assistent"
            sb.append(role).append(": ").append(message.text.trim()).append("\n")
        }
        sb.append("Assistent:")
        return sb.toString()
    }
}
