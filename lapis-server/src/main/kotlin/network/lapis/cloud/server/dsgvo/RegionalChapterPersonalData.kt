package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- `regional_chapter_officer.member_id`/
 * `granted_by_member_id` carry personal data (who was granted regional-chapter-officer access to
 * what, by whom, and when). `regional_chapter` itself is NOT covered here -- its own `name` is an
 * organizational label, not personal data (see [PersonalDataRegistry.noPersonalDataAllowlist]-
 * style reasoning, though this table is deliberately left OFF that allowlist too, since it is not
 * ownerless: `RegionalChapterService` fully owns its lifecycle and `member.regional_chapter_id`
 * itself is covered by [FoundationPersonalData]).
 *
 * **Erasure**: own grants (`member_id == subject`) are hard-deleted (`rowsDeleted`) -- unlike
 * [CommunicationPersonalData]'s `direct_message`, there is no counterparty copy a grant needs to
 * preserve for. Grants the subject GRANTED to someone else (`granted_by_member_id == subject`)
 * are anonymized (`granted_by_member_id = NULL`, `rowsAnonymized`) -- same accountability-
 * preserving treatment [AccountingPersonalData]/`accounting_export_run.started_by` already
 * establishes for a "WHO did this administrative act" column: the fact that SOMEONE granted this
 * access is retained, the identity of the (now-erased) grantor is not.
 *
 * **Export completeness (security/DSGVO Art. 15 fix, LOW)**: [exportMember] used to cover only
 * `member_id == subject` rows -- but [eraseMember] treats `granted_by_member_id == subject` rows
 * as personal data too (it anonymizes them). An ADMIN who granted officer access to OTHER members
 * therefore had that administrative activity omitted from their own Art. 15 export while it was
 * still erasure-relevant, an export/erasure inconsistency. [exportMember] now emits a SECOND
 * array, `officerGrantsIssued`, for those rows -- deliberately WITHOUT the recipient's identity
 * (no `memberId`/`displayName`), since the recipient is a different data subject whose own
 * identity is not this export's business; `chapterName`/`grantedAt`/`revokedAt` describe only the
 * subject's OWN administrative act.
 */
object RegionalChapterPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "regionalChapter"
    override val displayName = "Gliederungsverwaltung (Landesverbände)"
    override val coveredTables = setOf(RegionalChapterOfficerTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("officerGrants") {
                (RegionalChapterOfficerTable innerJoin RegionalChapterTable)
                    .selectAll()
                    .where { RegionalChapterOfficerTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("chapterName", row[RegionalChapterTable.name])
                                put("grantedAt", row[RegionalChapterOfficerTable.grantedAt].toString())
                                put("revokedAt", row[RegionalChapterOfficerTable.revokedAt]?.toString())
                                put("grantedBySelf", row[RegionalChapterOfficerTable.grantedByMemberId] == memberId)
                            },
                        )
                    }
            }
            // Security/DSGVO fix (LOW, export completeness) -- see this object's own KDoc "Export
            // completeness". Deliberately no memberId/displayName of the RECIPIENT -- see that
            // KDoc for why.
            putJsonArray("officerGrantsIssued") {
                (RegionalChapterOfficerTable innerJoin RegionalChapterTable)
                    .selectAll()
                    .where { RegionalChapterOfficerTable.grantedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("chapterName", row[RegionalChapterTable.name])
                                put("grantedAt", row[RegionalChapterOfficerTable.grantedAt].toString())
                                put("revokedAt", row[RegionalChapterOfficerTable.revokedAt]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = RegionalChapterOfficerTable.deleteWhere { RegionalChapterOfficerTable.memberId eq memberId }
        val anonymized =
            RegionalChapterOfficerTable.update({ RegionalChapterOfficerTable.grantedByMemberId eq memberId }) {
                it[grantedByMemberId] = null
            }
        return listOf(
            TableErasureOutcome(table = "regional_chapter_officer", rowsDeleted = deleted, rowsAnonymized = anonymized),
        )
    }
}
