package de.localvoice.livechat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun `gibt erst bei einer sicheren Satzgrenze heraus`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertTrue(chunker.append("Das ist ein Satz").isEmpty())
        // Der Punkt allein reicht nicht - erst das Zeichen danach macht ihn eindeutig.
        assertTrue(chunker.append(".").isEmpty())
        assertEquals(listOf("Das ist ein Satz."), chunker.append(" Und noch einer"))
    }

    @Test
    fun `haelt kurze Bruchstuecke zusammen`() {
        val chunker = SentenceChunker(minChunkChars = 20)
        assertTrue(chunker.append("Ja. ").isEmpty())
        assertEquals(
            listOf("Ja. Genau das meine ich."),
            chunker.append("Genau das meine ich. "),
        )
    }

    @Test
    fun `trennt nicht bei Ordnungszahlen`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertTrue(chunker.append("Am 3. Mai faengt es an").isEmpty())
        assertEquals(listOf("Am 3. Mai faengt es an."), chunker.append(". "))
    }

    @Test
    fun `schneidet ueberlange Ausgaben an einer Wortgrenze`() {
        val chunker = SentenceChunker(minChunkChars = 5, maxChunkChars = 30)
        val chunks = chunker.append("wort ".repeat(20))
        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue(it.length <= 30) }
    }

    @Test
    fun `flush liefert den Rest und leert den Puffer`() {
        val chunker = SentenceChunker()
        chunker.append("Ohne Satzzeichen am Ende")
        assertEquals("Ohne Satzzeichen am Ende", chunker.flush())
        assertNull(chunker.flush())
    }

    @Test
    fun `trennt an Zeilenumbruechen`() {
        val chunker = SentenceChunker(minChunkChars = 5)
        assertEquals(listOf("Erste Zeile"), chunker.append("Erste Zeile\nZweite"))
    }
}
