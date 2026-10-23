// Welle V1.9.134 "Vertragstexte je Instanz, versioniert" -- see network.lapis.cloud.server.agreementtexts.AgreementTextStore (the only reader and
// writer of both tables), network.lapis.cloud.server.rpc.AgreementTextAdminService and docs/features/agreement-texts.adoc.
//
// **Two tables.** `agreement_text_version` is APPEND-ONLY: one row per published version of the membership agreement or of the FRIEND terms,
// the current text of a kind being the row with the highest `sequence_no`. `agreement_text_draft` holds at most one working copy per kind
// (the primary key is `kind`); its `id` is the stable audit entity id of the draft. Every instance has its own database, so there is no
// organization id. Both tables are organization data and therefore part of the admin backup (NOT in `OrganizationSchemaCatalog.EXCLUDED_TABLES`).
//
// `sha256` = SHA-256 over `"$version_label\n$body"` (UTF-8, lower-case hex), the formula the built-in placeholder texts already use; it is what
// `membership_agreement_acknowledgment.agreement_sha256` / `friend_terms_acknowledgment.terms_sha256` record. `version_label` is VARCHAR(50), the
// width of those acknowledgment columns. The SQL carries the CHECK and the composite UNIQUE constraints (kind, version_label) and (kind, sequence_no).
//
// **FK-naming choice**: `author_member_id` / `updated_by` are plain «Column» UUID attributes with «Column».fkEntity, never a UML association --
// the domain-wide policy 21-auction.kuml.kts documents. A minimal id-only Member stub (owned by Foundation) resolves them within this file.
//
// **time-fields:** `published_at` / `updated_at` are class A (DbClock, UTC), `valid_from` is class D (calendar date) -- see
// lapis-server/src/test/resources/time-fields.tsv.
// **V1.9.196 (V112):** `body_format` ('plain' | 'markdown', VARCHAR(16), default 'plain') says how the body is displayed. It is NOT part of the hash
// (`sha256` stays SHA-256 over `"$version_label\n$body"`) and is immutable after publication.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "AgreementTexts") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val agreementTextVersion = classOf(name = "AgreementTextVersion") {
        stereotype("Entity") { "tableName" to "agreement_text_version"; "kotlinObjectName" to "AgreementTextVersionTable" }
        stereotype("Index") { "columns" to listOf("kind", "sequence_no"); "name" to "idx_agreement_text_version_kind_seq" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // MEMBERSHIP | FRIEND | REC_ROOM | REC_PULPIT | FINDER | ENC_CHURCH | ENC_ASSEM | EVENT_REC (V1.9.156, V110: the event-room capture consent, stored in event_capture_acknowledgment; V1.9.148, V108: the two encounter entry notices; V1.9.143 added the REC kinds, the CHECK lives in V106; V1.9.144 added FINDER, V107). REC_* texts are never acknowledged persistently; a FINDER consent is stored in member_finder_consent_event.
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "sequenceNo", type = "Int") {
            stereotype("Column") { "columnName" to "sequence_no" }
        }
        attribute(name = "versionLabel", type = "String") {
            stereotype("Column") { "columnName" to "version_label"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "validFrom", type = "LocalDate") {
            stereotype("Column") { "columnName" to "valid_from" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "TEXT" }
        }
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "charCount", type = "Int") {
            stereotype("Column") { "columnName" to "char_count" }
        }
        attribute(name = "bodyFormat", type = "String") {
            stereotype("Column") { "columnName" to "body_format"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "authorMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "author_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "publishedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "published_at" }
        }
    }

    val agreementTextDraft = classOf(name = "AgreementTextDraft") {
        stereotype("Entity") { "tableName" to "agreement_text_draft"; "kotlinObjectName" to "AgreementTextDraftTable" }

        // Primary key: at most one draft per kind.
        attribute(name = "kind", type = "String") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(10)" }
        }
        attribute(name = "id", type = "UUID") {
            stereotype("Column") { "columnName" to "id"; "unique" to true }
        }
        attribute(name = "versionLabel", type = "String") {
            stereotype("Column") { "columnName" to "version_label"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "validFrom", type = "LocalDate") {
            stereotype("Column") { "columnName" to "valid_from" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "TEXT" }
        }
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "charCount", type = "Int") {
            stereotype("Column") { "columnName" to "char_count" }
        }
        attribute(name = "bodyFormat", type = "String") {
            stereotype("Column") { "columnName" to "body_format"; "sqlType" to "VARCHAR(16)" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }
}
