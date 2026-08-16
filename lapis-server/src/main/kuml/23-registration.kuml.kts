// Beitritts-/Registrierungs-Workflow domain (V0.7.2) -- see
// network.lapis.cloud.shared.rpc.IRegistrationService KDoc and
// network.lapis.cloud.server.rpc.RegistrationService KDoc for the full fachlich model
// (silence-is-approval NOT applying to membership admission, the ABGELEHNT vs. AUSGETRETEN
// distinction, and the row-lock + compare-and-swap concurrency contract shared with
// CrowdfundingService's own approve/reject).
//
// This file only carries the TWO tables genuinely new to this wave --
// membership_agreement_acknowledgment and password_reset_token. The board-decision metadata
// (member.reviewed_by/reviewed_at/rejection_reason) and the new MemberStatus.REJECTED literal
// are both modelled in 00-foundation.kuml.kts (they extend the `member` entity/enum Foundation
// already owns, not a new entity) -- see that file's own V0.7.2 comments.
//
// **membership_agreement_acknowledgment**: the append-only proof record that a registrant echoed
// back the CURRENT, versioned+hashed Beitrittsvertrag/Satzungs-text (see
// network.lapis.cloud.server.rpc.MembershipAgreementDisclaimer) before self-registering -- same
// three-column (version/sha256/acknowledgedAt) shape as auction_compliance_acknowledgment
// (21-auction.kuml.kts), plus member_id (auction's acknowledger is always an ADMIN acting for the
// org; here the acknowledger IS the new applicant, hence the FK).
//
// **password_reset_token**: single-use, short-TTL, hash-only-persisted reset token -- same shape
// as `session` (22-session.kuml.kts): only token_hash is ever stored (SHA-256, never the raw
// bearer-usable token, reusing network.lapis.cloud.server.security.SessionTokens unchanged), never
// the raw token itself. consumed_at is nullable (null until the token is used exactly once, see
// network.lapis.cloud.server.security.PasswordResetTokenStore.consumeToken's compare-and-swap
// consumption) -- same "compute liveness from nullable timestamp columns" idiom session.revoked_at
// already establishes.
//
// Both tables' member_id are modelled as plain «Column» UUID attributes with «Column».fkEntity,
// NOT UML associations -- same idiom 22-session.kuml.kts's own file header documents at length
// (session.member_id is looked up by an unauthenticated caller's token, never resolved via
// resolveCurrentMember first; the same is true here for password_reset_token, and
// membership_agreement_acknowledgment is written in the SAME unauthenticated registerApplication
// call). This file, symmetrically, carries a minimal id-only Member stub (owned by Foundation)
// purely so UmlToErmTransformer can resolve the «Column».fkEntity override within this
// single-file evaluation -- same cross-domain-stub pattern every other domain in this codebase
// already establishes.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Registration") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val membershipAgreementAcknowledgment = classOf(name = "MembershipAgreementAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "membership_agreement_acknowledgment"
            "kotlinObjectName" to "MembershipAgreementAcknowledgmentTable"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_membership_agreement_acknowledgment_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        attribute(name = "agreementVersion", type = "String") {
            stereotype("Column") { "columnName" to "agreement_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "agreementSha256", type = "String") {
            stereotype("Column") { "columnName" to "agreement_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // V0.11.0 FRIEND self-registration -- append-only proof record that a FRIEND registrant echoed
    // back the CURRENT, versioned+hashed FriendTermsDisclaimer before self-registering. Deliberately
    // a SEPARATE table from membershipAgreementAcknowledgment, not a reused one with a discriminator
    // column -- these are two distinct legal acts (FRIEND terms vs. the Satzung/Beitrittsvertrag),
    // and this codebase's habit is to keep distinct legal acts in distinct tables (compare
    // auction_compliance_acknowledgment vs. membership_agreement_acknowledgment, which are also
    // structurally near-identical but never merged).
    val friendTermsAcknowledgment = classOf(name = "FriendTermsAcknowledgment") {
        stereotype("Entity") {
            "tableName" to "friend_terms_acknowledgment"
            "kotlinObjectName" to "FriendTermsAcknowledgmentTable"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_friend_terms_acknowledgment_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "acknowledgedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "acknowledged_at" }
        }
        attribute(name = "termsVersion", type = "String") {
            stereotype("Column") { "columnName" to "terms_version"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "termsSha256", type = "String") {
            stereotype("Column") { "columnName" to "terms_sha256"; "sqlType" to "VARCHAR(64)" }
        }
    }

    // V0.11.0 friend-email-verification mechanic (see 00-foundation.kuml.kts member.emailVerifiedAt)
    // -- structural clone of passwordResetToken below (same single-use, hash-only-persisted
    // discipline), deliberately a SEPARATE table: a password-reset token and a first-contact email-
    // verification token are different bearer credentials with different TTLs/threat models.
    val friendEmailVerificationToken = classOf(name = "FriendEmailVerificationToken") {
        stereotype("Entity") {
            "tableName" to "friend_email_verification_token"
            "kotlinObjectName" to "FriendEmailVerificationTokenTable"
        }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_friend_email_verification_token_member" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_friend_email_verification_token_expires_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "tokenHash", type = "String") {
            stereotype("Column") { "columnName" to "token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "consumedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consumed_at" }
        }
    }

    val passwordResetToken = classOf(name = "PasswordResetToken") {
        stereotype("Entity") { "tableName" to "password_reset_token"; "kotlinObjectName" to "PasswordResetTokenTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_password_reset_token_member" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_password_reset_token_expires_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "tokenHash", type = "String") {
            stereotype("Column") { "columnName" to "token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "consumedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consumed_at" }
        }
    }
}
