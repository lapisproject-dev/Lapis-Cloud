package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchItemTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
import network.lapis.cloud.server.payment.sepa.BicValidator
import network.lapis.cloud.server.payment.sepa.IbanValidator
import network.lapis.cloud.server.payment.sepa.Pain001Batch
import network.lapis.cloud.server.payment.sepa.Pain001Transaction
import network.lapis.cloud.server.payment.sepa.SepaCharacterSet
import network.lapis.cloud.server.payment.sepa.SepaPain001Writer
import network.lapis.cloud.server.payment.sepa.Target2Calendar
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaBatchSnapshot
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker
import network.lapis.cloud.shared.domain.TravelExpenseSepaReference
import network.lapis.cloud.shared.domain.TravelExpenseSnapshot
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private val SEPA_EXPORT_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** How a [TravelExpenseSepaException] maps onto HTTP. */
internal enum class SepaFailureKind { BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT }

/**
 * Failure of the SEPA export with a stable machine-readable [code]. The message and the [blockers] never contain an IBAN, a holder
 * or a member name -- only ids the caller already sent and enum values.
 */
internal class TravelExpenseSepaException(
    val kind: SepaFailureKind,
    val code: String,
    message: String = code,
    val blockers: Map<String, TravelExpenseSepaBlocker> = emptyMap(),
) : RuntimeException(message)

/**
 * Welle V1.9.123 "SEPA-Ueberweisungsdatei fuer genehmigte Reisekosten" -- the domain logic of the two export routes
 * (`TravelExpenseSepaRoutes`) and of the blocker computation the candidate list shows. No dependency on `ApplicationCall`, so the route
 * and the tests call it directly.
 *
 * **One request = one transaction = file + marks.** The pain.001 bytes are produced as pure computation inside the transaction, the
 * batch/items/report marks are written in the SAME transaction, and the route writes the response only after the commit. The XML is
 * never stored; there is no second download. A concurrent second export of the same report blocks on the row lock
 * (`SELECT ... FOR UPDATE`, rows sorted by id: no deadlocks) and then fails with `ALREADY_EXPORTED`.
 *
 * **Four eyes (identical to `decideReport`/`retryPosting`).** The subject and the requester of a report may never export it
 * (`OWN_REPORT`); the person who approved it may (they are not the beneficiary).
 *
 * **Amount invariant.** The transfer amount is `total_amount - advance_amount` in exact cents, taken from the database (never from
 * the client) AND compared with the CREDIT posting on the payment bank account of the booked journal entry
 * (`AMOUNT_MISMATCH_WITH_BOOKING`): file and booking can never drift apart.
 *
 * **Privacy.** The member IBAN is decrypted only in memory, inside the transaction, and ends up only in the returned bytes.
 * Neither audit entries, logs, exception messages nor batch tables carry an IBAN, a holder or a name.
 */
