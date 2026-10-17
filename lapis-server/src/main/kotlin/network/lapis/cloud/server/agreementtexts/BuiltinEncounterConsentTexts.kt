package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.shared.domain.EncounterConsentLayout

/*
 * V1.9.148 -- the built-in placeholders of the two encounter entry notices ([network.lapis.cloud.shared.domain.AgreementTextKind.ENC_CHURCH] and
 * [network.lapis.cloud.shared.domain.AgreementTextKind.ENC_ASSEM]): the Art. 9 consent a non-member acknowledges before entering a room. Same
 * contract as every placeholder in this package: immutable at runtime (a wording change needs a NEW `VERSION`), `SHA256` = SHA-256 over
 * `"$VERSION\n$TEXT"`.
 *
 * The layout is the paragraph rule of [network.lapis.cloud.shared.domain.EncounterConsentLayout]: paragraph 1 headline, 2-4 key points, the rest
 * the expandable detail. The four earlier objects of `network.lapis.cloud.server.encounter` (V1.9.61/67/143) are NOT read here -- they are kept
 * byte-for-byte only as the proof of consents already given; their strings are copied, never derived (no `substringAfter`).
 *
 * **These texts are DRAFTS, not legal advice.** They say what the server really shows to everybody present (name, seat with initials, table,
 * guest status, reactions, chat, voice with the floor) -- `EncounterConsentTextPinTest` pins those statements. The operator replaces them through
 * the agreement-text administration with a legally reviewed version.
 */
private const val ATTENDANCE_NOT_STORED_CHURCH: String =
    "Wer anwesend ist, wird nicht gespeichert. Die Anwesenheit besteht nur, solange Sie im Raum sind, und wird beim " +
        "Verlassen oder nach dem Ende der Veranstaltung gelöscht. Es gibt keine Teilnahmeliste und keinen Verlauf."

private const val ATTENDANCE_NOT_STORED_ASSEMBLY: String =
    "Wer anwesend ist, wird nicht gespeichert. Die Anwesenheit besteht nur, solange Sie im Raum sind, und wird beim " +
        "Verlassen oder nach dem Ende der Versammlung gelöscht. Es gibt keine Teilnahmeliste und keinen Verlauf."

private const val WHAT_OTHERS_SEE: String =
    "Alle Anwesenden sehen Ihren Namen. Wählen Sie einen Platz im Raum, sehen alle, wo Sie sitzen, mit Ihren Initialen. " +
        "Setzen Sie sich an einen Tisch, sehen alle, an welchem Tisch und auf welchem Platz. " +
        "Ob Sie Gast sind, wird an alle Geräte im Raum übertragen. Ihre Reaktionen, etwa ein Handzeichen, erscheinen an Ihrem Platz."

private const val SPEAKING_AND_CHAT_CHURCH: String =
    "Übertragen wird die Kanzel. Ihr Bild wird nie gesendet. Ihren Ton senden Sie nur, wenn Sie Ihr Mikrofon selbst einschalten: " +
        "am Tisch hören ihn die Personen an Ihrem Tisch, und wenn Ihnen das Wort erteilt wird, hören Sie alle Anwesenden, " +
        "und alle sehen dabei Ihren Namen. Chatnachrichten sehen alle, die gerade im Raum sind, mit Ihrem Namen; " +
        "sie werden nicht gespeichert. ${EncounterConsentLayout.NO_RECORDING_SENTENCE}"

private const val SPEAKING_AND_CHAT_ASSEMBLY: String =
    "Übertragen wird das Podium. Ihr Bild wird nie gesendet. Ihren Ton senden Sie nur, wenn Sie Ihr Mikrofon selbst einschalten: " +
        "am Tisch hören ihn die Personen an Ihrem Tisch, und wenn Ihnen das Wort erteilt wird, hören Sie alle Anwesenden, " +
        "und alle sehen dabei Ihren Namen. Chatnachrichten sehen alle, die gerade im Raum sind, mit Ihrem Namen; " +
        "sie werden nicht gespeichert. ${EncounterConsentLayout.NO_RECORDING_SENTENCE}"

