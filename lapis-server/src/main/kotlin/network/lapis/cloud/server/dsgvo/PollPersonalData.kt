package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 "Umfragen auf LTR-Basis" -- member-FK-bearing poll tables: [PollTable]
 * (`created_by`, `closed_by`) and [PollParticipationTable] (`member_id`).
 * `poll_option` (no member FK) and `poll_response` (BY DESIGN no member FK -- the anonymous answer)
 * are in [PersonalDataRegistry.noPersonalDataAllowlist] with their reasons.
 *
 * **Export**: polls the member created/closed/aborted and, per participation, ONLY the poll id ("took
 * part") -- never the answer, which cannot be attributed to the member at all.
 *
 * **Erasure**: the member's `poll_participation` rows are hard-deleted -- safe, because an erased
 * member is not ACTIVE any more and can never respond again (so the unique index has nothing left to
 * protect), and the anonymous `poll_response` rows stay untouched (they never pointed at the member).
 * `poll.created_by`/`closed_by` are retained: accountability for an organisation-wide question
 * (precedent [ElectionPersonalData]).
 */
object PollPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "polls"
    override val displayName = "Umfragen"
    override val coveredTables = setOf(PollTable, PollParticipationTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("pollsCreated") {
                PollTable
                    .selectAll()
                    .where { PollTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[PollTable.id].toString())
                                put("question", row[PollTable.question])
                                put("status", row[PollTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("pollsClosedOrAborted") {
                PollTable
                    .selectAll()
                    .where { PollTable.closedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[PollTable.id].toString())
                                put("question", row[PollTable.question])
                                put("status", row[PollTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("pollParticipations") {
                // Only "took part" -- the answer is not attributable to the member.
                PollParticipationTable
                    .selectAll()
                    .where { PollParticipationTable.memberId eq memberId }
                    .forEach { row -> add(buildJsonObject { put("pollId", row[PollParticipationTable.pollId].toString()) }) }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val retained =
            PollTable.selectAll().where { (PollTable.createdBy eq memberId) or (PollTable.closedBy eq memberId) }.count()
        val deleted = PollParticipationTable.deleteWhere { PollParticipationTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(
                table = "poll",
                rowsRetained = retained.toInt(),
                retentionReason = "Accountability for an organisation-wide question (creator/closer).",
            ),
            TableErasureOutcome(table = "poll_participation", rowsDeleted = deleted),
        )
    }
}
