package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.MemberHonorTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- owns [MemberHonorTable], which
 * carries a member in TWO roles: `member_id` (the honoree) and `recorded_by` (whoever entered the
 * row, always BOARD/ADMIN). See `network.lapis.cloud.server.rpc.MemberHonorService` KDoc for the
 * full domain rationale.
 *
 * **Hybrid erasure, honoree role only**: that the organization awarded a member an honor, when, and
 * of what category is an organizational/accountability record under Art. 5(2) DSGVO
 * (Rechenschaftspflicht) -- same "retain the fact, not necessarily every word" posture
 * [BoardMembershipPersonalData] already takes for board service history. The row itself is
 * therefore RETAINED regardless of [mode]. `note`, however, is a free-text Vorstandskommentar ÜBER
 * die honorierte Person (`41-member-honor.kuml.kts` file header) -- that text is nulled
 * unconditionally on the honoree's erasure, same "genulled on Art. 17" posture `crm_contact`'s own
 * free-text fields take, just unconditional here rather than gated on [ErasureMode] (there is no
 * third party's own interest in this particular free text the way [CommunicationPersonalData]'s
 * `direct_message` body has a recipient's interest).
 *
 * **`recorded_by` role is NOT redacted**: for the person who merely entered the row (not the
 * honoree), `note` stays fachlich fremd -- it is not commentary about THEM, so their own erasure
 * has no reason to touch it. Only the row's mere existence (`recorded_by = memberId`) is retained
 * as an accountability record of who entered it, same as every other `recorded_by`/`created_by`
 * column across this codebase.
 *
 * **Row-counting, not naive double-counting** (Welle-Plan §13 "S4"): `member_id` and `recorded_by`
 * CAN coincide on the same row (a board member honoring themselves is not excluded by any FK
 * constraint). [total] is therefore computed with a single `OR` condition -- never as
 * `memberIdCount + recordedByCount`, which would double-count an overlapping row exactly once too
 * often. [anonymizedCount] is the row count [note]-nulling actually touched (a subset of [total] by
 * construction, since it only ever matches on `member_id`), so `rowsRetained = total -
 * anonymizedCount` is correct with or without an overlap.
 */
object MemberHonorPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberHonors"
    override val displayName = "Ehrungen & Auszeichnungen"
    override val coveredTables = setOf(MemberHonorTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("memberHonors") {
                MemberHonorTable
                    .selectAll()
                    .where { (MemberHonorTable.memberId eq memberId) or (MemberHonorTable.recordedBy eq memberId) }
                    .forEach { row ->
                        val subjectIsHonoree = row[MemberHonorTable.memberId] == memberId
                        val subjectIsRecorder = row[MemberHonorTable.recordedBy] == memberId
                        add(
                            buildJsonObject {
                                put("id", row[MemberHonorTable.id].toString())
                                put("subjectRoleHonoree", subjectIsHonoree)
                                put("subjectRoleRecorder", subjectIsRecorder)
                                put("category", row[MemberHonorTable.category].name)
                                put("title", row[MemberHonorTable.title])
                                put("awardedAt", row[MemberHonorTable.awardedAt].toString())
                                put("awardedBy", row[MemberHonorTable.awardedBy])
                                put("note", row[MemberHonorTable.note])
                                put("recordedAt", row[MemberHonorTable.recordedAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total =
            MemberHonorTable
                .selectAll()
                .where { (MemberHonorTable.memberId eq memberId) or (MemberHonorTable.recordedBy eq memberId) }
                .count()
                .toInt()
        val anonymizedCount =
            MemberHonorTable.update({ MemberHonorTable.memberId eq memberId }) { it[note] = null }
        return listOf(
            TableErasureOutcome(
                table = "member_honor",
                rowsAnonymized = anonymizedCount,
                rowsRetained = total - anonymizedCount,
                retentionReason =
                    "Art. 5(2) DSGVO Rechenschaftspflicht -- dass und wann die Organisation eine " +
                        "Ehrung ausgesprochen hat, ist ein organisatorischer Vorgang. Die freitextliche " +
                        "Vorstandskommentar-Begründung (note) wird für die honorierte Rolle geleert; " +
                        "für die reine Erfasser-Rolle (recorded_by) bleibt sie unangetastet, weil sie " +
                        "dort fachlich fremd ist.",
            ),
        )
    }
}
