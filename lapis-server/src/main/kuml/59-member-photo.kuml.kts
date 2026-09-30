// Welle V1.9.19 "Mitglieder-Foto" -- see network.lapis.cloud.server.memberphoto.MemberPhotoImageProcessor
// for the decode/crop/re-encode pipeline, network.lapis.cloud.server.routes.MemberPhotoRoutes for the
// byte-carrying HTTP routes and docs/architecture/member-photo.adoc for the full rationale.
//
// **This file models exactly one new table.** `member_photo` is one row per member (enforced by
// the unique index on member_id) and is only the metadata pointer: the re-encoded 800x800 JPEG
// lives on disk under `<document storage root>/member-photos/<storage_key>`. `storage_key` is a
// random UUID (`<uuid>.jpg`), never the member id.
//
// **Consent state is part of the row.** `visibility` PRIVATE (default) / PUBLIC; PUBLIC requires
// `public_token` (256-bit, regenerated on EVERY publication, NULL again on withdrawal),
// `consent_granted_at` and `consent_text_version` -- a CHECK constraint
// (chk_member_photo_public_state) makes any other combination unrepresentable.
//
// **Own `id` primary key plus unique index on member_id**, not `member_id` as the primary key: the
// house convention (see `ai_member_opt_in`), and the ERM/drift tooling assumes an `id` column.
//
// **No CASCADE on the `member_id` FK** -- the application layer is authoritative for file cleanup
// (same posture as `conference_background_image`, `member_card_code`).
//
// **DSGVO**: `member_photo` carries a `member_id` FK -> `MemberPhotoPersonalData` contributor.
// Export carries metadata plus the JPEG (Art. 15/20) but never the public token or storage key;
// erasure is a hard DELETE of the row AND the file in every ErasureMode.
//
// **Admin backup exclusion**: listed in `OrganizationSchemaCatalog.EXCLUDED_TABLES`, deliberately
// NOT in `OrganizationExportService.BLOB_TABLES` -- a private photo must not become readable
// through the ADMIN-only whole-organization backup, and metadata without the file would restore a
// row with nothing behind it. Photos must be re-uploaded after a restore.
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same
// single-file-evaluation pattern every later domain file's own header documents.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "MemberPhoto") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Append-only, never reorder -- mirrors `MemberPhotoVisibility` on the Kotlin side.
    val memberPhotoVisibility = enumOf(name = "MemberPhotoVisibility") {
        literal(name = "PRIVATE")
        literal(name = "PUBLIC")
    }

    val memberPhoto = classOf(name = "MemberPhoto") {
        stereotype("Entity") { "tableName" to "member_photo"; "kotlinObjectName" to "MemberPhotoTable" }

        stereotype("Index") { "columns" to listOf("member_id"); "unique" to true; "name" to "uq_member_photo_member" }
        stereotype("Index") { "columns" to listOf("public_token"); "unique" to true; "name" to "uq_member_photo_public_token" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "contentType", type = "String") {
            stereotype("Column") { "columnName" to "content_type"; "sqlType" to "VARCHAR(32)" }
        }
        attribute(name = "widthPx", type = "Int") {
            stereotype("Column") { "columnName" to "width_px" }
        }
        attribute(name = "heightPx", type = "Int") {
            stereotype("Column") { "columnName" to "height_px" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "size_bytes" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
        attribute(name = "visibility", type = memberPhotoVisibility) {
            stereotype("Column") {
                "columnName" to "visibility"
                "sqlType" to "VARCHAR(10)"
                "enumType" to "network.lapis.cloud.shared.domain.MemberPhotoVisibility"
            }
        }
        attribute(name = "publicToken", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "public_token"; "sqlType" to "VARCHAR(64)" }
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
