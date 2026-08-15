package de.localvoice.livechat.speech

import kotlinx.coroutines.flow.StateFlow

/** Ergebnis eines Hoer-Durchgangs. */
sealed interface SttResult {
    /** Erkannter Text, garantiert nicht leer. */
    data class Text(val text: String) : SttResult

    /** Niemand hat gesprochen oder es war nichts Verwertbares dabei. */
    data object Silence : SttResult

    /** Harter Fehler; [recoverable] false heisst: ohne Zutun geht es nicht weiter. */
    data class Failure(val message: String, val recoverable: Boolean) : SttResult
}

/** Spracherkennung, die einen Redebeitrag aufnimmt und als Text zurueckgibt. */
interface SpeechToText {

    /** Zwischenergebnis waehrend des Sprechens, fuer die Anzeige. */
    val partialText: StateFlow<String>

    /** Lautstaerke 0..1, fuer die Animation. */
    val level: StateFlow<Float>

    /** Hoert einmal zu, bis der Sprecher fertig ist. Suspendiert bis dahin. */
    suspend fun listenOnce(): SttResult

    /** Bricht ein laufendes Zuhoeren ab. */
    fun abort()

    fun destroy()
}
