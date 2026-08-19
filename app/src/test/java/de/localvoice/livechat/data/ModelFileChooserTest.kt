package de.localvoice.livechat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelFileChooserTest {

    @Test
    fun `ignoriert alles, was kein litertlm ist`() {
        assertNull(
            ModelFileChooser.pick(
                listOf("README.md", "Gemma3-1B-IT_q4_ekv1280.task", "config.json"),
            ),
        )
    }

    @Test
    fun `bevorzugt die allgemeine Variante vor der chipspezifischen`() {
        val files = listOf(
            "Gemma3-1B-IT_q4_ekv1280_Google_Tensor_G5.litertlm",
            "Gemma3-1B-IT_q4_ekv1280.litertlm",
        )
        assertEquals("Gemma3-1B-IT_q4_ekv1280.litertlm", ModelFileChooser.pick(files))
    }

    @Test
    fun `nimmt die chipspezifische nur, wenn es nichts anderes gibt`() {
        val files = listOf("Gemma3-1B-IT_q8_ekv1280_Snapdragon.litertlm")
        assertEquals("Gemma3-1B-IT_q8_ekv1280_Snapdragon.litertlm", ModelFileChooser.pick(files))
    }

    @Test
    fun `bevorzugt die kleinere Quantisierung`() {
        val files = listOf(
            "Gemma3-1B-IT_q8_ekv1280.litertlm",
            "Gemma3-1B-IT_q4_ekv1280.litertlm",
        )
        assertEquals("Gemma3-1B-IT_q4_ekv1280.litertlm", ModelFileChooser.pick(files))
    }

    @Test
    fun `waehlt bei gleichem Rang immer dieselbe Datei`() {
        val files = listOf("b_q4.litertlm", "a_q4.litertlm")
        assertEquals("a_q4.litertlm", ModelFileChooser.pick(files))
        assertEquals("a_q4.litertlm", ModelFileChooser.pick(files.reversed()))
    }

    @Test
    fun `leeres Verzeichnis liefert nichts`() {
        assertNull(ModelFileChooser.pick(emptyList()))
    }
}
