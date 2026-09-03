package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns both member-FK-bearing tables of the V0.5.2 §20 GwG Transparenzregister domain:
 * [BoardMembershipTable] (`member_id`) and [TransparenzregisterReminderTable] (`member_id` -- the
 * reminder's subject -- and `resolved_by` -- whoever acknowledged it, nullable). See
 * `network.lapis.cloud.server.rpc.BoardMembershipService` KDoc for the full domain rationale.
 *
 * Retain-with-reason across the board, same precedent as [ElectionPersonalData]/
 * [GovernancePersonalData]: who served on the Vorstand, in which role, since/until when, and the
 * Transparenzregister reminder trail (who triggered a change, who acknowledged updating the real
 * register and when) are accountability-relevant governance/compliance records under Art. 5(2)
 * DSGVO ("Rechenschaftspflicht"), not purely personal data an individual can insist on erasing --
 * a §20 GwG beneficial-owner history is exactly the kind of record whose deletion would remove the
 * organization's own evidence of who its Vorstand was and when the Transparenzregister was told.
 */
object BoardMembershipPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "boardMembership"
    override val displayName = "Vorstand / Transparenzregister"
    override val coveredTables = setOf(BoardMembershipTable, TransparenzregisterReminderTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("boardMemberships") {
                BoardMembershipTable
                    .selectAll()
                    .where { BoardMembershipTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[BoardMembershipTable.id].toString())
                                put("committeeRole", row[BoardMembershipTable.committeeRole].name)
                                put("startedAt", row[BoardMembershipTable.startedAt].toString())
                                put("endedAt", row[BoardMembershipTable.endedAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("transparenzregisterReminders") {
                // Both roles this member can appear in -- subject of the reminder (memberId) or
                // the person who acknowledged it (resolvedBy).
                TransparenzregisterReminderTable
                    .selectAll()
                    .where {
                        (TransparenzregisterReminderTable.memberId eq memberId) or
                            (TransparenzregisterReminderTable.resolvedBy eq memberId)
                    }.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[TransparenzregisterReminderTable.id].toString())
                                put("triggeredAt", row[TransparenzregisterReminderTable.triggeredAt].toString())
                                put("subjectMemberId", row[TransparenzregisterReminderTable.memberId].toString())
                                put("committeeRole", row[TransparenzregisterReminderTable.committeeRole].name)
                                put("changeType", row[TransparenzregisterReminderTable.changeType].name)
                                put("resolved", row[TransparenzregisterReminderTable.resolved])
                                put("resolvedAt", row[TransparenzregisterReminderTable.resolvedAt]?.toString())
                                put("resolvedById", row[TransparenzregisterReminderTable.resolvedBy]?.toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val boardMembershipCount = BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq memberId }.count()
        val reminderCount =
            TransparenzregisterReminderTable
                .selectAll()
                .where {
                    (TransparenzregisterReminderTable.memberId eq memberId) or
                        (TransparenzregisterReminderTable.resolvedBy eq memberId)
                }.count()
        return listOf(
            TableErasureOutcome(
                table = "board_membership",
                rowsRetained = boardMembershipCount.toInt(),
                retentionReason = "§20 GwG beneficial-owner history -- record of who served on the Vorstand, since/until when.",
            ),
            TableErasureOutcome(
                table = "transparenzregister_reminder",
                rowsRetained = reminderCount.toInt(),
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht -- record of when a Vorstandsaenderung " +
                        "occurred and who acknowledged updating the real Transparenzregister entry.",
            ),
        )
    }
}
