// Document domain — document_folder/document/document_version (V3__documents.sql).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.DocumentTables.kt) by SchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Both of this domain's Member-referencing FKs (document.created_by, document_version.uploaded_by)
// are modelled as plain «Column» UUID attributes rather than UML associations (see the naming-gap
// note below) — pinned instead via «Column».fkEntity, which is why this file, symmetrically,
// carries a minimal id-only Member stub (owned by Foundation), same cross-domain-stub pattern
// established by contribution's own Member stub.
//
// Two self/circular-reference cases (both explicitly called out in the retrofit plan and both
// already handled identically by the hand-written DocumentTables.kt — see its own comments).
// Both COULD now be pinned via «Column».fkEntity (which resolves in a pass after every entity
// exists, so it has no script-declaration-order problem), but are deliberately left as plain
// columns: the real risk isn't script-declaration order, it's genuine bidirectional Kotlin
// `object`-initializer circularity at the Exposed layer if both tables end up referencing each
// other's columns — the same reason the original hand-written DocumentTables.kt never declared
// these either. Since the SQL/Flyway baseline is now generated from this same model, both real
// FK constraints (present in the pre-swap hand-written V3__documents.sql) are consequently absent
// from the generated baseline too — a deliberate, pre-existing trade-off, not a new regression.
//  - document_folder.parent_folder_id is genuinely self-referential. UmlToErmTransformer
//    explicitly skips self-referential UML associations ("cosmetic only, out of V3.4.6 scope"),
//    so this is modelled as a plain nullable UUID «Column» attribute, not a UML association —
//    exactly matching the hand-written table's own behaviour (no .references() on this column
//    at the Exposed layer either).
//  - document.current_version_id is nullable with NO FK at the Exposed layer specifically to
//    avoid a circular reference at DSL-declaration time (document <-> document_version). Modelled
//    here as a plain nullable UUID «Column» attribute (not an association) for the same reason —
//    do not let UmlToErmTransformer auto-derive a reference() for it.
//
// document_version.document_id: the real schema has onDelete=NO_ACTION on this FK (non-default
// relative to some other domains' CASCADE choices) — but associations cannot carry stereotype()
// calls from `.kuml.kts` script code today (AssociationBuilder does not implement
// UmlElementScope; confirmed during the contribution/foundation waves). This turns out not to be
// a blocker here: UmlToErmTransformer's own `parseReferentialAction` falls back to
// ReferentialAction.NO_ACTION whenever no «FK».onDelete tag is present (see
// ReferentialActionParsing.kt) — which is exactly the real schema's value for this column. So the
// plain, tag-less association below already reproduces NO_ACTION with no workaround needed.
//
// DocumentAccessLevel is the one enum column in this domain (document.access_level) — modelled
// with an explicit «Column».sqlType="VARCHAR(20)" override, same mechanism/rationale as
// member.status / account.role / membership_tier.billing_interval / contribution.status in the
// prior domains (real V3__documents.sql has a plain VARCHAR(20), no CHECK constraint).
//
// document.folder_id / document.created_by / document_version.uploaded_by are modelled as plain
// «Column» UUID attributes rather than UML associations — a genuine, newly-discovered gap beyond
// what foundation/contribution needed to work around: UmlToErmTransformer's association-to-FK
// column-naming defaults to `snake_case(singular(targetClass.name)) + "_id"` (e.g.
// "document_folder_id", "member_id") and only falls back to the association-end *role* name when
// the default name already collides with an existing attribute on the FK-owning entity
// (UmlToErmTransformer.addForeignKey, fkEntity.hasAttributeNamed(defaultBaseName) check) — there
// is no way to opt into role-based naming without an actual collision, and pre-declaring a
// colliding dummy attribute would leave two columns (the dummy plus the synthesized one), not one
// renamed column. Since this domain's real column names (folder_id, created_by, uploaded_by)
// don't match that derived default and associations cannot carry a «FK».constraintName-style
// override to rename the resulting column, the plain-«Column»-attribute fallback described in the
// retrofit plan's risk note (for the governance domain's multi-role-FK case) applies here too.
// The real FK's existence/target/nullability is still independently pinned by
// DocumentSchemaDriftTest's information_schema introspection against the real migrated schema —
// only the ERM-level `reference()`/foreignKey wiring is forgone for these three columns.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Document") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's own Member stub. Only exists here so UmlToErmTransformer can resolve
    // document.created_by / document_version.uploaded_by's «Column».fkEntity target.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val documentAccessLevel = enumOf(name = "DocumentAccessLevel") {
        literal(name = "PUBLIC_MEMBERS")
        literal(name = "BOARD_ONLY")
        literal(name = "ADMIN_ONLY")
    }

    val documentFolder = classOf(name = "DocumentFolder") {
        stereotype("Entity") { "tableName" to "document_folder"; "kotlinObjectName" to "DocumentFolderTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        // Self-referential — UmlToErmTransformer skips self-referential UML associations, so this
        // is a plain nullable UUID attribute, not an association. Matches the hand-written
        // DocumentFolderTable.parentFolderId (uuid("parent_folder_id").nullable(), no .references()).
        attribute(name = "parentFolderId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "parent_folder_id" }
        }
    }

    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        stereotype("Index") { "columns" to listOf("folder_id"); "name" to "idx_document_folder" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> document_folder (id), NOT NULL. Plain «Column» UUID attribute rather than a
        // UML association — see the file header comment (association-to-FK column naming would
        // derive "document_folder_id", not the real schema's "folder_id", with no DSL-level way
        // to override it). Pinned against the real schema's FK shape in DocumentSchemaDriftTest.
        attribute(name = "folderId", type = "UUID") {
            stereotype("Column") { "columnName" to "folder_id"; "fkEntity" to "DocumentFolder" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        // Nullable, no FK at the Exposed layer (avoids a document <-> document_version circular
        // reference at DSL-declaration time) — plain UUID attribute, not an association. Matches
        // the hand-written DocumentTable.currentVersionId.
        attribute(name = "currentVersionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "current_version_id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment (association-to-FK naming would derive "member_id", not the real schema's
        // "created_by").
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "accessLevel", type = documentAccessLevel) {
            stereotype("Column") { "columnName" to "access_level"; "enumType" to "network.lapis.cloud.shared.domain.DocumentAccessLevel" }
        }
        attribute(name = "isDeleted", type = "Boolean") {
            defaultValue = "FALSE"
            stereotype("Column") { "columnName" to "is_deleted" }
        }
    }

    val documentVersion = classOf(name = "DocumentVersion") {
        stereotype("Entity") { "tableName" to "document_version"; "kotlinObjectName" to "DocumentVersionTable" }
        stereotype("Index") {
            "columns" to listOf("document_id", "version_number")
            "unique" to true
            "name" to "uq_document_version_number"
        }
        stereotype("Index") { "columns" to listOf("document_id"); "name" to "idx_document_version_document" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "versionNumber", type = "Int") {
            stereotype("Column") { "columnName" to "version_number" }
        }
        attribute(name = "fileName", type = "String") {
            stereotype("Column") { "columnName" to "file_name"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "mimeType", type = "String") {
            stereotype("Column") { "columnName" to "mime_type"; "sqlType" to "VARCHAR(150)" }
        }
        attribute(name = "fileSizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "file_size_bytes" }
        }
        attribute(name = "storageKey", type = "String") {
            stereotype("Column") { "columnName" to "storage_key"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "checksumSha256", type = "String") {
            stereotype("Column") { "columnName" to "checksum_sha256"; "sqlType" to "VARCHAR(64)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment (association-to-FK naming would derive "member_id", not the real schema's
        // "uploaded_by").
        attribute(name = "uploadedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "uploaded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "uploadedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "uploaded_at" }
        }
        attribute(name = "changeNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "change_note"; "sqlType" to "VARCHAR(1000)" }
        }
        // download_count: monoton wachsender Zaehler fuer ausgelieferte Voll-Downloads dieser
        // Version (nicht "Downloads von diesem Dokument" -- DocumentDto traegt bewusst KEINEN
        // Zaehler, Design-Team-Entscheidung 2026-09-16: eine Quelle, an der Version, nicht am
        // Dokument). Zaehlt einen ausgelieferten LocalFileContent-Response (GET, kein
        // Range-Header) im DocumentRoutes-Download-Handler -- NICHT HEAD, NICHT
        // Range-Requests (PartialContent-Fortsetzungen desselben Downloads wuerden sonst mehrfach
        // zaehlen, siehe DocumentRoutes.kt-Kommentar an der Inkrement-Stelle). Kuenftig aus einer
        // document_download-Ereignishistorie ableitbar (COUNT(*) GROUP BY document_version_id)
        // ohne dass sich diese Spalten-/DTO-Form aendert -- bewusst heute schon so benannt, dass
        // ein spaeteres Event-Log sie ersetzen koennte, ohne die DTO-Form
        // (DocumentVersionDto.downloadCount) zu brechen.
        attribute(name = "downloadCount", type = "Long") {
            defaultValue = "0"
            stereotype("Column") { "columnName" to "download_count" }
        }
    }

    // document_version.document_id -> document (id): the association-derived default name
    // "document_id" (snake_case(singular("Document")) + "_id") happens to match the real schema
    // exactly, so this one FK is kept as a real UML association (and reproduces the real schema's
    // NO_ACTION onDelete via UmlToErmTransformer's own no-tag-present fallback — see the file
    // header comment).
    association(source = document, target = documentVersion, id = "assoc-document-document-version") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "documentId" }
    }
}
