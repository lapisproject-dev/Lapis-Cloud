package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import network.lapis.cloud.server.db.generated.FriendTermsAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [MembershipAgreementAcknowledgmentTable]/[PasswordResetTokenTable] (V0.7.2 Beitritts-/
 * Registrierungs-Workflow) -- the two genuinely NEW member-FK-bearing tables this wave adds (the
 * board-decision metadata on `member` itself is covered by [FoundationPersonalData], not here --
 * it extends a table Foundation already owns).
 *
 * Two very different retention treatments, same split reasoning [AuctionPersonalData] already
 * applies across its own three tables:
 *
 * - **[MembershipAgreementAcknowledgmentTable]: retained on erasure, never deleted/anonymized.**
 *   Proof that this member (as the registrant) explicitly accepted the Beitrittsvertrag/Satzung
 *   at a given version/hash and moment -- an accountability/contract-law record (Art. 17(3)(b)
 *   DSGVO: erasure does not apply where processing is necessary for compliance with a legal
 *   obligation, here evidencing how a private-law membership contract was formed), exactly the
 *   same class as `auction_compliance_acknowledgment` (see [AuctionPersonalData] KDoc).
 * - **[PasswordResetTokenTable]: no retention duty, hard-deleted regardless of [ErasureMode].**
 *   A reset token is a purely transient access-control artifact (which bearer secret currently
 *   authorizes a password change), not an activity record anyone has a legal or organizational
 *   interest in keeping -- same "no retention duty" treatment [SessionPersonalData] already
 *   applies to `session` for the identical reason.
 *
 * **V0.11.0 FRIEND self-registration additions -- both hard-deleted, no retention duty:**
 * - **[FriendTermsAcknowledgmentTable]**: unlike [MembershipAgreementAcknowledgmentTable] (a real
 *   private-law Beitrittsvertrag consent record with a genuine accountability retention duty), the
 *   FRIEND terms acknowledgment documents a much smaller, non-contractual act (see
 *   [network.lapis.cloud.server.rpc.FriendTermsDisclaimer] KDoc) with no comparable retention
 *   interest -- hard-deleted just like [PasswordResetTokenTable].
 * - **[FriendEmailVerificationTokenTable]**: same "purely transient access-control artifact" reasoning
 *   as [PasswordResetTokenTable] -- hard-deleted regardless of [ErasureMode].
 */
object RegistrationPersonalData : PersonalDataContributor {
    override val sectionKey = "registration"
    override val displayName = "Beitritts-/Registrierungs-Workflow"
    override val coveredTables =
        setOf(
            MembershipAgreementAcknowledgmentTable,
            PasswordResetTokenTable,
            FriendTermsAcknowledgmentTable,
            FriendEmailVerificationTokenTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            put(
                "membershipAgreementAcknowledgments",
                buildJsonArray {
                    MembershipAgreementAcknowledgmentTable
                        .selectAll()
                        .where { MembershipAgreementAcknowledgmentTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[MembershipAgreementAcknowledgmentTable.id].toString())
                                    put("agreementVersion", row[MembershipAgreementAcknowledgmentTable.agreementVersion])
                                    put("acknowledgedAt", row[MembershipAgreementAcknowledgmentTable.acknowledgedAt].toString())
                                },
                            )
                        }
                },
            )
            // password_reset_token / friend_email_verification_token deliberately NOT exported --
            // a hash of a bearer secret, not meaningful personal data on its own, same reasoning
            // SessionPersonalData already gives for omitting SessionTable.tokenHash from its own
            // export.
            put(
                "friendTermsAcknowledgments",
                buildJsonArray {
                    FriendTermsAcknowledgmentTable
                        .selectAll()
                        .where { FriendTermsAcknowledgmentTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("id", row[FriendTermsAcknowledgmentTable.id].toString())
                                    put("termsVersion", row[FriendTermsAcknowledgmentTable.termsVersion])
                                    put("acknowledgedAt", row[FriendTermsAcknowledgmentTable.acknowledgedAt].toString())
                                },
                            )
                        }
                },
            )
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val ackCount =
            MembershipAgreementAcknowledgmentTable
                .selectAll()
                .where { MembershipAgreementAcknowledgmentTable.memberId eq memberId }
                .count()
        val tokensDeleted = PasswordResetTokenTable.deleteWhere { PasswordResetTokenTable.memberId eq memberId }
        val friendTermsDeleted = FriendTermsAcknowledgmentTable.deleteWhere { FriendTermsAcknowledgmentTable.memberId eq memberId }
        val friendTokensDeleted =
            FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.memberId eq memberId }

        return listOf(
            TableErasureOutcome(
                table = "membership_agreement_acknowledgment",
                rowsRetained = ackCount.toInt(),
                retentionReason =
                    "Proof of Beitrittsvertrag/Satzung consent -- retained for the same contract-accountability " +
                        "reason as auction_compliance_acknowledgment (Art. 17(3)(b) DSGVO).",
            ),
            TableErasureOutcome(table = "password_reset_token", rowsDeleted = tokensDeleted),
            TableErasureOutcome(table = "friend_terms_acknowledgment", rowsDeleted = friendTermsDeleted),
            TableErasureOutcome(table = "friend_email_verification_token", rowsDeleted = friendTokensDeleted),
        )
    }
}
