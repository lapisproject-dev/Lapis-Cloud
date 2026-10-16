package network.lapis.cloud.server.encounter

import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION

/**
 * V1.9.143 -- the REFERENCE text of the operator notice an ADMIN acknowledges before switching the optional pulpit recording on. The
 * client shows the same text through gettext (so it is translated); this German reference is what the server pins. [SHA256] is the SHA-256
 * over `"$VERSION\n$TEXT_DE"`, written to the audit entry of the switch-on so that it is provable which text the admin saw.
 *
 * **Why a notice at all.** Whether a recording of a person is lawful, and under which conditions, depends on the country of the instance.
 * Lapis Cloud does not decide that. It makes the feature opt-in per instance and puts the responsibility, in writing, with the operator.
 * The notice is NOT legal advice either; an operator replaces the texts shown to the persons concerned (`REC_ROOM`, `REC_PULPIT`) with
 * legally reviewed ones in the agreement-text administration.
 *
 * **A change of [TEXT_DE] needs a new [VERSION]** (and the same value of [PULPIT_RECORDING_NOTICE_VERSION]): `PulpitRecordingNoticePinTest`
 * pins version and hash as literals and checks that the client source carries every paragraph word for word.
 */
internal object PulpitRecordingNotice {
    const val VERSION: String = PULPIT_RECORDING_NOTICE_VERSION

    /** The paragraphs, separated by a blank line in [TEXT_DE]. Each is one line in the client source (the pin test searches for it). */
    val PARAGRAPHS_DE: List<String> =
        listOf(
            "Sie sind im Begriff, die Aufzeichnung der Kanzel in den Begegnungsräumen dieser Instanz zu erlauben. Aufgezeichnet werden " +
                "ausschließlich Bild und Ton der Person, die das Amt der Kanzel (in Versammlungen: des Podiums) innehat. Die Gemeinde " +
                "(in Versammlungen: die Teilnehmenden) wird nicht aufgezeichnet.",
            "Ob und unter welchen Bedingungen eine solche Aufzeichnung zulässig ist, hängt vom Recht Ihres Landes ab, zum Beispiel vom " +
                "Datenschutz-, Persönlichkeits- und Urheberrecht und von Vorgaben für Religionsgemeinschaften und Versammlungen. Lapis " +
                "Cloud nimmt diese Prüfung nicht vor und ersetzt keine Rechtsberatung.",
            "Als Betreiber sind Sie dafür verantwortlich, dass Sie eine Rechtsgrundlage haben und dass die aufgezeichnete Person " +
                "informiert ist und, wo erforderlich, einwilligt. Die Aufnahme wird in der Dokumentenablage gespeichert, ist nur für " +
                "Vorstand und Administratoren sichtbar und bleibt bis zu ihrer Löschung bestehen.",
            "Zeigt die Kamera Personen, die vor Ort neben der Kanzel anwesend sind, werden auch diese aufgezeichnet. Das kann die " +
                "Software nicht verhindern.",
        )

    val TEXT_DE: String = PARAGRAPHS_DE.joinToString("\n\n")

    /** `SHA-256` over `"$VERSION\n$TEXT_DE"`. */
    val SHA256: String = ConsentHashing.sha256Hex("$VERSION\n$TEXT_DE")
}
