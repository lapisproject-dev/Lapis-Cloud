// Welle V1.9.129 "Video-Overlays (Logos, Name, Rahmen) im eigenen Kamerabild" -- see
// network.lapis.cloud.server.routes.VideoOverlayRoutes (upload + authenticated delivery),
// network.lapis.cloud.server.videooverlays.VideoOverlayStore (the only writer of the table) and
// docs/features/video-overlays.adoc.
//
// **This file models exactly one new table.** `video_overlay_asset` is one row per library element (a logo or a 16:9 frame) that an ADMIN
// uploaded. The re-encoded image lives on disk under `<LAPIS_VIDEO_OVERLAY_STORAGE_ROOT>/<id>.<format>`; this row is the metadata pointer and
// carries the per-upload access token that the authenticated delivery route resolves.
//
// **No member FK, no personal data.** Who uploaded or changed something is recorded in the hash-chained audit entry
// (`AuditEntityType.VIDEO_OVERLAY`); the table itself is organization data and therefore NOT in `OrganizationSchemaCatalog.EXCLUDED_TABLES`:
// it is part of the admin backup, together with the files (`BACKUP_ASSET_KINDS`, folder `video-overlays`).
//
// **`kind` / `format` are plain VARCHAR strings** (their CHECK constraints live in the SQL only, like every status column of this schema
// family since V1.9.57). `sha256` is `VARCHAR(64)`, matching the other digest columns. `access_token` is 32 random bytes in Base64url
// (43 characters), unique, minted anew on every upload.
//
// **time-fields:** `created_at` is class A (DbClock, UTC) -- see lapis-server/src/test/resources/time-fields.tsv.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "VideoOverlay") {
    applyProfile(ermMappingProfile)

    val videoOverlayAsset = classOf(name = "VideoOverlayAsset") {
        stereotype("Entity") {
            "tableName" to "video_overlay_asset"
            "kotlinObjectName" to "VideoOverlayAssetTable"
        }
        stereotype("Index") { "columns" to listOf("sort_order"); "name" to "idx_video_overlay_asset_sort" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // LOGO | FRAME
        attribute(name = "kind", type = "String") {
            stereotype("Column") { "columnName" to "kind"; "sqlType" to "VARCHAR(8)" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(60)" }
        }
        // png | jpg
        attribute(name = "format", type = "String") {
            stereotype("Column") { "columnName" to "format"; "sqlType" to "VARCHAR(4)" }
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
        attribute(name = "sha256", type = "String") {
            stereotype("Column") { "columnName" to "sha256"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "accessToken", type = "String") {
            stereotype("Column") { "columnName" to "access_token"; "sqlType" to "VARCHAR(64)" }
        }
        attribute(name = "sortOrder", type = "Int") {
            stereotype("Column") { "columnName" to "sort_order" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
