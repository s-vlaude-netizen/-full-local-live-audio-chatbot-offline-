package de.localvoice.livechat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {

    @Test
    fun `erkennt Stopp-Befehle unabhaengig von Gross-Kleinschreibung`() {
        assertTrue(VoiceCommands.isStopCommand("Stopp"))
        assertTrue(VoiceCommands.isStopCommand("  auf wiedersehen. "))
        assertTrue(VoiceCommands.isStopCommand("Gespräch beenden"))
    }

    @Test
    fun `ein Satz mit dem Wort stopp ist kein Befehl`() {
        assertFalse(VoiceCommands.isStopCommand("Erklaer mir bitte, was ein Notstopp ist"))
        assertFalse(VoiceCommands.isStopCommand("stopp mal kurz und erklaer das nochmal"))
        assertFalse(VoiceCommands.isStopCommand(""))
    }
}

class SpeechTextTest {

    @Test
    fun `entfernt Markdown vor dem Vorlesen`() {
        assertEquals(
            "Wichtig ist der Punkt hier.",
            SpeechText.forSpeech("**Wichtig** ist der `Punkt` hier."),
        )
    }

    @Test
    fun `entfernt Reste der Chat-Vorlage`() {
        assertEquals("Fertig.", SpeechText.forSpeech("Fertig.<end_of_turn>"))
        assertEquals("Fertig.", SpeechText.forDisplay("Fertig.<end_of_turn>"))
    }

    @Test
    fun `macht aus Aufzaehlungen sprechbaren Text`() {
        assertEquals("Erstens\nZweitens", SpeechText.forSpeech("- Erstens\n- Zweitens"))
    }

    @Test
    fun `laesst normalen Text unangetastet`() {
        val text = "Heute ist Freitag, und es regnet."
        assertEquals(text, SpeechText.forSpeech(text))
    }
}
