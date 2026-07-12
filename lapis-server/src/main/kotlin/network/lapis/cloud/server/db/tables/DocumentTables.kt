package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object DocumentFolderTable : Table("document_folder") {
    val id = uuid("id")
    val name = varchar("name", 200)
    val parentFolderId = uuid("parent_folder_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

object DocumentTable : Table("document") {
    val id = uuid("id")
    val folderId = uuid("folder_id").references(DocumentFolderTable.id)
    val title = varchar("title", 300)

    // Nullable + no FK constraint declared on the Kotlin side to avoid a circular reference
    // with DocumentVersionTable at the DSL level; the actual FK constraint lives in the V3
    // Flyway migration (added via a second ALTER TABLE after both tables exist).
    val currentVersionId = uuid("current_version_id").nullable()
    val createdBy = uuid("created_by").references(MemberTable.id)
    val createdAt = datetime("created_at")
    val accessLevel = enumerationByName<DocumentAccessLevel>("access_level", 20)
    val isDeleted = bool("is_deleted")

    override val primaryKey = PrimaryKey(id)
}

object DocumentVersionTable : Table("document_version") {
    val id = uuid("id")
    val documentId = uuid("document_id").references(DocumentTable.id, onDelete = ReferenceOption.NO_ACTION)
    val versionNumber = integer("version_number")
    val fileName = varchar("file_name", 300)
    val mimeType = varchar("mime_type", 150)
    val fileSizeBytes = long("file_size_bytes")
    val storageKey = varchar("storage_key", 300)
    val checksumSha256 = varchar("checksum_sha256", 64)
    val uploadedBy = uuid("uploaded_by").references(MemberTable.id)
    val uploadedAt = datetime("uploaded_at")
    val changeNote = varchar("change_note", 1000).nullable()

    override val primaryKey = PrimaryKey(id)
}
