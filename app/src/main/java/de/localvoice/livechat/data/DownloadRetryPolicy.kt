package de.localvoice.livechat.data

/**
 * Wann sich ein neuer Versuch lohnt und wann nicht.
 *
 * Ein wackelnder Router ist der Normalfall: die Verbindung faellt fuer ein paar
 * Sekunden weg und kommt wieder. Eine fehlende Lizenzzustimmung dagegen wird
 * auch beim zehnten Versuch nicht besser - dort sofort aufhoeren, sonst
 * wartet der Nutzer eine halbe Minute auf eine Meldung, die schon feststeht.
 */
object DownloadRetryPolicy {

    /** So lange wird nach einem Abbruch gewartet. */
    const val WAIT_MILLIS: Long = 10_000

    /** So viele Fehlschlaege am Stueck werden hingenommen. */
    const val MAX_ATTEMPTS: Int = 3

    /** true, wenn ein erneuter Versuch ueberhaupt Sinn ergibt. */
    fun isRetryable(error: DownloadError?): Boolean = when (error) {
        // Lizenz oder Token fehlen - daran aendert Warten nichts.
        is DownloadError.NeedsToken -> false
        is DownloadError.Message -> error.retryable
        null -> true // Netzwerkfehler ohne eigene Einordnung.
    }

    /**
     * Zaehlt die Fehlschlaege am Stueck.
     *
     * Kam seit dem letzten Fehler auch nur ein Byte an, faengt die Zaehlung von
     * vorn an. Sonst waeren drei Aussetzer ueber eine halbe Stunde verteilt
     * genauso toedlich wie drei in einer Minute - obwohl der Download in der
     * Zwischenzeit gut vorankam.
     */
    fun nextAttempt(previousAttempt: Int, madeProgress: Boolean): Int =
        if (madeProgress) 1 else previousAttempt + 1

    fun givingUp(attempt: Int): Boolean = attempt > MAX_ATTEMPTS
}
