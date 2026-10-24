package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** One planned `ltr_ledger_entry` row of a top-up part; [member] is the seed member number (as in [SeedIds.member]). */
internal data class SeedLedgerRow(
    val id: Uuid,
    val member: Int,
    val type: LtrLedgerEntryType,
    val amount: BigDecimal,
    val referenceType: LtrLedgerReferenceType?,
    val referenceId: Uuid?,
    val note: String,
    val at: LocalDateTime,
)

/** Result of one top-up part: entities written and entities skipped (already there, member not eligible, balance not covering). */
internal data class StagingSeedPartResult(
    val inserted: Int,
    val skipped: Int,
)

/** What the run reports per part; [failed] means the part's transaction was rolled back. */
internal data class StagingSeedPartReport(
    val part: String,
    val inserted: Int,
    val skipped: Int,
    val failed: Boolean,
)

/**
 * One unit of the V1.9.205 top-up. Each part runs in its OWN transaction whose first statement is the instance check (see
 * [StagingSeedTopUp.topUpWith]). A part writes an entity only as a whole (root row, its ledger rows, its children) and only when the root id is
 * missing, every involved member has a fitting status and the current free balance covers every debit; otherwise the entity is skipped.
 * Rows are never updated or deleted, except status transitions of rows that the same run has just inserted.
 */
internal interface StagingSeedPart {
    /** Stable, used in logs and in the run report. */
    val name: String

    fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult
}

/** Shared writer and checks for the ledger rows of the top-up parts. Pure functions are separated so the plan test can run without a database. */
internal object StagingSeedLedger {
    const val NOTE_PREFIX: String = "[SEED]"

    private val balanceProvider = LedgerBackedLtrBalanceProvider()

    fun note(text: String): String = "$NOTE_PREFIX $text"

    /** Current statuses of the given seed member numbers; members that do not exist are absent from the map. */
    fun JdbcTransaction.statusesOf(numbers: Collection<Int>): Map<Int, MemberStatus> {
        if (numbers.isEmpty()) return emptyMap()
        val ids = numbers.toSet().associateBy { SeedIds.member(it) }
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList ids.keys.toList() }
            .mapNotNull { row -> ids[row[MemberTable.id]]?.let { it to row[MemberTable.status] } }
            .toMap()
    }

    /** The current free LTR balance through the one source the views use. */
    fun JdbcTransaction.freeBalance(number: Int): BigDecimal = balanceProvider.freeBalance(SeedIds.member(number))

    /**
     * Smallest running balance (starting at zero, never above zero) per member over [rows], in the order of [rows] after a stable sort by time.
     * A bundle is covered when the current free balance plus this value is not negative for every member.
     */
    fun lowestRunning(rows: List<SeedLedgerRow>): Map<Int, BigDecimal> =
        rows
            .sortedBy { it.at }
            .groupBy { it.member }
            .mapValues { (_, memberRows) ->
                var running = BigDecimal.ZERO
                var lowest = BigDecimal.ZERO
                memberRows.forEach {
                    running += it.amount
                    if (running < lowest) lowest = running
                }
                lowest
            }

    /** `true` when every member of [rows] could carry the bundle from today's balance without going below zero. */
    fun JdbcTransaction.covers(rows: List<SeedLedgerRow>): Boolean =
        lowestRunning(rows).all { (member, lowest) -> freeBalance(member) + lowest >= BigDecimal.ZERO }

    fun JdbcTransaction.rowExists(id: Uuid): Boolean = !LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.id eq id }.empty()

    fun JdbcTransaction.insertRow(row: SeedLedgerRow) {
        LtrLedgerEntryTable.insert {
            it[LtrLedgerEntryTable.id] = row.id
            it[memberId] = SeedIds.member(row.member)
            it[entryType] = row.type
            it[amountLtr] = row.amount
            it[referenceType] = row.referenceType
            it[referenceId] = row.referenceId
            it[note] = row.note
            it[createdBy] = null
            it[createdAt] = row.at
        }
    }

    fun JdbcTransaction.insertRows(rows: List<SeedLedgerRow>) {
        rows.forEach { if (!rowExists(it.id)) insertRow(it) }
    }
}
