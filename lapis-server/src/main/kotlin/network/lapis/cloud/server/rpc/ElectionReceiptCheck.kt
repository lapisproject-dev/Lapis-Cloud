package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Receipt codes are 20 `SecureRandom` bytes, Base64url without padding: always exactly 27 characters. */
internal val ELECTION_RECEIPT_CODE_FORMAT = Regex("^[A-Za-z0-9_-]{27}$")

/**
 * V1.9.54 (receipt-freeness) -- the ONLY place that evaluates an election receipt code. A receipt proves
 * **inclusion only** (`found`, `counted`), never the content of the ballot: for a secret election no selection
 * or option row is ever read (gate before read), `optionLabel` is always `null`.
 *
 * An ill-formed code and an unknown code are indistinguishable (`found = false`), so format and existence form
 * no oracle. The query is restricted to [electionRow]'s own election: the code of another election is unknown.
 * `counted` is exact: the tally (`computeOutcome`) reads every ballot row of the election and drops none.
 * MUST run inside a transaction.
 */
internal fun verifyElectionReceipt(
    electionRow: ResultRow,
    receiptCode: String,
): ReceiptVerificationDto {
    if (!ELECTION_RECEIPT_CODE_FORMAT.matches(receiptCode)) return ReceiptVerificationDto(found = false, optionLabel = null)
    val wId = electionRow[ElectionTable.id]
    val ballotId =
        ElectionBallotTable
            .select(ElectionBallotTable.id)
            .where { (ElectionBallotTable.electionId eq wId) and (ElectionBallotTable.receiptCode eq receiptCode) }
            .singleOrNull()
            ?.get(ElectionBallotTable.id)
            ?: return ReceiptVerificationDto(found = false, optionLabel = null)
    val tallied = electionRow[ElectionTable.status] == ElectionStatus.TALLIED
    if (electionRow[ElectionTable.secret]) return ReceiptVerificationDto(found = true, optionLabel = null, counted = tallied)
    // Open election: unchanged behaviour, the labels after TALLIED (the ballot is public there anyway).
    val label = if (tallied) openBallotLabels(ballotId) else null
    return ReceiptVerificationDto(found = true, optionLabel = label, counted = tallied)
}

private fun openBallotLabels(ballotId: Uuid): String? =
    (ElectionBallotSelectionTable innerJoin ElectionOptionTable)
        .selectAll()
        .where { ElectionBallotSelectionTable.ballotId eq ballotId }
        .orderBy(ElectionOptionTable.position, SortOrder.ASC)
        .map { it[ElectionOptionTable.label] }
        .joinToString(", ")
        .ifBlank { null }
