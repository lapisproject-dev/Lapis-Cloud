// Foundation domain — member/account (V1__foundation.sql), plus the forward-referenced
// membership_tier_id FK column added by V2__contributions.sql.
//
// V0.4.1: `member` gains a minimal, single, nullable postal address (street/postalCode/city/
// country) -- needed by the Serienbrief/PDF-Engine (Beitragsrechnung/Spendenbescheinigung/
// Einladung all mail-merge a member's postal address) and reused as-is by V0.4.2's later postal
// (Letterxpress) dispatch. Deliberately no separate Address entity/billing-vs-shipping split --
// see 02 Projekte/Lapis Cloud V0.4.md scope guidance. All four fields are nullable: not every
// member has provided a postal address yet, and an email-only member may never need one.
//
// V0.5.2: `member` gains two further nullable beneficial-owner PII fields, dateOfBirth and
// nationality -- required content for a Transparenzregister (§20 GwG) beneficial-owner entry (see
// 13-transparenzregister.kuml.kts), which the pre-V0.5.2 schema had no way to express. Both
// nullable: not every member is a board member, and a board member's data may not be complete yet
// (see BeneficialOwnerDataGapDto in the Transparenzregister domain). Treated as PII exactly like
// the V0.4.1 postal-address fields -- see FoundationPersonalData for export/erasure coverage.
//
// V0.7.2 Beitritts-/Registrierungs-Workflow: `member.status` gains the ABGELEHNT literal (a
// board-rejected ANTRAG, retained with a reason -- never reused as AUSGETRETEN, see
// network.lapis.cloud.shared.domain.MemberStatus KDoc), and `member` gains three nullable
// board-decision-metadata columns (reviewedBy/reviewedAt/rejectionReason), same shape as
// crowdfunding_project's own reviewedBy/reviewedAt/rejectionReason (17-crowdfunding.kuml.kts) --
// except reviewedBy here is genuinely self-referential (member -> member), see that attribute's
// own comment below for why it deliberately has NO fkEntity tag. The two genuinely NEW entities
// this wave adds (membership_agreement_acknowledgment, password_reset_token) live in
// 23-registration.kuml.kts, not here -- this file only extends the `member` entity/enum Foundation
// already owns.
//
// V0.11.0: `MemberStatus` literals renamed German -> English (ANTRAG->APPLICATION,
// AKTIV->ACTIVE, GAST->GUEST, AUSGETRETEN->WITHDRAWN, ABGELEHNT->REJECTED) plus a new FRIEND
// literal -- a self-registerable, board-approval-free, identity-unverified NON-membership
// account, scoped to video-conference access only (network.lapis.cloud.shared.domain
// .MemberStatusSets.CONFERENCE_ELIGIBLE). `member` gains `friendSince` (set once on FRIEND
// self-registration, never cleared) and `emailVerifiedAt` (V0.11.0 friend-email-verification
// mechanic, see 23-registration.kuml.kts `friend_email_verification_token`). See Flyway
// `V3__member_status_english_and_friend.sql` for the real-data migration on an already-`V1`
// baseline.
//
// V1.2.11 (PdV-CSV-Import, operator-run `MemberCsvImport` CLI): `MemberStatus` gains two source-
// CRM statuses this codebase had no prior equivalent for, DONOR (financial supporter, not a
// member) and DECEASED (terminal, login-blocked) -- see network.lapis.cloud.shared.domain
// .MemberStatus KDoc for the full rationale and the deliberate distinction from the accounting
// module's `external_donor` booking entity. `member` also gains `externalReference`, the source-
// CRM's own person number, set only for imported rows (NULL for organically created members). See
// Flyway `V10__member_donor_deceased_and_external_reference.sql` for the real-data migration on an
// already-`V1` baseline.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.FoundationTables.kt) by SchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Every attribute carries an explicit «Column»{columnName} tag (not just the ones that need
// overriding) — establishes the per-file naming-tag convention this retrofit's later domain
// waves reuse, and keeps the generated-vs-hand-written structural diff trivial to reason about.
//
// account.member_id is UNIQUE in the real schema (V1__foundation.sql: UNIQUE REFERENCES
// member (id)) — UmlToErmTransformer.addForeignKey always synthesizes association-derived FK
// columns with unique=false (no «Column» stereotype can be applied to a UML-association-derived
// attribute), so this can't be pinned via «Column».unique. Pinned instead via a class-level
// «Index» (single-column, unique=true) on Account — renders as a named CREATE UNIQUE INDEX rather
// than an inline column constraint, but semantically identical (enforces the same 1:1).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Foundation") {
    applyProfile(ermMappingProfile)

    val memberStatus = enumOf(name = "MemberStatus") {
        literal(name = "APPLICATION")
        literal(name = "ACTIVE")
        literal(name = "GUEST")
        literal(name = "WITHDRAWN")
        // V0.7.2 Beitritts-Workflow: a board-rejected APPLICATION lands here, retained with a
        // rejectionReason -- never silently reused as WITHDRAWN, which means something
        // structurally different ("left after having been admitted"). See
        // network.lapis.cloud.shared.domain.MemberStatus KDoc.
        literal(name = "REJECTED")
        // V0.11.0: self-registered, board-approval-free, identity-unverified NON-membership
        // account. Scope today: video conferencing ONLY (MemberStatusSets.CONFERENCE_ELIGIBLE).
        // No Beitragspflicht, no governance/accounting/LTR rights. Upgradeable to ACTIVE via the
        // regular APPLICATION route -- see `friendSince` below and IRegistrationService
        // .applyForMembership.
        literal(name = "FRIEND")
        // V1.2.11 PdV-CSV-Import: two source-CRM statuses with no prior equivalent here. DONOR =
        // Spender/Foerderer (financial supporter, NOT a member, no Beitragspflicht, in NO
        // MemberStatusSets capability set). Bewusst NICHT identisch mit der external_donor-Entitaet
        // aus 10-accounting.kuml.kts (§25 PartG Buchungs-Spenderidentitaet) -- hier ein
        // MITGLIEDSCHAFTSSTATUS einer member-Zeile, dort eine Buchungsentitaet.
        literal(name = "DONOR")
        // DECEASED = verstorben. Terminal status, login-blocked (MemberStatusSets.LOGIN_BLOCKED).
        literal(name = "DECEASED")
    }

    val accountRole = enumOf(name = "AccountRole") {
        literal(name = "MEMBER")
        literal(name = "BOARD")
        literal(name = "TREASURER")
        literal(name = "ADMIN")
    }

    val membershipTier = classOf(name = "MembershipTier") {
        stereotype("Entity") { "tableName" to "membership_tier"; "kotlinObjectName" to "MembershipTierTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "displayName", type = "String") {
            stereotype("Column") { "columnName" to "display_name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "email", type = "String") {
            stereotype("Column") { "columnName" to "email"; "sqlType" to "VARCHAR(320)"; "unique" to true }
        }
        attribute(name = "status", type = memberStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.MemberStatus" }
        }
        attribute(name = "joinedAt", type = "LocalDate") {
            stereotype("Column") { "columnName" to "joined_at" }
        }
        attribute(name = "anonymizedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "anonymized_at" }
        }
        // V0.4.1 postal address (Serienbrief/PDF engine) -- see file header. All four nullable.
        attribute(name = "street", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "street"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "postalCode", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "postal_code"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "city", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "city"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "country", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "country"; "sqlType" to "VARCHAR(100)" }
        }
        // V0.5.2 Transparenzregister beneficial-owner fields -- see file header. Both nullable.
        attribute(name = "dateOfBirth", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "date_of_birth" }
        }
        attribute(name = "nationality", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "nationality"; "sqlType" to "VARCHAR(100)" }
        }
        // V0.7.2 Beitritts-Workflow board-decision metadata -- see network.lapis.cloud.server.rpc.
        // RegistrationService KDoc. All three nullable: null until a board decision is made (or
        // forever, for a member created directly via createMemberDirect / a still-ANTRAG applicant).
        //
        // reviewedBy is genuinely self-referential (member -> member) -- UmlToErmTransformer
        // explicitly skips self-referential UML associations (same as document_folder.parent_folder_id,
        // see 02-document.kuml.kts file header "self/circular-reference cases"), so this is a plain
        // UUID «Column» attribute with NO fkEntity tag -- do NOT add fkEntity="Member" here (that
        // would be the natural-looking but WRONG move: unlike crowdfunding_project.reviewed_by
        // -- which references a DIFFERENT table, member, and correctly uses fkEntity="Member" --
        // this reviewedBy is on member itself, so fkEntity="Member" here would attempt a
        // self-referential FK, exactly the case UmlToErmTransformer/this codebase's own convention
        // deliberately avoids).
        attribute(name = "reviewedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_by" }
        }
        attribute(name = "reviewedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_at" }
        }
        attribute(name = "rejectionReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rejection_reason"; "sqlType" to "VARCHAR(1000)" }
        }
        // V0.11.0 FRIEND self-registration. Set on FRIEND self-registration, NEVER cleared. Two
        // jobs: (1) keeps a friend-originated membership application conference-eligible while it
        // is pending (see MemberStatusSets.CONFERENCE_ELIGIBLE), and (2) tells rejectApplication
        // to fall back to FRIEND rather than REJECTED, so a declined membership application does
        // not also destroy the friend account.
        attribute(name = "friendSince", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "friend_since" }
        }
        // V0.11.0 friend-email-verification mechanic (see 23-registration.kuml.kts
        // friend_email_verification_token). Null until the member consumes a verification token
        // -- or forever, while LAPIS_FRIEND_REQUIRE_EMAIL_VERIFICATION stays at its default false.
        attribute(name = "emailVerifiedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "email_verified_at" }
        }
        // V1.2.11 PdV-CSV-Import: the source-CRM's own person number. Set only for imported rows,
        // NULL for organically created members -- deliberately no `unique` tag, since a partial
        // unique index (`WHERE external_reference IS NOT NULL`) is not expressible under H2's
        // MODE=PostgreSQL test dialect (same limitation V9__dunning.sql's uq_dunning_notice_slot
        // and SepaMandateTable already document). Uniqueness is instead enforced procedurally by
        // MemberCsvImport's own idempotency check.
        attribute(name = "externalReference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "external_reference"; "sqlType" to "VARCHAR(50)" }
        }
    }

    association(source = membershipTier, target = member, id = "assoc-member-membership-tier") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "membershipTierId" }
    }

    val account = classOf(name = "Account") {
        stereotype("Entity") { "tableName" to "account"; "kotlinObjectName" to "AccountTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_account_member_id" }
        // V0.8.2 OIDC-Gastzugang-Federation: (oidc_issuer, oidc_subject) jointly identify a
        // federated principal (iss+sub, globally unique per OIDC spec) -- unique together so the
        // same home-server identity always resolves to the same local guest Member row on repeat
        // visits. Postgres/H2 both treat NULL as distinct per unique-index row, so ordinary local
        // accounts (both columns NULL) never collide with each other or with a real guest row --
        // see 25-oidc-guest-federation.kuml.kts file header.
        stereotype("Index") {
            "columns" to listOf("oidc_issuer", "oidc_subject")
            "unique" to true
            "name" to "uq_account_oidc_issuer_subject"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "passwordHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "password_hash"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "oidcSubject", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "oidc_subject"; "sqlType" to "VARCHAR(200)" }
        }
        // V0.8.2 OIDC-Gastzugang-Federation: the federated identity's issuer (home-server origin),
        // paired with oidcSubject above -- see this class's «Index» stereotype and
        // 25-oidc-guest-federation.kuml.kts file header. Nullable: only set for a guest account
        // created via the OIDC Relying Party flow; a real local member never has one.
        attribute(name = "oidcIssuer", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "oidc_issuer"; "sqlType" to "VARCHAR(2048)" }
        }
        attribute(name = "role", type = accountRole) {
            stereotype("Column") { "columnName" to "role"; "enumType" to "network.lapis.cloud.shared.domain.AccountRole" }
        }
    }

    association(source = member, target = account, id = "assoc-member-account") {
        source { multiplicity("1") }
        target { multiplicity("0..1"); role = "memberId" }
    }
}
