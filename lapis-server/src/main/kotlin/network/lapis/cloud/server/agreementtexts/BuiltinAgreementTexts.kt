package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.server.rpc.FriendTermsDisclaimer
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterProfile

/** The built-in placeholder of one kind: the text an instance shows until its admin publishes its own, legally reviewed version. */
internal data class BuiltinText(
    val versionLabel: String,
    val body: String,
    val sha256: String,
)

/**
 * V1.9.134 -- reads the two placeholder objects. [MembershipAgreementDisclaimer] and [FriendTermsDisclaimer] stay byte-for-byte unchanged
 * (text, version, hash); they are the standard text while no database version is published.
 */
internal object BuiltinAgreementTexts {
    /**
     * The placeholder of [kind]. MEMBERSHIP and FRIEND ignore [profile]; REC_ROOM and REC_PULPIT have one placeholder per room profile
     * (V1.9.143), because "Kanzel/Gemeinde" and "Podium/Teilnehmende" must not be mixed up. A PUBLISHED version of a kind applies to both profiles.
     */
    fun of(
        kind: AgreementTextKind,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    ): BuiltinText =
        when (kind) {
            AgreementTextKind.MEMBERSHIP ->
                BuiltinText(
                    versionLabel = MembershipAgreementDisclaimer.VERSION,
                    body = MembershipAgreementDisclaimer.TEXT,
                    sha256 = MembershipAgreementDisclaimer.SHA256,
                )
            AgreementTextKind.FRIEND ->
                BuiltinText(
                    versionLabel = FriendTermsDisclaimer.VERSION,
                    body = FriendTermsDisclaimer.TEXT,
                    sha256 = FriendTermsDisclaimer.SHA256,
                )
            AgreementTextKind.REC_ROOM ->
                when (profile) {
                    EncounterProfile.CHURCH_SERVICE ->
                        BuiltinText(
                            versionLabel = RecRoomChurchDefault.VERSION,
                            body = RecRoomChurchDefault.TEXT,
                            sha256 = RecRoomChurchDefault.SHA256,
                        )
                    EncounterProfile.ASSEMBLY ->
                        BuiltinText(
                            versionLabel = RecRoomAssemblyDefault.VERSION,
                            body = RecRoomAssemblyDefault.TEXT,
                            sha256 = RecRoomAssemblyDefault.SHA256,
                        )
                }
            AgreementTextKind.REC_PULPIT ->
                when (profile) {
                    EncounterProfile.CHURCH_SERVICE ->
                        BuiltinText(
                            versionLabel = RecPulpitChurchDefault.VERSION,
                            body = RecPulpitChurchDefault.TEXT,
                            sha256 = RecPulpitChurchDefault.SHA256,
                        )
                    EncounterProfile.ASSEMBLY ->
                        BuiltinText(
                            versionLabel = RecPulpitAssemblyDefault.VERSION,
                            body = RecPulpitAssemblyDefault.TEXT,
                            sha256 = RecPulpitAssemblyDefault.SHA256,
                        )
                }
        }

    /** Every placeholder of [kind] (two for the REC kinds, one otherwise): what a published text must differ from and must not collide with. */
    fun allOf(kind: AgreementTextKind): List<BuiltinText> = EncounterProfile.entries.map { of(kind = kind, profile = it) }.distinct()
}
