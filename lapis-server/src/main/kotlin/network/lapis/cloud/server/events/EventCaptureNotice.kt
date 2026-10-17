package network.lapis.cloud.server.events

import network.lapis.cloud.server.encounter.ConsentHashing
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION

/**
 * V1.9.156 -- the REFERENCE text of the operator notice an ADMIN acknowledges before switching on "recording and live streaming in event
 * rooms". The client shows the same text through gettext (so it is translated); this German reference is what the server pins. [SHA256] is the
 * SHA-256 over `"$VERSION\n$TEXT_DE"`, written to the audit entry of the switch-on so that it is provable which text the admin saw.
 *
 * **Why a notice at all.** Whether a recording of persons is lawful, and under which conditions, depends on the country of the instance. Lapis
 * Cloud does not decide that. It makes the feature opt-in per instance AND per event and puts the responsibility, in writing, with the operator.
 * The notice is NOT legal advice; an operator replaces the consent text shown to the persons concerned (`EVENT_REC`) with a legally reviewed one
 * in the agreement-text administration -- and cannot switch the feature on before it has done so.
 *
 * **A change of [TEXT_DE] needs a new [VERSION]** (and the same value of [EVENT_CAPTURE_NOTICE_VERSION]): `EventCaptureNoticePinTest` pins
 * version and hash as literals and checks that the client source carries every paragraph word for word.
 */
internal object EventCaptureNotice {
    const val VERSION: String = EVENT_CAPTURE_NOTICE_VERSION

    /** The paragraphs, separated by a blank line in [TEXT_DE]. Each is one line in the client source (the pin test searches for it). */
    val PARAGRAPHS_DE: List<String> =
        listOf(
            "Sie sind im Begriff, die Aufzeichnung und die Live-Übertragung in den Online-Räumen von Veranstaltungen dieser Instanz zu " +
                "erlauben. Aufgezeichnet und übertragen werden können Bild, Ton und Anzeigename aller Personen im Raum, nicht nur der " +
                "Vortragenden. Der Vorstand entscheidet danach für jede Veranstaltung einzeln.",
            "Ob und unter welchen Bedingungen eine solche Aufzeichnung zulässig ist, hängt vom Recht Ihres Landes ab, zum Beispiel vom " +
                "Datenschutz-, Persönlichkeits- und Urheberrecht. Bei Veranstaltungen kann ein Teil der Daten besondere Kategorien " +
                "betreffen, etwa die politische oder religiöse Überzeugung. Lapis Cloud nimmt diese Prüfung nicht vor und ersetzt keine " +
                "Rechtsberatung.",
            "Als Betreiber sind Sie dafür verantwortlich, dass Sie eine Rechtsgrundlage haben und dass jede Person vor dem Betreten des " +
                "Raums informiert ist und, wo erforderlich, einwilligt. Lapis Cloud verlangt dafür von jeder Person eine ausdrückliche " +
                "Bestätigung des Einwilligungstextes und hält sie ohne Namen und ohne Uhrzeit fest. Das ersetzt keine Prüfung, ob die " +
                "Einwilligung freiwillig und ausreichend ist.",
            "Das Programm kann nicht verhindern, dass Teilnehmende Bildschirmfotos oder eigene Mitschnitte anfertigen. Der Einwilligungs" +
                "text, der angezeigt wird, muss von Ihnen vor dem Einschalten veröffentlicht werden.",
        )

    val TEXT_DE: String = PARAGRAPHS_DE.joinToString("\n\n")

    /** `SHA-256` over `"$VERSION\n$TEXT_DE"`. */
    val SHA256: String = ConsentHashing.sha256Hex("$VERSION\n$TEXT_DE")
}
