package network.lapis.cloud.server.agreementtexts

/**
 * V1.9.156 -- the built-in placeholder of the [network.lapis.cloud.shared.domain.AgreementTextKind.EVENT_REC] kind: the consent a participant
 * gives before entering an event room whose organizer allowed recording / live streaming. Same contract as every other placeholder in this
 * package: immutable at runtime (a wording change needs a NEW `VERSION`), `SHA256` = SHA-256 over `"$VERSION\n$TEXT"`.
 *
 * The text follows the paragraph rule of the encounter notices ([network.lapis.cloud.shared.domain.EncounterConsentLayout]): paragraph 1 is
 * the headline, paragraphs 2-4 are the key points, paragraph 5 completes the full text.
 *
 * **This text is a DRAFT, not legal advice.** Whether and how persons may be recorded depends on the country of the instance. Capture cannot be
 * switched on while this placeholder is the live text: the operator publishes its own, legally reviewed version first (and a published text
 * that equals this one is refused).
 */
internal object EventCaptureConsentDefault {
    const val VERSION: String = "2026-10-18.event-rec.v1"
    const val TEXT: String =
        "In diesem Veranstaltungsraum werden Bild und Ton aufgezeichnet und können live übertragen werden.\n\n" +
            "Aufgezeichnet und übertragen werden können Ihr Bild, Ihr Ton und Ihr Anzeigename, sobald Ihre Kamera oder " +
            "Ihr Mikrofon an ist. Wer Kamera und Mikrofon aus lässt, wird mit dem Anzeigenamen in der Teilnehmerliste " +
            "sichtbar, aber nicht in Bild und Ton aufgezeichnet.\n\n" +
            "Die Aufzeichnung wird in der Dokumentenablage der Organisation gespeichert und ist standardmäßig nur für " +
            "Vorstand und Administratoren sichtbar. Die Übertragung geht an die vom Vorstand eingerichteten Ziele. " +
            "Ein Banner im Raum zeigt Ihnen jederzeit, ob gerade aufgezeichnet oder übertragen wird.\n\n" +
            "Ihre Teilnahme ist freiwillig. Sie können den Raum jederzeit verlassen, auch wenn eine Aufzeichnung läuft. " +
            "Wenn Sie mit der Aufzeichnung und Übertragung nicht einverstanden sind, treten Sie bitte nicht bei.\n\n" +
            "Mit Ihrer Bestätigung willigen Sie ausdrücklich ein, dass Ihr Bild und Ihr Ton aufgezeichnet und live " +
            "übertragen werden dürfen. Festgehalten wird nur, dass diese Fassung des Textes für diese Veranstaltung an " +
            "diesem Tag bestätigt wurde, ohne Namen und ohne Uhrzeit. Das Programm kann nicht verhindern, dass andere " +
            "Teilnehmende Bildschirmfotos oder eigene Mitschnitte anfertigen."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}
