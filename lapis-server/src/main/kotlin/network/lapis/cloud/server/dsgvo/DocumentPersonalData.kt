package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [DocumentTable]/[DocumentVersionTable] metadata — file *bytes* live on disk under
 * `LAPIS_DOCUMENT_STORAGE_ROOT`, handled by
 * `network.lapis.cloud.server.routes.registerDocumentRoutes`, not here (same reasoning as that
 * route's own KDoc for why bytes don't travel through Kilua RPC).
 *
 * Documents are organizational records, not purely the author's/uploader's personal data —
 * retained on erasure with the `created_by`/`uploaded_by` pointer resolving to the anonymized
 * member row (see [FoundationPersonalData]). File bytes on disk are retained by default and
 * flagged for manual DPO review rather than auto-deleted, because a document could be the
 * organization's only copy of a governance record (e.g. Vereinssatzung) authored by the member
 * being erased — see `docs/architecture/dsgvo.adoc` "Bewusst nicht umgesetzt in dieser Welle".
 */
object DocumentPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "documents"
    override val displayName = "Dokumente"
    override val coveredTables = setOf(DocumentTable, DocumentVersionTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("authoredDocuments") {
                DocumentTable
                    .selectAll()
                    .where { DocumentTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[DocumentTable.id].toString())
                                put("title", row[DocumentTable.title])
                                put("createdAt", row[DocumentTable.createdAt].toString())
                                put("accessLevel", row[DocumentTable.accessLevel].name)
                                put("isDeleted", row[DocumentTable.isDeleted])
                            },
                        )
                    }
            }
            putJsonArray("uploadedVersions") {
                DocumentVersionTable
                    .selectAll()
                    .where { DocumentVersionTable.uploadedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[DocumentVersionTable.id].toString())
                                put("documentId", row[DocumentVersionTable.documentId].toString())
                                put("fileName", row[DocumentVersionTable.fileName])
                                put("uploadedAt", row[DocumentVersionTable.uploadedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val documentCount = DocumentTable.selectAll().where { DocumentTable.createdBy eq memberId }.count()
        val versionCount = DocumentVersionTable.selectAll().where { DocumentVersionTable.uploadedBy eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "document",
                rowsRetained = documentCount.toInt(),
                retentionReason =
                    "Organisationsdokument, kein reines Personendatum; Ersteller-Zeiger zeigt nach der " +
                        "Anonymisierung auf den anonymisierten Mitgliedsdatensatz",
            ),
            TableErasureOutcome(
                table = "document_version",
                rowsRetained = versionCount.toInt(),
                retentionReason =
                    "Dateibytes auf Disk werden in dieser Welle nicht automatisiert geloescht -- " +
                        "manuelle DPO-Pruefung empfohlen, falls eine Version reines Personendatum ist",
            ),
        )
    }
}
