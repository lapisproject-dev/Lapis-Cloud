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
        literal(name = "ANTRAG")
        literal(name = "AKTIV")
        literal(name = "GAST")
        literal(name = "AUSGETRETEN")
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
    }

    association(source = membershipTier, target = member, id = "assoc-member-membership-tier") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "membershipTierId" }
    }

    val account = classOf(name = "Account") {
        stereotype("Entity") { "tableName" to "account"; "kotlinObjectName" to "AccountTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_account_member_id" }

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
        attribute(name = "role", type = accountRole) {
            stereotype("Column") { "columnName" to "role"; "enumType" to "network.lapis.cloud.shared.domain.AccountRole" }
        }
    }

    association(source = member, target = account, id = "assoc-member-account") {
        source { multiplicity("1") }
        target { multiplicity("0..1"); role = "memberId" }
    }
}
