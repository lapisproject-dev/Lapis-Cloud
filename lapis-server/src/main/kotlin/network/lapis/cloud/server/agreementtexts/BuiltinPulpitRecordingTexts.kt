package network.lapis.cloud.server.agreementtexts

/**
 * V1.9.143 -- the four built-in placeholders of the two pulpit-recording kinds ([network.lapis.cloud.shared.domain.AgreementTextKind.REC_ROOM],
 * [network.lapis.cloud.shared.domain.AgreementTextKind.REC_PULPIT]), one per room profile (church service / assembly). Same contract as every
 * other placeholder in this package: immutable at runtime (a wording change needs a NEW `VERSION`), `SHA256` = SHA-256 over `"$VERSION\n$TEXT"`.
 *
 * **These texts are DRAFTS, not legal advice.** Whether and how a person may be recorded depends on the country of the instance; the operator
 * replaces them with a legally reviewed version through the agreement-text administration (a published version applies to both profiles).
 * `PulpitRecordingBuiltinTextPinTest` pins label and hash of all four.
 */
internal object RecRoomChurchDefault {
    const val VERSION: String = "2026-10-16.rec-room.v1"
    const val TEXT: String = "Die Kanzel wird aufgezeichnet, die Gemeinde nicht."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}

internal object RecRoomAssemblyDefault {
    const val VERSION: String = "2026-10-16.rec-room-assembly.v1"
    const val TEXT: String = "Das Podium wird aufgezeichnet, die Teilnehmenden nicht."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}

internal object RecPulpitChurchDefault {
    const val VERSION: String = "2026-10-16.rec-pulpit.v1"
    const val TEXT: String =
        "Ihre Stimme und Ihr Bild an der Kanzel werden aufgezeichnet und in der Dokumentenablage der Organisation gespeichert, " +
            "bis sie gelöscht werden. Die Gemeinde wird nicht aufgezeichnet."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}

internal object RecPulpitAssemblyDefault {
    const val VERSION: String = "2026-10-16.rec-pulpit-assembly.v1"
    const val TEXT: String =
        "Ihre Stimme und Ihr Bild am Podium werden aufgezeichnet und in der Dokumentenablage der Organisation gespeichert, " +
            "bis sie gelöscht werden. Die Teilnehmenden werden nicht aufgezeichnet."
    val SHA256: String = AgreementTextHash.of(versionLabel = VERSION, body = TEXT)
}
