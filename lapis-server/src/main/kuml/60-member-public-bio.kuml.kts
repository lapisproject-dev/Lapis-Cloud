// Welle V1.9.20 "Oeffentliche Seiten Vorstand, Politiker und Landesverbaende" -- see
// network.lapis.cloud.server.memberbio.MemberPublicBioStore, network.lapis.cloud.server.rpc
// .MemberPublicProfileService and docs/architecture/public-profiles.adoc for the full rationale.
//
// **This file models exactly one new table.** `member_public_bio` holds the short introduction a
// member writes about themselves (at most 500 Unicode code points, plain text). One row per member
// (unique index on member_id). The text is PRIVATE by default: it only appears on the public
// /vorstand and /politiker pages and in the embed feeds while a consent is EFFECTIVE, i.e.
// `consent_granted_at` is set AND `consent_text_version` equals the server's current version
// (`MemberPublicBioRules.CONSENT_TEXT_VERSION`). A consent for an older wording is not effective.
//
// **Consent state** = `consent_granted_at` + `consent_text_version`, both NULL (private) or both set
// (published) -- chk_member_public_bio_consent_state (plain SQL CHECK in V62, not expressible in the
// ERM profile). A text change keeps an existing consent (the author writes and previews the text
// themselves); withdrawal and deletion are always available to the owner.
//
// **Own `id` primary key plus unique index on member_id**, same house convention as
// `member_photo` / `ai_member_opt_in`.
//
// **No CASCADE on the `member_id` FK** -- the application layer is authoritative for cleanup.
//
// **DSGVO**: carries a `member_id` FK -> `MemberPublicBioPersonalData` contributor. Export carries
// the text, the publication state, the consent version and timestamps; erasure is a hard DELETE of
// the row in every ErasureMode (a self-written profile text has no accountability/retention value).
//
// **Admin backup exclusion**: listed in `OrganizationSchemaCatalog.EXCLUDED_TABLES` -- a PRIVATE
// self-description must not become readable through the ADMIN-only whole-organization backup.
// Restore targets an empty database; members write their introduction again.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same
// single-file-evaluation pattern every later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberPublicBio") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val memberPublicBio = classOf(name = "MemberPublicBio") {
        stereotype("Entity") { "tableName" to "member_public_bio"; "kotlinObjectName" to "MemberPublicBioTable" }

        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_member_public_bio_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        // Plain text, 500 code points maximum (application-validated); VARCHAR(2000) is UTF-16 headroom.
        attribute(name = "bioText", type = "String") {
            stereotype("Column") { "columnName" to "bio_text"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "consentGrantedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_granted_at" }
        }
        attribute(name = "consentTextVersion", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "consent_text_version"; "sqlType" to "VARCHAR(40)" }
        }
    }
}
