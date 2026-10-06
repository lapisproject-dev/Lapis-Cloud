package network.lapis.cloud.server.accounting.export

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

internal val FIXTURE_ENTRY_DATE = LocalDate(2026, 1, 15)
internal val FIXTURE_AMOUNT = BigDecimal("42.00")

/** Members, journal entries and export runs created by a test, removed again by [cleanup]. */
internal class ExportFixtures {
    private val memberIds = mutableListOf<Uuid>()
    private val entryIds = mutableListOf<Uuid>()
    private val runIds = mutableListOf<Uuid>()

    fun newMember(): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Idempotenz Testmitglied"
                it[email] = "idem-$id@example.org"
                it[status] = MemberStatus.ACTIVE
                it[joinedAt] = LocalDate(2020, 1, 1)
            }
        }
        memberIds += id
        return id
    }

    fun newJournalEntry(createdBy: Uuid): Uuid {
        val id = Uuid.random()
        transaction {
            JournalEntryTable.insert {
                it[JournalEntryTable.id] = id
                it[entryDate] = FIXTURE_ENTRY_DATE
                it[description] = "Idempotenz Testbuchung"
                it[voucherReference] = null
                it[JournalEntryTable.createdBy] = createdBy
                it[status] = JournalEntryStatus.POSTED
                it[postedAt] = LocalDateTime(2026, 1, 15, 9, 0)
                it[createdAt] = LocalDateTime(2026, 1, 15, 8, 0)
            }
        }
        entryIds += id
        return id
    }

    fun plannedVoucher(
        journalEntryId: Uuid,
        amount: BigDecimal = FIXTURE_AMOUNT,
    ) = PlannedVoucher(
        journalEntryId = journalEntryId,
        entryDate = FIXTURE_ENTRY_DATE,
        voucherNumber = AccountingExportPlanner.voucherNumber(entryDate = FIXTURE_ENTRY_DATE, journalEntryId = journalEntryId),
        direction = AccountingExportDirection.INCOME,
        grossAmount = amount,
        ledgerAccountId = Uuid.random(),
        externalCategoryId = "cat-1",
        externalCategoryName = "Einnahmen",
        description = "Idempotenz Testbuchung",
        voucherReference = null,
        alreadyExported = false,
    )

    /** A RUNNING run with one PENDING item per fresh journal entry; returns the run id and the item ids (voucher-number order is not guaranteed). */
    fun newRun(
        actor: Uuid,
        now: LocalDateTime,
        items: Int = 1,
        provider: AccountingExportProvider = AccountingExportProvider.LEXOFFICE,
    ): Pair<Uuid, List<Uuid>> {
        val entries = List(items) { newJournalEntry(actor) }
        val runId =
            AccountingExportStore.createRun(
                provider = provider,
                from = LocalDate(2026, 1, 1),
                to = LocalDate(2026, 1, 31),
                startedBy = actor,
                now = now,
                vouchers = entries.map { plannedVoucher(journalEntryId = it) },
            )
        runIds += runId
        return runId to itemIdsOf(runId)
    }

    /** Registers a run created elsewhere (for example a second `createRun` for the same entry) for cleanup. */
    fun track(runId: Uuid) {
        runIds += runId
    }

    fun itemIdsOf(runId: Uuid): List<Uuid> =
        transaction {
            AccountingExportItemTable
                .selectAll()
                .where {
                    AccountingExportItemTable.runId eq runId
                }.map { it[AccountingExportItemTable.id] }
        }

    fun cleanup() {
        transaction {
            if (runIds.isNotEmpty()) {
                val list = runIds.joinToString(",") { "'$it'" }
                exec("DELETE FROM accounting_export_item WHERE run_id IN ($list)")
                exec("DELETE FROM accounting_export_run WHERE id IN ($list)")
            }
            exec("DELETE FROM accounting_export_connection")
            if (entryIds.isNotEmpty()) JournalEntryTable.deleteWhere { JournalEntryTable.id inList entryIds }
            if (memberIds.isNotEmpty()) MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
        runIds.clear()
        entryIds.clear()
        memberIds.clear()
    }
}
