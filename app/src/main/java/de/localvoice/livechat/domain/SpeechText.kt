package de.localvoice.livechat.domain

/**
 * Raeumt Modellausgaben so auf, dass sie vorgelesen werden koennen.
 *
 * Kleine Modelle rutschen gern in Markdown oder lassen Reste ihrer
 * Chat-Vorlage stehen. Beides wuerde die Sprachausgabe Zeichen fuer Zeichen
 * mitsprechen ("Sternchen Sternchen").
 */
object SpeechText {

    private val CONTROL_TOKENS = listOf(
        "<end_of_turn>", "<start_of_turn>", "<eos>", "<bos>",
        "<|im_end|>", "<|im_start|>", "<|endoftext|>",
    )

    /** Fuer die Sprachausgabe. */
    fun forSpeech(raw: String): String {
        var text = stripControlTokens(raw)
        text = text.replace(Regex("```[\\s\\S]*?```"), " ")
        text = text.replace(Regex("[*_`#>]+"), "")
        // Aufzaehlungsstriche am Zeilenanfang klingen sonst wie "Minus".
        text = text.replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.replace(Regex("\\n{2,}"), "\n")
        return text.trim()
    }

    /** Fuer die Anzeige: nur die Vorlagen-Reste entfernen, Formatierung bleibt. */
    fun forDisplay(raw: String): String = stripControlTokens(raw).trim()

    private fun stripControlTokens(raw: String): String {
        var text = raw
        for (token in CONTROL_TOKENS) text = text.replace(token, "")
        return text
    }
}