internal class TravelExpenseSepaExport(
    private val secretKey: ByteArray?,
) {
    class Result(
        val batchId: Uuid,
        val filename: String,
        val xml: ByteArray,
        val count: Int,
        val sumCents: Long,
    ) {
        /** Never print the file. */
        override fun toString(): String = "Result(batchId=$batchId, count=$count, sumCents=$sumCents)"
    }

    private class Candidate(
        val row: ResultRow,
        val payableCents: Long,
    )

    fun export(
        actor: CurrentMember,
        reportIds: List<Uuid>,
        debtorBankAccountId: Uuid?,
        executionDate: LocalDate,
    ): Result {
        requireRole(actor)
        if (reportIds.isEmpty()) throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "no_reports_selected")
        if (reportIds.toSet().size !=
            reportIds.size
        ) {
            throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "duplicate_reports")
        }
        if (reportIds.size > SepaPain001Writer.MAX_TRANSACTIONS) {
            throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "too_many_transactions")
        }
        return generate(
            actor = actor,
            reportIds = reportIds,
            reexportReason = null,
            debtorBankAccountId = debtorBankAccountId,
            executionDate = executionDate,
        )
    }

    fun reexport(
        actor: CurrentMember,
        reportId: Uuid,
        reason: String,
        debtorBankAccountId: Uuid?,
        executionDate: LocalDate,
    ): Result {
        requireRole(actor)
        val trimmed = reason.trim()
        if (trimmed.length < MIN_REASON_LENGTH || trimmed.length > MAX_REASON_LENGTH) {
            throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "reason_length_invalid")
        }
        if (containsIban(trimmed)) {
            throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "reason_contains_account_number")
        }
        return generate(
            actor = actor,
            reportIds = listOf(reportId),
            reexportReason = trimmed,
            debtorBankAccountId = debtorBankAccountId,
            executionDate = executionDate,
        )
    }

    /**
     * Blockers for a candidate listing; must run inside the caller's transaction. `null` == can be exported by [actor]. Does NOT
     * re-check roles.
     */
    fun blockersFor(
        rows: List<ResultRow>,
        actor: CurrentMember?,
    ): Map<Uuid, TravelExpenseSepaBlocker?> {
        if (rows.isEmpty()) return emptyMap()
        val credits = bookedBankCredits(rows)
        return rows.associate {
            it[TravelExpenseReportTable.id] to
                blockerOf(row = it, actor = actor, bookedBankCreditCents = credits[it[TravelExpenseReportTable.postedJournalEntryId]])
        }
    }

    /** Precedence: booked, payout, payable, paid outside Lapis, already exported, own report, IBAN, key, booking amount. */
    internal fun blockerOf(
        row: ResultRow,
        actor: CurrentMember?,
        bookedBankCreditCents: Long?,
        forReexport: Boolean = false,
    ): TravelExpenseSepaBlocker? {
        if (row[TravelExpenseReportTable.status] != TravelExpenseReportStatus.EXECUTED ||
            row[TravelExpenseReportTable.postedJournalEntryId] == null
        ) {
            return TravelExpenseSepaBlocker.NOT_BOOKED
        }
        if (row[TravelExpenseReportTable.payoutChoice] != TravelExpensePayoutChoice.PAYOUT) return TravelExpenseSepaBlocker.NOT_PAYOUT
        val payable = payableCents(row)
        if (payable <= 0) return TravelExpenseSepaBlocker.NOTHING_PAYABLE
        if (row[TravelExpenseReportTable.paidOutsideLapis]) return TravelExpenseSepaBlocker.PAID_OUTSIDE_LAPIS
        if (!forReexport && row[TravelExpenseReportTable.sepaExportedAt] != null) return TravelExpenseSepaBlocker.ALREADY_EXPORTED
        if (actor != null &&
            (actor.memberId == row[TravelExpenseReportTable.subjectMemberId] || actor.memberId == row[TravelExpenseReportTable.requestedBy])
        ) {
            return TravelExpenseSepaBlocker.OWN_REPORT
        }
        if (row[TravelExpenseReportTable.refundIbanCiphertext] == null) return TravelExpenseSepaBlocker.NO_REFUND_IBAN
        if (secretKey == null) return TravelExpenseSepaBlocker.ENCRYPTION_KEY_MISSING
        if (bookedBankCreditCents == null || bookedBankCreditCents != payable) return TravelExpenseSepaBlocker.AMOUNT_MISMATCH_WITH_BOOKING
        return null
    }

    /** What is still missing before a file can be generated; must run inside the caller's transaction. */
    fun missingSetup(hasBankAccount: Boolean): List<String> =
        buildList {
            if (!hasBankAccount) add("BANK_ACCOUNT")
            if (runCatching { loadOrganizationName() }.isFailure) add("ORGANIZATION_NAME")
            if (secretKey == null) add("ENCRYPTION_KEY")
        }

    // ── internals ───────────────────────────────────────────────────────────────────────────

    private fun requireRole(actor: CurrentMember) {
        if (actor.role !in SEPA_EXPORT_ROLES) throw TravelExpenseSepaException(kind = SepaFailureKind.FORBIDDEN, code = "forbidden")
    }

    private fun generate(
        actor: CurrentMember,
        reportIds: List<Uuid>,
        reexportReason: String?,
        debtorBankAccountId: Uuid?,
        executionDate: LocalDate,
    ): Result {
        val key = secretKey ?: throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "encryption_key_missing")
        // One clock read before the transaction: Exposed repeats the block on an SQLException and the block has no outer effect.
        val now = DbClock.nowLocalDateTime()
        validateExecutionDate(date = executionDate, now = now)
        val box = SecretBox(key)
        val isReexport = reexportReason != null

        return transaction {
            val debtor = loadDebtorAccount(debtorBankAccountId)
            val organizationName = loadOrganizationName()

            val rows =
                TravelExpenseReportTable
                    .selectAll()
                    .where { TravelExpenseReportTable.id inList reportIds }
                    .orderBy(TravelExpenseReportTable.id to SortOrder.ASC)
                    .forUpdate()
                    .toList()
            // Missing, foreign and garbage ids are indistinguishable -- no way to probe for existing reports.
            if (rows.size != reportIds.size) throw TravelExpenseSepaException(kind = SepaFailureKind.NOT_FOUND, code = "report_not_found")

            val credits = bookedBankCredits(rows)
            val blockers = mutableMapOf<String, TravelExpenseSepaBlocker>()
            rows.forEach { row ->
                val blocker =
                    blockerOf(
                        row = row,
                        actor = actor,
                        bookedBankCreditCents = credits[row[TravelExpenseReportTable.postedJournalEntryId]],
                        forReexport = isReexport,
                    )
                if (blocker != null) blockers[row[TravelExpenseReportTable.id].toString()] = blocker
            }
            if (isReexport && rows.single()[TravelExpenseReportTable.sepaExportedAt] == null) {
                throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "not_exported_yet")
            }
            if (blockers.isNotEmpty()) {
                // The whole export is refused -- nothing is filtered silently (an own report in the selection included).
                throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "reports_not_exportable", blockers = blockers)
            }
            if (isReexport && rows.single()[TravelExpenseReportTable.sepaReexportCount] >= MAX_REEXPORTS) {
                throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "reexport_limit_reached")
            }

            val candidates = rows.map { Candidate(row = it, payableCents = payableCents(it)) }
            val holderFallbackNames = displayNames(rows.map { it[TravelExpenseReportTable.subjectMemberId] })
            val batchId = Uuid.random()
            val batchIdString = batchId.toString()

            val transactions =
                candidates.map { candidate ->
                    val reportId = candidate.row[TravelExpenseReportTable.id]
                    val holder =
                        candidate.row[TravelExpenseReportTable.refundAccountHolder]
                            ?: holderFallbackNames[candidate.row[TravelExpenseReportTable.subjectMemberId]]
                            ?: ""
                    val iban =
                        try {
                            TravelExpenseRefundAccount.open(
                                box = box,
                                reportId = reportId,
                                ciphertext = requireNotNull(candidate.row[TravelExpenseReportTable.refundIbanCiphertext]),
                            )
                        } catch (_: ConflictException) {
                            throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "refund_iban_unreadable")
                        }
                    val reexportCount = candidate.row[TravelExpenseReportTable.sepaReexportCount] + (if (isReexport) 1 else 0)
                    Pain001Transaction(
                        endToEndId = SepaPain001Writer.endToEndIdOf(reportId = reportId.toString(), reexportCount = reexportCount),
                        amountCents = candidate.payableCents,
                        creditorName = holder,
                        creditorIban = iban,
                        remittanceInformation =
                            "Reisekosten ${TravelExpenseSepaReference.of(reportId.toString())} vom " +
                                germanDate(candidate.row[TravelExpenseReportTable.travelTo]),
                    )
                }
            val pain =
                Pain001Batch(
                    messageId = SepaPain001Writer.messageIdOf(batchIdString),
                    paymentInfoId = SepaPain001Writer.paymentInfoIdOf(batchIdString),
                    creationDateTime = OrganizationTimeZone.wallNowOf(now),
                    initiatingPartyName = organizationName,
                    debtorName = organizationName,
                    debtorIban = debtor.iban,
                    debtorBic = debtor.bic,
                    requestedExecutionDate = executionDate,
                    transactions = transactions,
                )
            val xml =
                try {
                    SepaPain001Writer.write(pain)
                } catch (e: IllegalArgumentException) {
                    // The validator names the failing field, never a value.
                    throw TravelExpenseSepaException(
                        kind = SepaFailureKind.CONFLICT,
                        code = "file_validation_failed",
                        message = e.message ?: "file_validation_failed",
                    )
                }
            val sumCents = candidates.sumOf { it.payableCents }

            TravelExpenseSepaBatchTable.insert {
                it[id] = batchId
                it[TravelExpenseSepaBatchTable.debtorBankAccountId] = debtor.id
                it[debtorAccountLabel] = debtor.label
                it[debtorIbanLast4] = IbanValidator.last4(debtor.iban)
                it[requestedExecutionDate] = executionDate
                it[transactionCount] = candidates.size
                it[controlSumCents] = sumCents
                it[painVersion] = SepaPain001Writer.PAIN001_VERSION
                it[createdBy] = actor.memberId
                it[createdAt] = now
                it[TravelExpenseSepaBatchTable.isReexport] = isReexport
                it[reason] = reexportReason
            }
            val lineRowsByReport =
                TravelExpenseLineTable
                    .selectAll()
                    .where { TravelExpenseLineTable.reportId inList reportIds }
                    .toList()
                    .groupBy { it[TravelExpenseLineTable.reportId] }
            candidates.forEachIndexed { index, candidate ->
                val row = candidate.row
                val reportId = row[TravelExpenseReportTable.id]
                val newCount = row[TravelExpenseReportTable.sepaReexportCount] + (if (isReexport) 1 else 0)
                TravelExpenseSepaBatchItemTable.insert {
                    it[id] = Uuid.random()
                    it[TravelExpenseSepaBatchItemTable.batchId] = batchId
                    it[TravelExpenseSepaBatchItemTable.reportId] = reportId
                    it[amountCents] = candidate.payableCents
                    it[endToEndId] = transactions[index].endToEndId
                }
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
                    it[sepaExportedAt] = now
                    it[sepaExportBatchId] = batchId
                    it[sepaExportedBy] = actor.memberId
                    it[sepaReexportCount] = newCount
                }
                recordReportExport(
                    row = row,
                    lines = lineRowsByReport[reportId].orEmpty(),
                    batchId = batchId,
                    newCount = newCount,
                    actor = actor,
                    now = now,
                )
            }
            AuditLogRecorder.record(
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                entityType = AuditEntityType.TRAVEL_EXPENSE_SEPA_BATCH,
                entityId = batchId,
                action = AuditAction.CREATE,
                after =
                    Json.encodeToString(
                        TravelExpenseSepaBatchSnapshot.serializer(),
                        TravelExpenseSepaBatchSnapshot(
                            batchId = batchIdString,
                            transactionCount = candidates.size,
                            controlSumCents = sumCents,
                            executionDate = executionDate,
                            debtorBankAccountId = debtor.id.toString(),
                            reportIds = candidates.map { it.row[TravelExpenseReportTable.id].toString() },
                            isReexport = isReexport,
                        ),
                    ),
                occurredAt = now,
            )
            logger.info {
                "travel expense SEPA file generated: batchId=$batchId count=${candidates.size} sumCents=$sumCents reexport=$isReexport"
            }
            Result(
                batchId = batchId,
                filename = "sepa-reisekosten-${compactDate(
                    executionDate,
                )}-${batchIdString.filter { it != '-' }.take(FILENAME_HEX_CHARS)}.xml",
                xml = xml,
                count = candidates.size,
                sumCents = sumCents,
            )
        }
    }

    private class Debtor(
        val id: Uuid,
        val label: String,
        val iban: String,
        val bic: String?,
    )

    private fun loadDebtorAccount(requestedId: Uuid?): Debtor {
        val row =
            if (requestedId != null) {
                BankAccountTable.selectAll().where { BankAccountTable.id eq requestedId }.singleOrNull()
            } else {
                BankAccountTable.selectAll().where { BankAccountTable.isDefault eq true }.singleOrNull()
            } ?: throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "debtor_account_missing")
        val iban = IbanValidator.normalize(row[BankAccountTable.iban])
        val bic = row[BankAccountTable.bic]?.trim()?.ifBlank { null }
        if (!IbanValidator.isValid(iban) || (bic != null && !BicValidator.isValid(bic))) {
            throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "debtor_account_invalid")
        }
        return Debtor(id = row[BankAccountTable.id], label = row[BankAccountTable.label], iban = iban, bic = bic)
    }

    private fun loadOrganizationName(): String {
        val row =
            OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()
        val raw = row?.get(OrganizationSettingsTable.sepaCreditorName)?.ifBlank { null } ?: row?.get(OrganizationSettingsTable.name) ?: ""
        val sanitized = SepaCharacterSet.sanitize(raw = raw, maxLength = SepaPain001Writer.MAX_NAME_LENGTH)
        if (sanitized.isEmpty()) throw TravelExpenseSepaException(kind = SepaFailureKind.CONFLICT, code = "organization_name_missing")
        return sanitized
    }

    /**
     * Journal entry id -> CREDIT cents that left the bank, derived from the entry itself (POSTED entries only), NOT from the payment
     * bank account configured today: switching `paymentBankAccountId` later must not make old bookings look wrong. A PAYOUT booking
     * credits the bank with `total - advance` and, when an advance was paid out earlier, the advance account with `advance`; so the
     * bank part is everything credited except the advance account. If the advance account setting was changed since, no posting
     * matches it and the one posting equal to the advance amount is taken as the advance part.
     */
    private fun bookedBankCredits(rows: List<ResultRow>): Map<Uuid, Long> {
        val advanceByEntry =
            rows
                .mapNotNull { row ->
                    row[TravelExpenseReportTable.postedJournalEntryId]?.let { it to row[TravelExpenseReportTable.advanceAmount].toCents() }
                }.toMap()
        if (advanceByEntry.isEmpty()) return emptyMap()
        val posted =
            JournalEntryTable
                .selectAll()
                .where { (JournalEntryTable.id inList advanceByEntry.keys) and (JournalEntryTable.status eq JournalEntryStatus.POSTED) }
                .map { it[JournalEntryTable.id] }
                .toSet()
        if (posted.isEmpty()) return emptyMap()
        val advanceAccountId =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.travelAdvanceAccountId)
        return PostingTable
            .selectAll()
            .where { (PostingTable.journalEntryId inList posted) and (PostingTable.side eq PostingSide.CREDIT) }
            .groupBy { it[PostingTable.journalEntryId] }
            .mapValues { (entryId, postings) ->
                val advanceCents = advanceByEntry.getValue(entryId)
                var bank = postings.filter { advanceAccountId == null || it[PostingTable.ledgerAccountId] != advanceAccountId }
                if (advanceCents > 0 && bank.size == postings.size && postings.size > 1) {
                    val advancePosting = bank.firstOrNull { it[PostingTable.amount].toCents() == advanceCents }
                    if (advancePosting != null) bank = bank - advancePosting
                }
                bank.sumOf { it[PostingTable.amount].toCents() }
            }
    }

    private fun displayNames(memberIds: Collection<Uuid>): Map<Uuid, String> =
        MemberTable
            .selectAll()
            .where { MemberTable.id inList memberIds.distinct() }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }

    private fun payableCents(row: ResultRow): Long =
        row[TravelExpenseReportTable.totalAmount].toCents() - row[TravelExpenseReportTable.advanceAmount].toCents()

    private fun validateExecutionDate(
        date: LocalDate,
        now: LocalDateTime,
    ) {
        val today = OrganizationTimeZone.dateOf(now)
        when {
            date < today -> throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "execution_date_past")
            today.daysUntil(
                date,
            ) > MAX_DAYS_AHEAD -> throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "execution_date_too_far")
            !Target2Calendar.isOpen(
                date,
            ) -> throw TravelExpenseSepaException(kind = SepaFailureKind.BAD_REQUEST, code = "execution_date_not_target2_day")
        }
    }

    private fun recordReportExport(
        row: ResultRow,
        lines: List<ResultRow>,
        batchId: Uuid,
        newCount: Int,
        actor: CurrentMember,
        now: LocalDateTime,
    ) {
        val id = row[TravelExpenseReportTable.id]
        val before =
            TravelExpenseSnapshot(
                reportId = id.toString(),
                subjectMemberId = row[TravelExpenseReportTable.subjectMemberId].toString(),
                status = row[TravelExpenseReportTable.status],
                travelFrom = row[TravelExpenseReportTable.travelFrom],
                travelTo = row[TravelExpenseReportTable.travelTo],
                lineCount = lines.size,
                lineKinds = lines.map { it[TravelExpenseLineTable.kind] },
                totalAmount = row[TravelExpenseReportTable.totalAmount],
                postedJournalEntryId = row[TravelExpenseReportTable.postedJournalEntryId]?.toString(),
                executionError = row[TravelExpenseReportTable.executionError],
                payoutChoice = row[TravelExpenseReportTable.payoutChoice],
                advanceCents = row[TravelExpenseReportTable.advanceAmount].toCents(),
                dayLineCount = lines.count { it[TravelExpenseLineTable.kind] == TravelExpenseLineKind.DAY_ALLOWANCE },
                sepaExportBatchId = row[TravelExpenseReportTable.sepaExportBatchId]?.toString(),
                sepaReexportCount = row[TravelExpenseReportTable.sepaReexportCount],
                refundAccountChanged = row[TravelExpenseReportTable.refundAccountChangedAfterSubmit],
            )
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.TRAVEL_EXPENSE_REPORT,
            entityId = id,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(TravelExpenseSnapshot.serializer(), before),
            after =
                Json.encodeToString(
                    TravelExpenseSnapshot.serializer(),
                    before.copy(sepaExportBatchId = batchId.toString(), sepaReexportCount = newCount),
                ),
            occurredAt = now,
        )
    }

    private fun germanDate(date: LocalDate): String = "%02d.%02d.%04d".format(java.util.Locale.ROOT, date.day, date.month.number, date.year)

    private fun compactDate(date: LocalDate): String = "%04d%02d%02d".format(java.util.Locale.ROOT, date.year, date.month.number, date.day)

    companion object {
        const val MAX_DAYS_AHEAD: Int = 60
        const val MIN_REASON_LENGTH: Int = 10
        const val MAX_REASON_LENGTH: Int = 500
        const val MAX_REEXPORTS: Int = 99
        private const val FILENAME_HEX_CHARS = 8

        // Lookahead => a candidate starts at EVERY "XX99" position (a return code like "AC04" in front of an IBAN must not hide it).
        // Whitespace is only tolerated between the groups of at most four characters, never glued across words.
        private val IBAN_CANDIDATE = Regex("(?=([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{1,4}){3,10}))")

        /** `true` when [text] contains a real IBAN (country length + mod 97), not merely something that looks like one. */
        internal fun containsIban(text: String): Boolean =
            IBAN_CANDIDATE.findAll(text.uppercase()).any { match ->
                val compact = match.groupValues[1].filterNot { it.isWhitespace() }
                val length = IbanValidator.COUNTRY_LENGTHS[compact.take(2)] ?: return@any false
                compact.length >= length && IbanValidator.isValid(compact.take(length))
            }

        /** The default execution date offered by the dialog: the first TARGET2 day after today (organization time zone). */
        fun defaultExecutionDate(now: LocalDateTime = DbClock.nowLocalDateTime()): LocalDate {
            val today = OrganizationTimeZone.dateOf(now)
            return Target2Calendar.nextOpenDayAfter(today)
        }
    }
}