private val DETAIL_CHURCH: String =
    """
    Besondere Kategorie von Daten: Die Teilnahme an einem Gottesdienst kann Rückschlüsse auf religiöse Überzeugungen zulassen. Das
    sind besondere Kategorien personenbezogener Daten (Art. 9 DSGVO). Wir verarbeiten sie nur, wenn Sie ausdrücklich einwilligen
    (Art. 9 Abs. 2 lit. a DSGVO).

    Was gespeichert wird: Als Nachweis Ihrer Einwilligung speichern wir die Version dieses Textes, dessen Prüfsumme und das Datum
    Ihrer Bestätigung, ohne Angabe von Raum oder Uhrzeit. Sonst wird nichts über Ihre Teilnahme gespeichert.

    Ihre Kontrolle: Sie können den Raum jederzeit verlassen. Ihre Einwilligung können Sie jederzeit mit Wirkung für die Zukunft
    widerrufen, indem Sie sich an die verantwortliche Organisation wenden. Die Rechtmäßigkeit der bisherigen Verarbeitung bleibt
    davon unberührt.

    Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text unter einer neuen
    Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
    """.trimIndent()

private val DETAIL_ASSEMBLY: String =
    """
    Besondere Kategorie von Daten: Die Teilnahme an einer Versammlung kann Rückschlüsse auf politische Meinungen oder
    weltanschauliche Überzeugungen zulassen. Das sind besondere Kategorien personenbezogener Daten (Art. 9 DSGVO). Wir verarbeiten
    sie nur, wenn Sie ausdrücklich einwilligen (Art. 9 Abs. 2 lit. a DSGVO).

    Was gespeichert wird: Als Nachweis Ihrer Einwilligung speichern wir die Version dieses Textes, dessen Prüfsumme und das Datum
    Ihrer Bestätigung, ohne Angabe von Raum oder Uhrzeit. Sonst wird nichts über Ihre Teilnahme gespeichert.

    Ihre Kontrolle: Sie können den Raum jederzeit verlassen. Ihre Einwilligung können Sie jederzeit mit Wirkung für die Zukunft
    widerrufen, indem Sie sich an die verantwortliche Organisation wenden. Die Rechtmäßigkeit der bisherigen Verarbeitung bleibt
    davon unberührt.

    Dieser Hinweis ist ein Entwurf und stellt keine Rechtsberatung dar. Ein reales Deployment sollte diesen Text unter einer neuen
    Version durch die eigene, rechtlich geprüfte Fassung ersetzen.
    """.trimIndent()

/** Placeholder of [network.lapis.cloud.shared.domain.AgreementTextKind.ENC_CHURCH]. */
internal object EncounterChurchConsentDefault {
    const val VERSION: String = "2026-10-17.v2"
    val TEXT: String =
        listOf(
            "Sie nehmen an einem Gottesdienst oder einer Andacht teil.",
            ATTENDANCE_NOT_STORED_CHURCH,
            WHAT_OTHERS_SEE,
            SPEAKING_AND_CHAT_CHURCH,
            DETAIL_CHURCH,
        ).joinToString("\n\n")
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}

/** Placeholder of [network.lapis.cloud.shared.domain.AgreementTextKind.ENC_ASSEM]. */
internal object EncounterAssemblyConsentDefault {
    const val VERSION: String = "2026-10-17.assembly.v2"
    val TEXT: String =
        listOf(
            "Sie nehmen an einer Versammlung teil.",
            ATTENDANCE_NOT_STORED_ASSEMBLY,
            WHAT_OTHERS_SEE,
            SPEAKING_AND_CHAT_ASSEMBLY,
            DETAIL_ASSEMBLY,
        ).joinToString("\n\n")
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}
