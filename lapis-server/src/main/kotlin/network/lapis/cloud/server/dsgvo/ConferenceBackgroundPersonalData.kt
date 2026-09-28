package network.lapis.cloud.server.dsgvo

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Same root `network.lapis.cloud.server.Application.module` computes for `documentStorageRoot`
 * (`LAPIS_DOCUMENT_STORAGE_ROOT`, default `build/document-storage`) -- re-derived here rather than
 * threaded through [MemberPersonalDataContributor.eraseMember]'s signature, same convention
 * [TravelExpensePersonalData] already establishes for its own `receiptStorageRoot`.
 */
private val backgroundStorageRoot: File
    get() = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- owns
 * [ConferenceBackgroundImageTable]. See `56-conference-background.kuml.kts` file header "DSGVO"
 * for the full rationale.
 *
 * **Hard DELETE, not retain-and-redact** -- same posture [MemberCardPersonalData] already
 * establishes: an uploaded private photo has no accountability/retention interest of its own and
 * must be gone immediately on Art. 17 erasure, files included.
 *
 * **Export carries only metadata** -- id/width/height/sizeBytes/createdAt, never the storage
 * keys (an internal filesystem detail, not something the subject needs to see back) and never the
 * file bytes themselves (same "no bytes in a DSGVO export" posture [TravelExpensePersonalData]
 * already establishes for receipts).
 */
object ConferenceBackgroundPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "conferenceBackgrounds"
    override val displayName = "Eigene Videokonferenz-Hintergrundbilder"
    override val coveredTables = setOf(ConferenceBackgroundImageTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("conferenceBackgroundImages") {
                ConferenceBackgroundImageTable
                    .selectAll()
                    .where { ConferenceBackgroundImageTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ConferenceBackgroundImageTable.id].toString())
                                put("width", row[ConferenceBackgroundImageTable.width])
                                put("height", row[ConferenceBackgroundImageTable.height])
                                put("sizeBytes", row[ConferenceBackgroundImageTable.sizeBytes])
                                put("createdAt", row[ConferenceBackgroundImageTable.createdAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val rows =
            ConferenceBackgroundImageTable
                .selectAll()
                .where { ConferenceBackgroundImageTable.memberId eq memberId }
                .toList()
        rows.forEach { row ->
            deleteBackgroundFile(row[ConferenceBackgroundImageTable.storageKey])
            deleteBackgroundFile(row[ConferenceBackgroundImageTable.thumbStorageKey])
        }
        val deleted = ConferenceBackgroundImageTable.deleteWhere { ConferenceBackgroundImageTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(
                table = "conference_background_image",
                rowsDeleted = deleted,
                retentionReason = null,
            ),
        )
    }
}

private fun deleteBackgroundFile(storageKey: String) {
    runCatching {
        val file = backgroundStorageRoot.resolve(storageKey)
        if (file.exists()) file.delete()
    }.onFailure { e ->
        logger.warn(e) { "ConferenceBackgroundPersonalData: failed to delete file for storageKey=$storageKey (DB row is authoritative)" }
    }
}
