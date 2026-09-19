package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.AiCallAuditTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.AiMemberOptInTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.6.1 -- owns the three AI tables that carry a `member` FK. See `53-ai-assistant.kuml.kts`
 * file header "DSGVO".
 *
 * - `ai_member_opt_in`: **hard DELETE** -- a consent record for a feature the person no longer
 *   uses has no retention interest of its own.
 * - `ai_call_audit`: **retain-and-redact** -- the row is a usage/traceability record and carries
 *   only hashes and counters (never clear text); erasure sets `member_id` to `NULL`, after which
 *   the row has no personal reference.
 * - `ai_knowledge_release`: **retain-and-redact** -- the release decision belongs to the knowledge
 *   base record; erasure nulls the releasing member.
 *
 * Export: the hashes (`input_hash`/`output_hash`) are never exported -- like `code_hash` in
 * [MemberCardPersonalData] they give the data subject no intelligible information.
 *
 * `ai_knowledge_chunk`/`ai_knowledge_index_state` carry no member FK (derivatives of document
 * content, whose personal-data handling lives in [DocumentPersonalData]).
 */
object AiAssistantPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "aiAssistant"
    override val displayName = "KI-Assistenz"
    override val coveredTables = setOf(AiMemberOptInTable, AiCallAuditTable, AiKnowledgeReleaseTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("optIns") {
                AiMemberOptInTable
                    .selectAll()
                    .where { AiMemberOptInTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("feature", row[AiMemberOptInTable.feature].name)
                                put("enabled", row[AiMemberOptInTable.enabled])
                                put("updatedAt", row[AiMemberOptInTable.updatedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("aiCalls") {
                AiCallAuditTable
                    .selectAll()
                    .where { AiCallAuditTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("occurredAt", row[AiCallAuditTable.occurredAt].toString())
                                put("agentType", row[AiCallAuditTable.agentType])
                                put("outcome", row[AiCallAuditTable.outcome].name)
                                put("tokensIn", row[AiCallAuditTable.tokensIn])
                                put("tokensOut", row[AiCallAuditTable.tokensOut])
                                put("retrievedChunkCount", row[AiCallAuditTable.retrievedChunkCount])
                            },
                        )
                    }
            }
            putJsonArray("releasedDocuments") {
                (AiKnowledgeReleaseTable innerJoin DocumentTable)
                    .selectAll()
                    .where { AiKnowledgeReleaseTable.releasedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("documentTitle", row[DocumentTable.title])
                                put("releasedAt", row[AiKnowledgeReleaseTable.releasedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val optInsDeleted = AiMemberOptInTable.deleteWhere { AiMemberOptInTable.memberId eq memberId }
        val auditRedacted =
            AiCallAuditTable.update({ AiCallAuditTable.memberId eq memberId }) {
                it[AiCallAuditTable.memberId] = null
            }
        val releasesRedacted =
            AiKnowledgeReleaseTable.update({ AiKnowledgeReleaseTable.releasedBy eq memberId }) {
                it[AiKnowledgeReleaseTable.releasedBy] = null
            }
        return listOf(
            TableErasureOutcome(table = "ai_member_opt_in", rowsDeleted = optInsDeleted, retentionReason = null),
            TableErasureOutcome(
                table = "ai_call_audit",
                rowsAnonymized = auditRedacted,
                retentionReason = "Usage traceability record: only hashes and counters are retained, the member reference is removed",
            ),
            TableErasureOutcome(
                table = "ai_knowledge_release",
                rowsAnonymized = releasesRedacted,
                retentionReason = "Knowledge-base release decision is retained, the releasing member reference is removed",
            ),
        )
    }
}
