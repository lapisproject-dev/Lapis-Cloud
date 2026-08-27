package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [AuditLogEntryTable] -- the only member-FK-bearing table the V0.5.3 GoBD audit-log domain
 * adds (`actor_member_id`). See `network.lapis.cloud.server.audit.AuditLogRecorder` KDoc for the
 * write path and `14-audit-log.kuml.kts`'s file header for the domain rationale.
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared, EVEN
 * stronger than [AccountingPersonalData]'s own retain-with-reason.** GoBD Nachvollziehbarkeit
 * requires knowing WHO made a change, permanently -- anonymizing or deleting `actor_member_id`
 * would not just fail to protect the actor's privacy (they remain identifiable via every
 * [AuditLogEntryTable.beforeSnapshot]/[AuditLogEntryTable.afterSnapshot] payload they authored
 * anyway, which are themselves retained as part of the accompanying, GoBD-retained
 * `journal_entry`/`resolution`/`board_membership` record) -- it would actively undermine the very
 * accountability trail this table exists to provide (Art. 17(3)(b) DSGVO: erasure does not apply
 * where processing is necessary for compliance with a legal obligation).
 *
 * Current understanding, not a reviewed legal conclusion -- see `14-audit-log.kuml.kts`'s
 * top-of-file disclaimer for the same "verify against the current GoBD text and a lawyer/
 * Steuerberater before relying on this for a real Verein/Partei" caveat, which applies here too.
 *
 * [export] deliberately returns only [AuditLogEntryTable.id]/`sequenceNumber`/`occurredAt`/
 * `entityType`/`entityId`/`action` for the rows where [memberId] was the actor -- NOT
 * `beforeSnapshot`/`afterSnapshot`. A snapshot can legitimately reference a DIFFERENT person's data
 * (e.g. a `JournalEntrySnapshot.donorMemberId` naming some other member as the donor a treasurer
 * booked a donation for) -- exporting the raw snapshot into `memberId`'s own DSGVO export would
 * leak that third party's data into an unrelated data subject's export. A member wanting the full
 * detail of a JournalEntry/Resolution/BoardMembership they were *involved in* (not just the actor
 * who recorded it) already has that via [AccountingPersonalData]/[GovernancePersonalData]/
 * [BoardMembershipPersonalData]'s own exports.
 *
 * **Security fix (2026-08-27, LOW DSGVO Art. 15)**: [export] used to filter on `actorMemberId`
 * ONLY, exactly the gap [MemberChangeSnapshot]'s own "Security fix (2026-08-27, DSGVO Art. 17/15
 * MAJOR)" KDoc paragraph flags but does not itself close. A board decision recorded via
 * `network.lapis.cloud.server.rpc.MemberService.updateMemberStatus`/`updateMemberRole` names
 * [memberId] as the row's `entityId` (the SUBJECT), not its `actorMemberId` (the board member who
 * acted) -- so the subject's own Art. 15 export never surfaced so much as the FACT that their
 * status/role changed, let alone the mandatory `reason` text a board writes about them (3-1000
 * chars, see `MemberService.MIN_REASON_LENGTH`/`MAX_REASON_LENGTH`). [export] now ALSO includes
 * rows where [memberId] is the `entityType == MEMBER` row's `entityId`, and for exactly those rows
 * surfaces `status`/`role`/`reason` from [AuditLogEntryTable.afterSnapshot] -- safe to expose to
 * the subject themselves because [MemberChangeSnapshot] no longer carries `displayName`/`email`
 * content (see that type's own KDoc). Rows where [memberId] is merely the ACTOR (not the subject)
 * still never surface `beforeSnapshot`/`afterSnapshot` -- the third-party-leak reasoning above is
 * unchanged for those.
 */
object AuditLogPersonalData : PersonalDataContributor {
    override val sectionKey = "auditLog"
    override val displayName = "Audit-Log (GoBD)"
    override val coveredTables = setOf(AuditLogEntryTable)

    override fun export(memberId: Uuid) =
        buildJsonArray {
            AuditLogEntryTable
                .selectAll()
                .where {
                    (AuditLogEntryTable.actorMemberId eq memberId) or
                        ((AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq memberId))
                }.forEach { row ->
                    val isSubjectsOwnMemberEntry =
                        row[AuditLogEntryTable.entityType] == AuditEntityType.MEMBER &&
                            row[AuditLogEntryTable.entityId] == memberId
                    add(
                        buildJsonObject {
                            put("id", row[AuditLogEntryTable.id].toString())
                            put("sequenceNumber", row[AuditLogEntryTable.sequenceNumber])
                            put("occurredAt", row[AuditLogEntryTable.occurredAt].toString())
                            put("entityType", row[AuditLogEntryTable.entityType].name)
                            put("entityId", row[AuditLogEntryTable.entityId].toString())
                            put("action", row[AuditLogEntryTable.action].name)
                            // Only for the SUBJECT's own member-entity rows -- see this object's own
                            // "Security fix (2026-08-27, LOW DSGVO Art. 15)" KDoc for why this is safe
                            // (no displayName/email content in this snapshot type) and why rows where
                            // memberId was merely the actor still never surface a snapshot.
                            if (isSubjectsOwnMemberEntry) {
                                row[AuditLogEntryTable.afterSnapshot]?.let { afterJson ->
                                    runCatching {
                                        Json.decodeFromString(MemberChangeSnapshot.serializer(), afterJson)
                                    }.onSuccess { after ->
                                        put("status", after.status.name)
                                        put("role", after.role?.name)
                                        put("reason", after.reason)
                                    }
                                }
                            }
                        },
                    )
                }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        // Security fix (2026-08-27, LOW DSGVO Art. 12/17): this predicate MUST stay in lockstep
        // with export()'s -- it used to count only actorMemberId rows, while export() (see this
        // object's own "Security fix (2026-08-27, LOW DSGVO Art. 15)" KDoc above) already surfaces
        // rows where memberId is merely the SUBJECT (entityType == MEMBER, entityId == memberId),
        // e.g. board decisions recorded via MemberService.updateMemberStatus/-Role about a member
        // who never triggered an audited action themselves. Counting only actor rows made the
        // Art. 17 erasure confirmation UNDERSTATE what is retained about the requester relative to
        // what the Art. 15 export already discloses to them -- rows containing status/role and the
        // board's freeform reason text about this member (MemberService.kt:466-469) would silently
        // vanish from the retained-rows tally.
        val total =
            AuditLogEntryTable
                .selectAll()
                .where {
                    (AuditLogEntryTable.actorMemberId eq memberId) or
                        ((AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and (AuditLogEntryTable.entityId eq memberId))
                }.count()
        return listOf(
            TableErasureOutcome(
                table = "audit_log_entry",
                rowsRetained = total.toInt(),
                retentionReason =
                    "GoBD Nachvollziehbarkeit/Unveraenderbarkeit -- the actor identity is the core " +
                        "of the accountability trail this table exists to provide; it is never " +
                        "cleared or anonymized, and the row itself is append-only/immutable by " +
                        "construction (see AuditLogRecorder). Rows where this member is merely the " +
                        "SUBJECT of another actor's recorded decision (status/role change and its " +
                        "reason text) are counted here too, for the same reason export() surfaces " +
                        "them to the subject under Art. 15 -- see this class's KDoc.",
            ),
        )
    }
}
