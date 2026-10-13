package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchItemTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** Fixed test key -- the SEPA export and refund-account tests share it. */
internal val SEPA_TEST_KEY: ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES) { (it * 7 + 3).toByte() }

internal const val DEBTOR_IBAN = "DE89370400440532013000"
internal const val MEMBER_IBAN_1 = "DE02120300000000202051"
internal const val MEMBER_IBAN_2 = "AT611904300234573201"
internal const val ORGANIZATION_TEST_NAME = "Partei der Vernunft Testverband"

/**
 * Builds the data a SEPA export needs directly in the database (no service flow): booked reports (EXECUTED + journal entry with
 * a CREDIT on the payment bank account), encrypted refund IBANs, a debtor bank account and the organization settings. Everything
 * is removed again by [cleanUp]; the organization settings are restored.
 */
internal class TravelExpenseSepaTestData(
    private val data: PollTestData,
) {
    private val reportIds = mutableListOf<Uuid>()
    private val journalEntryIds = mutableListOf<Uuid>()
    private val ledgerAccountIds = mutableListOf<Uuid>()
    private val bankAccountIds = mutableListOf<Uuid>()
    private var originalSettings: Triple<Uuid?, String?, String>? = null

    lateinit var bankLedgerAccountId: Uuid
        private set

    fun actor(
        label: String,
        role: AccountRole,
    ): CurrentMember = CurrentMember(memberId = data.member(label = label, role = role), role = role, status = MemberStatus.ACTIVE)

    /** Creates the bank ledger account and points the organization settings at it; remembers the original values. */
    fun configureOrganization(name: String = ORGANIZATION_TEST_NAME) {
        bankLedgerAccountId = ledgerAccount(LedgerAccountType.ASSET)
        transaction {
            val row = OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.single()
            originalSettings =
                Triple(
                    row[OrganizationSettingsTable.paymentBankAccountId],
                    row[OrganizationSettingsTable.sepaCreditorName],
                    row[OrganizationSettingsTable.name],
                )
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentBankAccountId] = bankLedgerAccountId
                it[sepaCreditorName] = name
            }
        }
    }

    fun setOrganizationName(
        creditorName: String?,
        name: String? = null,
    ) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[sepaCreditorName] = creditorName
                if (name != null) it[OrganizationSettingsTable.name] = name
            }
        }
    }

    fun ledgerAccount(type: LedgerAccountType): Uuid {
        val id = Uuid.random()
        transaction {
            LedgerAccountTable.insert {
                it[LedgerAccountTable.id] = id
                it[accountNumber] = "S${id.toString().filter { c -> c.isDigit() }.take(9)}"
                it[name] = "SEPA-Testkonto ${id.toString().take(8)}"
                it[accountClass] = 0
                it[LedgerAccountTable.type] = type
                it[active] = true
                it[reserveType] = null
                it[isCashRegister] = false
            }
        }
        ledgerAccountIds += id
        return id
    }

    fun bankAccount(
        createdBy: Uuid,
        iban: String = DEBTOR_IBAN,
        label: String = "Hauptkonto",
        bic: String? = "COBADEFFXXX",
        isDefault: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            BankAccountTable.insert {
                it[BankAccountTable.id] = id
                it[BankAccountTable.label] = label
                it[BankAccountTable.iban] = iban
                it[BankAccountTable.bic] = bic
                it[bankName] = "Testbank"
                it[BankAccountTable.isDefault] = isDefault
                it[defaultMarker] = if (isDefault) "X" else null
                it[BankAccountTable.createdBy] = createdBy
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        bankAccountIds += id
        return id
    }

    /** Clears every default flag, returns the ids that were default so the caller can restore them. */
    fun clearDefaultBankAccounts(): List<Uuid> =
        transaction {
            val defaults = BankAccountTable.selectAll().where { BankAccountTable.isDefault eq true }.map { it[BankAccountTable.id] }
            BankAccountTable.update({ BankAccountTable.isDefault eq true }) {
                it[isDefault] = false
                it[defaultMarker] = null
            }
            defaults
        }

    fun restoreDefaultBankAccounts(ids: List<Uuid>) {
        transaction {
            BankAccountTable.update({ BankAccountTable.id neq Uuid.NIL }) {
                it[isDefault] = false
                it[defaultMarker] = null
            }
            if (ids.isNotEmpty()) {
                BankAccountTable.update({ BankAccountTable.id inList ids }) {
                    it[isDefault] = true
                    it[defaultMarker] = "X"
                }
            }
        }
    }

    fun makeDefault(bankAccountId: Uuid) {
        transaction {
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[isDefault] = true
                it[defaultMarker] = "X"
            }
        }
    }

    /**
     * An EXECUTED, booked report. [bookedBankCreditCents] defaults to the payable amount (`total - advance`); pass a different value
     * to model a booking that does not match.
     */
    @Suppress("LongParameterList")
    fun executedReport(
        subject: Uuid,
        requester: Uuid = subject,
        totalCents: Long = 10_000,
        advanceCents: Long = 2_000,
        iban: String? = MEMBER_IBAN_1,
        holder: String? = "Erika Mustermann",
        payout: TravelExpensePayoutChoice = TravelExpensePayoutChoice.PAYOUT,
        status: TravelExpenseReportStatus = TravelExpenseReportStatus.EXECUTED,
        bookedBankCreditCents: Long? = null,
        travelTo: LocalDate = LocalDate(2026, 9, 20),
        advanceCreditAccount: Uuid? = null,
        paidOutsideLapis: Boolean = false,
    ): Uuid {
        val reportId = Uuid.random()
        val entryId = Uuid.random()
        val payable = totalCents - advanceCents
        val booked = status == TravelExpenseReportStatus.EXECUTED
        transaction {
            if (booked) {
                JournalEntryTable.insert {
                    it[id] = entryId
                    it[entryDate] = LocalDate(2026, 9, 25)
                    it[description] = "Reisekosten Test $reportId"
                    it[voucherReference] = "TRAVEL-$reportId"
                    it[createdBy] = requester
                    it[JournalEntryTable.status] = JournalEntryStatus.POSTED
                    it[postedAt] = LocalDateTime(2026, 9, 25, 10, 0)
                    it[createdAt] = LocalDateTime(2026, 9, 25, 10, 0)
                }
                journalEntryIds += entryId

                fun posting(
                    account: Uuid,
                    side: PostingSide,
                    cents: Long,
                ) = PostingTable.insert {
                    it[id] = Uuid.random()
                    it[journalEntryId] = entryId
                    it[ledgerAccountId] = account
                    it[PostingTable.side] = side
                    it[amount] = BigDecimal.valueOf(cents, 2)
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
                val expense = ledgerAccount(LedgerAccountType.EXPENSE)
                posting(expense, PostingSide.DEBIT, totalCents)
                val credit = bookedBankCreditCents ?: payable
                if (credit > 0) posting(bankLedgerAccountId, PostingSide.CREDIT, credit)
                if (advanceCreditAccount != null && advanceCents > 0) posting(advanceCreditAccount, PostingSide.CREDIT, advanceCents)
            }
            TravelExpenseReportTable.insert {
                it[id] = reportId
                it[subjectMemberId] = subject
                it[TravelExpenseReportTable.status] = status
                it[purpose] = "Vorstandssitzung"
                it[travelFrom] = travelTo
                it[TravelExpenseReportTable.travelTo] = travelTo
                it[totalAmount] = BigDecimal.valueOf(totalCents, 2)
                it[createdAt] = LocalDateTime(2026, 9, 21, 8, 0)
                it[requestedBy] = requester
                it[submittedAt] = LocalDateTime(2026, 9, 21, 9, 0)
                it[decidedAt] = LocalDateTime(2026, 9, 25, 10, 0)
                it[decisionNote] = "ok"
                if (booked) {
                    it[executedAt] = LocalDateTime(2026, 9, 25, 10, 0)
                    it[postedJournalEntryId] = entryId
                }
                it[advanceAmount] = BigDecimal.valueOf(advanceCents, 2)
                it[payoutChoice] = payout
                it[TravelExpenseReportTable.paidOutsideLapis] = paidOutsideLapis
                if (iban != null) {
                    it[refundIbanCiphertext] =
                        TravelExpenseRefundAccount.seal(box = SecretBox(SEPA_TEST_KEY), reportId = reportId, normalizedIban = iban)
                    it[refundIbanLast4] = iban.takeLast(4)
                    it[refundAccountHolder] = holder
                }
            }
        }
        reportIds += reportId
        return reportId
    }

    /** Points the organization settings at another payment bank ledger account (the original is restored by [cleanUp]). */
    fun switchPaymentBankAccount(accountId: Uuid) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[paymentBankAccountId] = accountId
            }
        }
    }

    fun report(id: Uuid) = transaction { TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq id }.single() }

    fun batchCount(): Long =
        transaction {
            TravelExpenseSepaBatchTable
                .selectAll()
                .where {
                    TravelExpenseSepaBatchTable.createdBy inList
                        data.memberIds
                }.count()
        }

    fun cleanUp() {
        transaction {
            val items =
                TravelExpenseSepaBatchItemTable
                    .selectAll()
                    .where { TravelExpenseSepaBatchItemTable.reportId inList reportIds }
                    .toList()
            val batchIds = items.map { it[TravelExpenseSepaBatchItemTable.batchId] }.distinct()
            TravelExpenseReportTable.update({ TravelExpenseReportTable.id inList reportIds }) {
                it[sepaExportBatchId] = null
                it[sepaExportedAt] = null
                it[sepaExportedBy] = null
            }
            TravelExpenseSepaBatchItemTable.deleteWhere { TravelExpenseSepaBatchItemTable.batchId inList batchIds }
            TravelExpenseSepaBatchTable.deleteWhere { TravelExpenseSepaBatchTable.id inList batchIds }
            TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
            TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
            originalSettings?.let { (bank, creditor, name) ->
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[paymentBankAccountId] = bank
                    it[sepaCreditorName] = creditor
                    it[OrganizationSettingsTable.name] = name
                }
            }
            BankAccountTable.deleteWhere { BankAccountTable.id inList bankAccountIds }
            PostingTable.deleteWhere { PostingTable.ledgerAccountId inList ledgerAccountIds }
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ledgerAccountIds }
            AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityId inList (reportIds + batchIds) }
        }
    }
}
