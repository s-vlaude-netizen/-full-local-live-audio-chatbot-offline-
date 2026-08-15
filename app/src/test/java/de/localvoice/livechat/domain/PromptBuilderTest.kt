package de.localvoice.livechat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun user(text: String) = ChatMessage(1, Role.USER, text, 0)
    private fun assistant(text: String) = ChatMessage(2, Role.ASSISTANT, text, 0)

    @Test
    fun `Gemma bekommt die Systemanweisung in den ersten Nutzer-Turn`() {
        val prompt = PromptBuilder.build(
            PromptTemplate.GEMMA,
            "Antworte kurz.",
            listOf(user("Hallo")),
        )
        assertEquals(
            "<start_of_turn>user\nAntworte kurz.\n\nHallo<end_of_turn>\n<start_of_turn>model\n",
            prompt,
        )
    }

    @Test
    fun `die Systemanweisung steht nur einmal im Prompt`() {
        val prompt = PromptBuilder.build(
            PromptTemplate.GEMMA,
            "SYSTEM",
            listOf(user("eins"), assistant("antwort"), user("zwei")),
        )
        assertEquals(1, prompt.split("SYSTEM").size - 1)
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `ChatML nutzt eine eigene System-Rolle`() {
        val prompt = PromptBuilder.build(PromptTemplate.CHATML, "SYSTEM", listOf(user("Hi")))
        assertTrue(prompt.startsWith("<|im_start|>system\nSYSTEM<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `leere Systemanweisung erzeugt keinen Leerraum`() {
        val prompt = PromptBuilder.build(PromptTemplate.GEMMA, "", listOf(user("Hallo")))
        assertEquals(
            "<start_of_turn>user\nHallo<end_of_turn>\n<start_of_turn>model\n",
            prompt,
        )
    }

    @Test
    fun `der Folge-Turn enthaelt nur die neue Frage`() {
        val turn = PromptBuilder.buildTurn(PromptTemplate.GEMMA, "  Wie spaet ist es?  ")
        assertEquals(
            "<start_of_turn>user\nWie spaet ist es?<end_of_turn>\n<start_of_turn>model\n",
            turn,
        )
    }
}
