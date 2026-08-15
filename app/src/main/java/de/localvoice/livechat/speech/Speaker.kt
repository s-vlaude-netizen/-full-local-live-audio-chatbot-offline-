package de.localvoice.livechat.speech

import kotlinx.coroutines.flow.StateFlow

/** Sprachausgabe mit Warteschlange. */
interface Speaker {

    /** true, solange noch etwas in der Warteschlange steht oder gesprochen wird. */
    val busy: StateFlow<Boolean>

    /** Meldung, falls die Ausgabe nicht offline funktioniert - sonst null. */
    val warning: StateFlow<String?>

    /** Startet die Engine. Gibt false zurueck, wenn gar nichts gesprochen werden kann. */
    suspend fun prepare(): Boolean

    /** Haengt Text hinten an die Warteschlange an. */
    fun enqueue(text: String)

    /** Wartet, bis die Warteschlange leer ist. */
    suspend fun awaitIdle()

    /** Bricht sofort ab und leert die Warteschlange. */
    fun stop()

    fun shutdown()
}
