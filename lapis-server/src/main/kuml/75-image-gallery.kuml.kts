// Image gallery domain -- attached_image (V119__image_gallery.sql).
//
// Welle V1.9.222 "Gemeinsame Bildergalerie": one gallery component for several owner objects. Wave 1 serves crowdfunding projects and
// auction
// listings; a social post joins later through its own migration (the owner_type CHECK is extended then, not now -- no unreachable path
// today).
// The OWNER is addressed by (owner_type, owner_id) WITHOUT a foreign key, because one table serves several owner tables. Integrity is
// therefore
// the job of GalleryOwnerPolicy / AttachedImageStore (the only code that reads or writes the table) and of the orphan hook + start
// sweep.
//
// DECISIONS THAT SHAPE THE TABLE -- do not "repair" any of them:
// * position is the gallery order, 0-based and gap-free per owner; position 0 IS the cover image. UNIQUE (owner_type, owner_id,
// position)
// is the
// safety net -- the service keeps the invariant under the owner row lock. A re-order is two-phase (shift to 1000+, then to the final
// slot),
//     because a unique index is not deferrable on PostgreSQL.
// * storage_key is a server-generated UUID, DISTINCT from id: the on-disk file name (<storage_key>.<jpg|png>) and the `v` cache-busting
// tag
//     derive from it; the id never names a file. UNIQUE.
// * mime_type is the OUTPUT type (image/jpeg or image/png). A WebP upload is decoded and stored as JPEG (PNG when it has an alpha
// channel);
//     WebP itself is never stored.
//   * uploaded_by is a plain «Column» with fkEntity, NO association(...) block -- same reasoning as 74-tender.kuml.kts.
//
// V1.9.223 "Bilder im Sozialen Netzwerk": SOCIAL_POST (ROOT posts only) joined through V120__social_post_images.sql, which only widens the
// owner_type CHECK -- no new column or index. Pictures of a post are deleted with their uploader (= the author, only the author can
// upload) and by the orphan hook in executeContentErasure.
//
// DSGVO -- uploaded_by, caption, alt_text and the picture itself are personal data (a photo can show people);
// network.lapis.cloud.server.dsgvo.AttachedImagePersonalData exports and HARD-erases them in every ErasureMode, registered in
// PersonalDataRegistry. The hash-chained audit entries carry no caption, no file name and no storage key.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "ImageGallery") {
    applyProfile(ermMappingProfile)

    val member =
        classOf(name = "Member") {
            stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
        }

    // Literal order is load-bearing: the drift test pins the values in exactly this order (= network.lapis.cloud.shared.domain.*).
    val attachedImageOwnerType =
        enumOf(name = "AttachedImageOwnerType") {
            literal(name = "CROWDFUNDING_PROJECT")
            literal(name = "AUCTION_LISTING")
            literal(name = "SOCIAL_POST") // V1.9.223, migration V120 (owner_type CHECK widened; V119 untouched)
        }

    val attachedImage =
        classOf(name = "AttachedImage") {
            stereotype("Entity") { "tableName" to "attached_image"; "kotlinObjectName" to "AttachedImageTable" }
            stereotype("Index") {
                "columns" to listOf("owner_type", "owner_id", "position")
                "unique" to true
                "name" to "uq_attached_image_owner_position"
            }
            stereotype("Index") { "columns" to listOf("storage_key"); "unique" to true; "name" to "uq_attached_image_storage_key" }
            stereotype("Index") { "columns" to listOf("uploaded_by"); "name" to "ix_attached_image_uploaded_by" }

            attribute(name = "id", type = "UUID") {
                stereotype("Id")
                stereotype("Column") { "columnName" to "id" }
            }
            attribute(name = "ownerType", type = attachedImageOwnerType) {
                stereotype("Column") {
                    "columnName" to "owner_type"
                    "sqlType" to "VARCHAR(32)"
                    "enumType" to "network.lapis.cloud.shared.domain.AttachedImageOwnerType"
                }
            }
            // No foreign key: the owner lives in one of several tables, see the file header.
            attribute(name = "ownerId", type = "UUID") {
                stereotype("Column") { "columnName" to "owner_id" }
            }
            attribute(name = "position", type = "Int") {
                stereotype("Column") { "columnName" to "position" }
            }
            attribute(name = "caption", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "caption"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "altText", type = "String") {
                multiplicity = Multiplicity(0, 1)
                stereotype("Column") { "columnName" to "alt_text"; "sqlType" to "VARCHAR(200)" }
            }
            attribute(name = "storageKey", type = "UUID") {
                stereotype("Column") { "columnName" to "storage_key" }
            }
            attribute(name = "mimeType", type = "String") {
                stereotype("Column") { "columnName" to "mime_type"; "sqlType" to "VARCHAR(32)" }
            }
            attribute(name = "byteSize", type = "Long") {
                stereotype("Column") { "columnName" to "byte_size" }
            }
            attribute(name = "width", type = "Int") {
                stereotype("Column") { "columnName" to "width" }
            }
            attribute(name = "height", type = "Int") {
                stereotype("Column") { "columnName" to "height" }
            }
            attribute(name = "uploadedBy", type = "UUID") {
                stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
            }
            attribute(name = "createdAt", type = "LocalDateTime") {
                stereotype("Column") { "columnName" to "created_at" }
            }
        }
}
