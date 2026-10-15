package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [AgreementTextVersionTable] (`author_member_id`) and [AgreementTextDraftTable] (`updated_by`) -- V1.9.134 "Vertragstexte".
 *
 * The member is only ever the ACTOR (who published or last edited a registration text), never the data subject of the text. Retained
 * unconditionally, regardless of [ErasureMode]: the row is the organization's evidence of who published the authoritative contract text a
 * member once accepted (Art. 5(2) DSGVO accountability); a published version is append-only by design and must stay provable. Same
 * "organizational record, actor only" class as [DsgvoCompliancePersonalData].
 *
 * [exportMember] returns metadata only (kind, version label, timestamps) -- never the text.
 */
object AgreementTextPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "agreementTexts"
    override val displayName = "Vertragstexte (Beitrittsvertrag/Freund-Nutzungsbedingungen)"
    override val coveredTables = setOf(AgreementTextVersionTable, AgreementTextDraftTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            put(
                "publishedVersions",
                buildJsonArray {
                    AgreementTextVersionTable
                        .selectAll()
                        .where { AgreementTextVersionTable.authorMemberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("kind", row[AgreementTextVersionTable.kind])
                                    put("versionLabel", row[AgreementTextVersionTable.versionLabel])
                                    put("publishedAt", row[AgreementTextVersionTable.publishedAt].toString())
                                },
                            )
                        }
                },
            )
            put(
                "drafts",
                buildJsonArray {
                    AgreementTextDraftTable
                        .selectAll()
                        .where { AgreementTextDraftTable.updatedBy eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("kind", row[AgreementTextDraftTable.kind])
                                    put("updatedAt", row[AgreementTextDraftTable.updatedAt].toString())
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val reason =
            "Organizational record of who published the authoritative registration text a member accepted (Art. 5(2) DSGVO " +
                "accountability); a published version is append-only and must stay provable. Only the actor reference is retained, " +
                "the text itself contains no personal data."
        val versions = AgreementTextVersionTable.selectAll().where { AgreementTextVersionTable.authorMemberId eq memberId }.count()
        val drafts = AgreementTextDraftTable.selectAll().where { AgreementTextDraftTable.updatedBy eq memberId }.count()
        return listOf(
            TableErasureOutcome(table = "agreement_text_version", rowsRetained = versions.toInt(), retentionReason = reason),
            TableErasureOutcome(table = "agreement_text_draft", rowsRetained = drafts.toInt(), retentionReason = reason),
        )
    }
}
