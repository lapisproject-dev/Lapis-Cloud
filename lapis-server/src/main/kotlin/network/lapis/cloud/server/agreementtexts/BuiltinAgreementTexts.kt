package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.server.rpc.FriendTermsDisclaimer
import network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer
import network.lapis.cloud.shared.domain.AgreementTextKind

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
    fun of(kind: AgreementTextKind): BuiltinText =
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
        }
}
