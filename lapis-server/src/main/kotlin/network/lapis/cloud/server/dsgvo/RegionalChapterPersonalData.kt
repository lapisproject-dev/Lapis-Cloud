package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.RegionalChapterTreasurerSnapshot
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * **V1.9.289 (R4a) chapter treasurer** (`regional_chapter_treasurer`): the export lists the subject's own grants (`treasurerGrants`) and those the
 * subject issued (`treasurerGrantsIssued`, without the recipient). On erasure the ACTIVE grants of the subject are REVOKED at once (the access
 * must end), the revoked rows STAY as history (the bookings and the audit trail refer to the member), and `granted_by_member_id` is cleared.
 *
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
    override val coveredTables = setOf(RegionalChapterOfficerTable, RegionalChapterTreasurerTable)

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
            putJsonArray("treasurerGrants") {
                (RegionalChapterTreasurerTable innerJoin RegionalChapterTable)
                    .selectAll()
                    .where { RegionalChapterTreasurerTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("chapterName", row[RegionalChapterTable.name])
                                put("grantedAt", row[RegionalChapterTreasurerTable.grantedAt].toString())
                                put("revokedAt", row[RegionalChapterTreasurerTable.revokedAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("treasurerGrantsIssued") {
                (RegionalChapterTreasurerTable innerJoin RegionalChapterTable)
                    .selectAll()
                    .where { RegionalChapterTreasurerTable.grantedByMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("chapterName", row[RegionalChapterTable.name])
                                put("grantedAt", row[RegionalChapterTreasurerTable.grantedAt].toString())
                                put("revokedAt", row[RegionalChapterTreasurerTable.revokedAt]?.toString())
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
        val now = DbClock.nowLocalDateTime()
        val revoked =
            RegionalChapterTreasurerTable.update(
                { (RegionalChapterTreasurerTable.memberId eq memberId) and RegionalChapterTreasurerTable.revokedAt.isNull() },
            ) {
                it[revokedAt] = now
                it[activeKey] = null
            }
        val treasurerAnonymized =
            RegionalChapterTreasurerTable.update({ RegionalChapterTreasurerTable.grantedByMemberId eq memberId }) {
                it[grantedByMemberId] = null
            }
        val retained =
            RegionalChapterTreasurerTable
                .selectAll()
                .where { RegionalChapterTreasurerTable.memberId eq memberId }
                .count()
                .toInt()
        return listOf(
            TableErasureOutcome(table = "regional_chapter_officer", rowsDeleted = deleted, rowsAnonymized = anonymized),
            TableErasureOutcome(
                table = "regional_chapter_treasurer",
                rowsAnonymized = treasurerAnonymized + revoked,
                rowsRetained = retained,
                retentionReason = "Active grants are revoked at once; revoked rows stay as history of who could book (GoBD).",
            ),
        )
    }

    /** An active chapter-treasurer grant, as read before [eraseMember] revokes it. */
    data class ActiveTreasurerGrant(
        val grantId: Uuid,
        val chapterId: Uuid,
    )

    /** The ACTIVE chapter-treasurer grants of [memberId]. Call BEFORE the contributor walk revokes them. */
    fun activeTreasurerGrants(memberId: Uuid): List<ActiveTreasurerGrant> =
        RegionalChapterTreasurerTable
            .selectAll()
            .where { (RegionalChapterTreasurerTable.memberId eq memberId) and RegionalChapterTreasurerTable.revokedAt.isNull() }
            .map {
                ActiveTreasurerGrant(
                    grantId = it[RegionalChapterTreasurerTable.id],
                    chapterId = it[RegionalChapterTreasurerTable.regionalChapterId],
                )
            }

    /**
     * GoBD: writes the `REGIONAL_CHAPTER_TREASURER`/`UPDATE` audit entry (same shape as `revokeChapterTreasurer`) for each grant that
     * [eraseMember] revoked. Deliberately NOT called from [eraseMember] itself: the audit chain lock must not be taken in the middle of
     * the contributor walk (deadlock contract of [AuditLogRecorder]); the erasure executor calls this AFTER the walk, in the same transaction.
     */
    fun recordRevocationAudit(
        actorMemberId: Uuid,
        actorRole: AccountRole,
        subjectMemberId: Uuid,
        grants: List<ActiveTreasurerGrant>,
    ) {
        val now = DbClock.nowLocalDateTime()
        grants.forEach { grant ->
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.REGIONAL_CHAPTER_TREASURER,
                entityId = grant.grantId,
                action = AuditAction.UPDATE,
                before =
                    Json.encodeToString(
                        RegionalChapterTreasurerSnapshot.serializer(),
                        RegionalChapterTreasurerSnapshot(
                            memberId = subjectMemberId.toString(),
                            regionalChapterId = grant.chapterId.toString(),
                        ),
                    ),
                after =
                    Json.encodeToString(
                        RegionalChapterTreasurerSnapshot.serializer(),
                        RegionalChapterTreasurerSnapshot(
                            memberId = subjectMemberId.toString(),
                            regionalChapterId = grant.chapterId.toString(),
                            revoked = true,
                        ),
                    ),
                occurredAt = now,
            )
        }
    }
}
