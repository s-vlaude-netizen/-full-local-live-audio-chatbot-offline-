package de.localvoice.livechat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {

    // Der Katalog braucht inzwischen einen Context; fuer die Regel genuegt
    // ein beliebiger Eintrag.
    private val entry = CatalogEntry(
        repoId = "beispiel/modell",
        title = "Beispiel",
        sizeLabel = "1 GB",
        note = "",
        gated = true,
    )

    @Test
    fun `fehlende Lizenz wird nicht wiederholt`() {
        assertFalse(DownloadRetryPolicy.isRetryable(DownloadError.NeedsToken(entry)))
    }

    @Test
    fun `Netzwerkfehler ohne Einordnung wird wiederholt`() {
        assertTrue(DownloadRetryPolicy.isRetryable(null))
    }

    @Test
    fun `eine fehlende Datei wird nicht wiederholt, ein Serverfehler schon`() {
        assertFalse(
            DownloadRetryPolicy.isRetryable(DownloadError.Message("weg", retryable = false)),
        )
        assertTrue(
            DownloadRetryPolicy.isRetryable(DownloadError.Message("spaeter", retryable = true)),
        )
    }

    @Test
    fun `Fehlschlaege am Stueck werden hochgezaehlt`() {
        var attempt = 1
        attempt = DownloadRetryPolicy.nextAttempt(attempt, madeProgress = false)
        assertEquals(2, attempt)
        attempt = DownloadRetryPolicy.nextAttempt(attempt, madeProgress = false)
        assertEquals(3, attempt)
    }

    @Test
    fun `Fortschritt setzt die Zaehlung zurueck`() {
        val attempt = DownloadRetryPolicy.nextAttempt(3, madeProgress = true)
        assertEquals(1, attempt)
        assertFalse(DownloadRetryPolicy.givingUp(attempt))
    }

    @Test
    fun `nach drei Fehlschlaegen am Stueck wird aufgegeben`() {
        assertFalse(DownloadRetryPolicy.givingUp(3))
        assertTrue(DownloadRetryPolicy.givingUp(4))
    }
}
