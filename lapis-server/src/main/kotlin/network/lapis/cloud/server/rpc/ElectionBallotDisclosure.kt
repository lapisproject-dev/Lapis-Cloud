package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.shared.domain.ElectionBallotDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.46 -- true iff the single ballots of this election may be delivered at all.
 * A secret election never delivers single ballots: no status, no participation, no role.
 */
internal fun electionSingleBallotsDisclosable(secret: Boolean): Boolean = singleBallotsDisclosable(secret)

/**
 * The ONLY way to a list of [ElectionBallotDto]. Empty for a secret election before any ballot
 * or selection row is read. Open election: every ballot, named, ordered by castAt, id.
 * MUST run inside a transaction. [row] is the already-loaded election row.
 */
internal fun disclosedElectionBallots(
    row: ResultRow,
    memberDisplayName: (Uuid?) -> String?,
): List<ElectionBallotDto> {
    if (!electionSingleBallotsDisclosable(row[ElectionTable.secret])) return emptyList()
    val wId = row[ElectionTable.id]
    return ElectionBallotTable
        .selectAll()
        .where { ElectionBallotTable.electionId eq wId }
        .orderBy(ElectionBallotTable.castAt to SortOrder.ASC, ElectionBallotTable.id to SortOrder.ASC)
        .map { it.toOpenElectionBallotDto(memberDisplayName) }
}

private fun ResultRow.toOpenElectionBallotDto(memberDisplayName: (Uuid?) -> String?): ElectionBallotDto {
    val ballotId = this[ElectionBallotTable.id]
    val memberId = this[ElectionBallotTable.memberId]
    val labels =
        (ElectionBallotSelectionTable innerJoin ElectionOptionTable)
            .selectAll()
            .where { ElectionBallotSelectionTable.ballotId eq ballotId }
            .orderBy(ElectionOptionTable.position, SortOrder.ASC)
            .map { it[ElectionOptionTable.label] }
    return ElectionBallotDto(
        id = ballotId.toString(),
        electionId = this[ElectionBallotTable.electionId].toString(),
        memberId = memberId?.toString(),
        memberDisplayName = memberDisplayName(memberId),
        selectedOptionLabels = labels,
        castAt = this[ElectionBallotTable.castAt],
    )
}
