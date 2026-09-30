// Regional chapter domain -- regional_chapter / regional_chapter_officer (V59__regional_chapters.sql).
//
// Welle V1.9.13 "Gliederungsverwaltung (Landesverbaende)" -- a flat (single-level) set of named
// regional chapters ("Landesverbaende"). A member.regional_chapter_id (00-foundation.kuml.kts)
// assigns at most one chapter per member; an ADMIN may additionally grant a member "Landesvorstand"
// (regional-chapter-officer) access, which scopes that officer's view of
// IMemberService.listMembersForAdministration to just their own chapter's ACTIVE, non-anonymized
// members (network.lapis.cloud.server.security.RegionalChapterVisibility). Deliberately NOT a
// hierarchy (no Kreis-/Ortsverbaende) -- see docs/architecture/regional-chapters.adoc "Zurueckgestellt".
//
// name_key exists because H2 cannot index an EXPRESSION (lower(name)) -- see
// network.lapis.cloud.shared.domain.RegionalChapterRules.nameKey. The service keeps it in sync with
// name on every create/rename.
//
// active_for_member_id on regional_chapter_officer is the uniqueness guard for "at most one ACTIVE
// officer grant per member" -- a UNIQUE index alone (H2/Postgres both treat NULL as distinct per
// row, same idiom account's oidc_issuer/oidc_subject pair already establishes in
// 00-foundation.kuml.kts). It carries NO «Column».fkEntity / no association, deliberately: it is
// not itself a foreign key to member, only a self-consistency guard value that must always equal
// member_id when revoked_at is NULL, and NULL otherwise -- enforced by
// chk_regional_chapter_officer_active_consistency (plain SQL CHECK, not expressible in kUML's ERM
// profile, added directly in V59__regional_chapters.sql, same "not every constraint is emitted"
// idiom MemberTable's own generated-file comment documents for its three un-emitted CHECKs).
//
// DSGVO: regional_chapter_officer.member_id/granted_by_member_id carry personal data (who was
// granted access to what, by whom) -- network.lapis.cloud.server.dsgvo.RegionalChapterPersonalData
// exports/erases it, registered in PersonalDataRegistry. The chapter's own name is NOT personal
// data (an organizational label), only appears via export as the resolved display value.
//
// Welle V1.9.20 adds description + crest_* to regional_chapter (public /landesverbaende page and
// embed feed). Crest FILES are not part of the admin backup (only the crest_* columns travel):
// after a restore the public crest URL answers 404 until a crest is uploaded again -- same posture
// as event/article covers, see docs/architecture/public-profiles.adoc "Backup".
//
// Cross-domain stub: a minimal id-only Member stub (Foundation-owned), same pattern every other
// domain file establishes, only so UmlToErmTransformer can resolve member_id/granted_by_member_id.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "RegionalChapter") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes. Only exists here so UmlToErmTransformer can resolve
    // regional_chapter_officer.member_id/granted_by_member_id's association target.
    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    val regionalChapter =
        classOf(name = "RegionalChapter") {
            stereotype("Entity") { "tableName" to "regional_chapter"; "kotlinObjectName" to "RegionalChapterTable" }
            stereotype("Index") { "columns" to listOf("name_key"); "unique" to true; "name" to "uq_regional_chapter_name_key" }
            stereotype("Index") { "columns" to listOf("crest_public_token"); "unique" to true; "name" to "uq_regional_chapter_crest_token" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "name", type = "String") {
                stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(80)" }
            }
            // See file header -- kept in sync with `name` by RegionalChapterRules.nameKey, never
            // independently editable.
            attribute(name = "nameKey", type = "String") {
                stereotype("Column") { "columnName" to "name_key"; "sqlType" to "VARCHAR(80)" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
            // Welle V1.9.20 "Oeffentliche Seiten" (V62__public_profiles.sql) -- public description
            // (<= 300 code points, application-validated; VARCHAR(1200) is UTF-16 headroom) and crest.
            attribute(name = "description", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1200)" }
            }
            // File-name key of the stored crest image -- never a path. NOT an FK, NOT the public token.
            attribute(name = "crestImageId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "crest_image_id" }
            }
            // 256-bit Base64url key of the public URL /public/chapter-crests/{token}; regenerated on every upload.
            attribute(name = "crestPublicToken", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "crest_public_token"; "sqlType" to "VARCHAR(64)" }
            }
            // 'image/jpeg' | 'image/png' | 'image/svg+xml' (V63). The three crest_* columns are all NULL or all set
            // (chk_regional_chapter_crest_state, plain SQL CHECK in V62; the content type CHECK was widened in V63).
            attribute(name = "crestContentType", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "crest_content_type"; "sqlType" to "VARCHAR(32)" }
            }
        }

    val regionalChapterOfficer =
        classOf(name = "RegionalChapterOfficer") {
            stereotype("Entity") {
                "tableName" to "regional_chapter_officer"
                "kotlinObjectName" to "RegionalChapterOfficerTable"
            }
            stereotype("Index") { "columns" to listOf("regional_chapter_id"); "name" to "idx_regional_chapter_officer_chapter" }
            stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_regional_chapter_officer_member" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "memberId", type = "UUID") {
                stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "regionalChapterId", type = "UUID") {
                stereotype("Column") { "columnName" to "regional_chapter_id"; "fkEntity" to "RegionalChapter" }
            }
            attribute(name = "grantedAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "granted_at" }
            }
            // Nullable -- a future SYSTEM/bootstrap grant has no human actor. See
            // RegionalChapterService.grantOfficer, which always sets this to current.memberId.
            attribute(name = "grantedByMemberId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "granted_by_member_id"; "fkEntity" to "Member" }
            }
            attribute(name = "revokedAt", type = "LocalDateTime") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "revoked_at" }
            }
            // See file header -- NOT a foreign key, a self-consistency uniqueness guard value.
            attribute(name = "activeForMemberId", type = "UUID") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "active_for_member_id" }
            }
        }
}
