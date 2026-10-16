package network.lapis.cloud.server.agreementtexts

/**
 * V1.9.144 -- the built-in placeholder of the [network.lapis.cloud.shared.domain.AgreementTextKind.FINDER] kind: the consent a member gives to the
 * optional member finder. Same contract as every other placeholder in this package: immutable at runtime (a wording change needs a NEW `VERSION`),
 * `SHA256` = SHA-256 over `"$VERSION\n$TEXT"`.
 *
 * **This text is a DRAFT, not legal advice.** Party membership is a special category of data; the operator replaces it with a legally reviewed version
 * through the agreement-text administration. A newly published version ends every effective consent until the members consent again.
 * `MemberFinderBuiltinTextPinTest` pins label and hash.
 */
internal object MemberFinderConsentDefault {
    const val VERSION: String = "2026-10-16.finder.v1"
    const val TEXT: String =
        "Ich willige ausdrücklich ein, dass die Organisation, die diese Instanz betreibt, im Mitglieder-Finder meine Region verwendet. " +
            "Die Region wird aus den ersten zwei Ziffern der Postleitzahl meiner hinterlegten Adresse abgeleitet " +
            "und nicht bei mir gespeichert. " +
            "Andere Mitglieder erfahren weder meinen Namen noch meine Region; mein Name wird erst nach beidseitiger Zustimmung sichtbar. " +
            "Gezeigt werden nur ungefähre Gruppengrößen ab fünf Teilnehmenden.\n\n" +
            "Mir ist bewusst, dass meine Teilnahme Rückschlüsse auf meine Mitgliedschaft in dieser Organisation " +
            "und gegebenenfalls auf meine " +
            "politische oder weltanschauliche Überzeugung zulässt (besondere Kategorie personenbezogener Daten, " +
            "Art. 9 DSGVO und vergleichbare " +
            "Regelungen). Ich bestätige, dass ich volljährig bin.\n\n" +
            "Ich kann diese Einwilligung jederzeit widerrufen. Mit dem Widerruf werden meine Anfragen und " +
            "Verbindungen sofort gelöscht. Der " +
            "Nachweis meiner Einwilligung und meines Widerrufs bleibt bis zur Löschung meines Kontos gespeichert."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}
