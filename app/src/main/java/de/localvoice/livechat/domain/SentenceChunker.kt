package de.localvoice.livechat.domain

/**
 * Zerlegt einen Token-Stream in sprechbare Haeppchen.
 *
 * Das Modell liefert Text Stueck fuer Stueck; die Sprachausgabe soll aber schon
 * loslegen, sobald der erste Satz fertig ist. Der Chunker schneidet deshalb an
 * Satzgrenzen und - falls ein Satz zu lang wird - notfalls an einer Wortgrenze.
 *
 * Nicht thread-sicher: pro Antwort eine Instanz, von einem Coroutine-Kontext aus benutzt.
 */
class SentenceChunker(
    private val minChunkChars: Int = 24,
    private val maxChunkChars: Int = 220,
) {
    private var buffer: String = ""

    /** Haengt neuen Text an und gibt alle jetzt vollstaendigen Haeppchen zurueck. */
    fun append(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer += text
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut() ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer = buffer.substring(cut)
            if (chunk.isNotEmpty()) out += chunk
        }
        return out
    }

    /** Gibt den Rest heraus und leert den Puffer. Am Ende einer Antwort aufrufen. */
    fun flush(): String? {
        val rest = buffer.trim()
        buffer = ""
        return rest.ifEmpty { null }
    }

    private fun findCut(): Int? {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '\n') {
                if (i + 1 >= minChunkChars) return i + 1
                continue
            }
            if (c !in TERMINATORS) continue
            // Eine Satzgrenze gilt erst als sicher, wenn danach schon etwas steht.
            val next = buffer.getOrNull(i + 1) ?: continue
            if (!next.isWhitespace()) continue
            if (i + 1 < minChunkChars) continue
            if (c == '.' && looksLikeAbbreviation(i)) continue
            return i + 1
        }
        if (buffer.length >= maxChunkChars) {
            val space = buffer.lastIndexOf(' ', maxChunkChars - 1)
            if (space >= minChunkChars) return space + 1
        }
        return null
    }

    /** "am 3. Mai" oder "z. B." sind keine Satzenden. */
    private fun looksLikeAbbreviation(dotIndex: Int): Boolean {
        val before = buffer.getOrNull(dotIndex - 1) ?: return false
        if (before.isDigit()) return true
        val beforeBefore = buffer.getOrNull(dotIndex - 2)
        return beforeBefore == null || beforeBefore.isWhitespace()
    }

    private companion object {
        const val TERMINATORS = ".!?…;:"
    }
}
