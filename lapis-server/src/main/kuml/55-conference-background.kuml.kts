// Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- see
// network.lapis.cloud.server.conference.ConferenceBackgroundImageProcessor for the decode/
// re-encode pipeline and network.lapis.cloud.server.routes.ConferenceBackgroundRoutes for the
// byte-carrying HTTP routes. `docs/architecture/video-background-effects.adoc` § "Custom
// backgrounds" has the full rationale.
//
// **This file models exactly one new table.** `conference_background_image` is one row per
// stored, member-owned custom background photo -- both the re-encoded main image and its
// thumbnail live on disk under `conference-backgrounds/<memberId>/<id>.jpg` /
// `<id>.thumb.jpg`; this row is only the metadata pointer (analogous to
// `travel_expense_receipt`, 45-travel-expense.kuml.kts, for the "row points at a file" shape).
//
// **No filename, MIME type or updated_at column.** Unlike `travel_expense_receipt`, this table
// carries no user-supplied filename (the upload is discarded after decoding, never stored under
// its original name) and no MIME type column (the server ALWAYS re-encodes to JPEG regardless of
// the original format -- see the processor's own KDoc). The row is never updated after insert
// (only inserted or deleted), so there is no `updated_at`.
//
// **`sha256` is `VARCHAR(64)`, not `CHAR(64)`** -- deliberately matching
// `travel_expense_receipt.sha256` and `member_card_code.code_hash`'s own column type (the
// domain-model drift tests compare type AND nullability, and this codebase's convention for a
// hex-digest column is `VARCHAR(64)`, never `CHAR(64)`). It is used ONLY as a cheap `ETag` value
// for the image-download routes' `If-None-Match` handling -- not a security boundary (unlike
// `member_card_code.code_hash`, which authenticates a bearer code).
//
// **No CASCADE on the `member_id` FK.** The only writers of this table (`ConferenceBackgroundRoutes`,
// `ConferenceBackgroundPersonalData`) always delete the DB row FIRST or IN THE SAME step as the
// filesystem bytes -- a CASCADE delete triggered from elsewhere (there is currently no other
// deletion path for a `member` row) would silently orphan the files on disk with nothing left to
// clean them up. Mirrors `member_card_code`'s own "no CASCADE, the application layer is
// authoritative for file cleanup" posture.
//
// **DSGVO**: `conference_background_image` carries a `member_id` FK -> new
// `ConferenceBackgroundPersonalData` contributor, `PersonalDataRegistry`. Export carries only
// metadata (id/width/height/sizeBytes/createdAt) -- never the file bytes, never the storage keys
// (a storage key is an internal filesystem detail, not something the subject needs to see back).
// Erasure is a hard DELETE of the row AND both files -- an uploaded private photo has no
// accountability/retention interest of its own (same posture as `member_card_code`, not the
// retain-and-redact posture most of this codebase's other tables take).
//
// **Admin backup exclusion**: this table is deliberately listed in
// `OrganizationSchemaCatalog.EXCLUDED_TABLES` (NOT `OrganizationExportService.BLOB_TABLES`) -- see
// that object's own KDoc for the rationale (private, non-organizational data; the admin-facing
// whole-organization backup must not become a way to read a member's private photos, and
// exporting the metadata row WITHOUT the blob bytes would restore into a row with no file behind
// it, which still counts against `ConferenceBackgroundRules.MAX_PER_MEMBER` and whose thumbnail
// would 404).
//
// Cross-domain stub: minimal id-only `Member` (owned by `00-foundation.kuml.kts`), same
// single-file-evaluation pattern every later domain file's own header documents (most recently
// `54-mcp-server.kuml.kts`'s own stub) -- purely so `UmlToErmTransformer` can resolve this file's
// `conference_background_image.member_id` FK.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ConferenceBackground") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors every other domain file's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val conferenceBackgroundImage = classOf(name = "ConferenceBackgroundImage") {
        stereotype("Entity") {
            "tableName" to "conference_background_image"
            "kotlinObjectName" to "ConferenceBackgroundImageTable"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "thumbStorageKey", type = "String") {
            stereotype("Column") { "columnName" to "thumb_storage_key"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "width", type = "Int") {
            stereotype("Column") { "columnName" to "width" }
        }
        attribute(name = "height", type = "Int") {
            stereotype("Column") { "columnName" to "height" }
        }
        attribute(name = "sizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "size_bytes" }
        }
        // Cheap ETag only -- see file header "sha256 is VARCHAR(64), not CHAR(64)".
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
