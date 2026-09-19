package network.lapis.cloud.client

/**
 * Welle V1.4.21, Audit-Fund (zweiter Durchgang): **Riegel für die Inline-Bestätigung** der beiden
 * geldbewegenden Dialoge in [OpenItemDialogs.kt] (Ausgleichen, Verrechnen).
 *
 * Die Dialoge folgen dem Forstall-Ruling "Bestätigung inline im selben Modal, kein zweites Modal":
 * ein Klick auf den äußeren Knopf ("Ausgleichen …" / "Verrechnen …") baut per `removeAll()` einen
 * frischen Bestätigungskasten mit einem frischen, **entsperrten** Endgültig-Knopf. Der innere Knopf
 * war über `runGuardedAction` gegen Doppelklicks gesichert, der äußere aber nicht -- also ließ sich
 * die Sperre umgehen: während die `settleOpenItem`-Anfrage lief, erzeugte ein zweiter Klick auf
 * "Ausgleichen …" einen neuen, nicht gesperrten "Jetzt ausgleichen"-Knopf, und der buchte einen
 * **zweiten echten Teil-Ausgleich**.
 *
 * Diese Klasse hält genau den Zustand, der dafür nötig ist, **DOM-frei und damit testbar**
 * ([InlineConfirmGateTest]): der äußere Knopf ist gesperrt ([blocked]), solange eine Bestätigung
 * sichtbar ist oder eine Schreibanfrage läuft. Jeder Übergang ist eine Abfrage, kein Kommando --
 * gibt sie `false` zurück, findet der Übergang NICHT statt und der Aufrufer bricht ab.
 *
 * Gewollt NICHT hier: das Sperren des inneren Endgültig-Knopfs. Das bleibt bei
 * [runGuardedAction] (try/finally um den Aufruf, siehe dessen KDoc) -- dieser Riegel ergänzt es,
 * ersetzt es nicht.
 */
internal class InlineConfirmGate {
    /** Ein Bestätigungskasten ist sichtbar. */
    var confirming: Boolean = false
        private set

    /** Die Schreibanfrage läuft. */
    var inFlight: Boolean = false
        private set

    /** Sperrzustand des ÄUSSEREN Knopfs ("Ausgleichen …" / "Verrechnen …"). */
    val blocked: Boolean get() = confirming || inFlight

    /**
     * Klick auf den äußeren Knopf. `false` = es gibt schon eine Bestätigung oder eine laufende
     * Anfrage; der Aufrufer darf dann **keinen** neuen Bestätigungskasten aufbauen.
     */
    fun openConfirmation(): Boolean {
        if (blocked) return false
        confirming = true
        return true
    }

    /**
     * "Zurück", oder eine Eingabeänderung, die die sichtbare Bestätigung entwertet (im
     * Verrechnen-Dialog: neuer Kandidat/Betrag ⇒ neue Vorschau). `false` = eine Anfrage läuft; dann
     * bleibt der Kasten stehen, statt unter der laufenden Buchung weggezogen zu werden.
     */
    fun cancelConfirmation(): Boolean {
        if (inFlight) return false
        confirming = false
        return true
    }

    /**
     * Klick auf den Endgültig-Knopf. `false` = es läuft schon eine Anfrage oder es ist gar keine
     * Bestätigung offen (verwaister Knopf) -- in beiden Fällen wird nichts gesendet.
     */
    fun beginRequest(): Boolean {
        if (inFlight || !confirming) return false
        inFlight = true
        return true
    }

    /**
     * Immer im `finally` aufrufen. [succeeded] `true` schließt auch die Bestätigung (der Dialog geht
     * zu); bei `false` bleibt sie offen, damit der Fehler sichtbar bleibt und "Zurück" erreichbar ist.
     */
    fun endRequest(succeeded: Boolean) {
        inFlight = false
        if (succeeded) confirming = false
    }
}
