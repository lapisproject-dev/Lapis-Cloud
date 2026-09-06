package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.rpc.ContributionPaymentEvents
import network.lapis.cloud.server.rpc.ContributionPostingBridge
import network.lapis.cloud.server.rpc.DonationPostingBridge
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementDonationAssignmentInput
import network.lapis.cloud.shared.domain.BankStatementImportDto
import network.lapis.cloud.shared.domain.BankStatementImportPageDto
import network.lapis.cloud.shared.domain.BankStatementLineDto
import network.lapis.cloud.shared.domain.BankStatementLinePageDto
import network.lapis.cloud.shared.domain.BankStatementLineQuery
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementMatchCandidateDto
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.ContributionStatusSets
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val MAX_PAGE_SIZE = 200

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Reine Exposed-Datenzugriffs- UND Disposition-Schicht fuer
 * `bank_statement_import`/`bank_statement_line` -- oeffnet, wie jede `*Store` in diesem Codebase,
 * ihre eigenen `transaction {}` (anders als `CrmContactStore`, weil dieses Objekt sowohl von der
 * RPC-Service-Schicht (`BankStatementService`) als auch potenziell von Tests direkt aufgerufen
 * wird, und die Disposition-Methoden -- anders als reine Lesezugriffe -- Mehrfach-Statement-
 * Transaktionen brauchen, deren Grenze hier, nicht beim Aufrufer, liegt).
 */
internal object BankStatementStore {
    fun listImports(
        limit: Int,
        offset: Int,
    ): BankStatementImportPageDto =
        transaction {
            val cappedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val total = BankStatementImportTable.selectAll().count()
            val rows =
                (BankStatementImportTable innerJoin MemberTable)
                    .selectAll()
                    .orderBy(BankStatementImportTable.uploadedAt, SortOrder.DESC)
                    .limit(cappedLimit)
                    .offset(offset.toLong())
                    .map { row ->
                        BankStatementImportDto(
                            id = row[BankStatementImportTable.id].toString(),
                            format = row[BankStatementImportTable.format],
                            dialect = row[BankStatementImportTable.dialect].toBankCsvDialect(),
                            fileName = row[BankStatementImportTable.fileName],
                            accountIbanMasked = BankStatementImportService.maskIban(row[BankStatementImportTable.accountIban]),
                            statementFrom = row[BankStatementImportTable.statementFrom],
                            statementTo = row[BankStatementImportTable.statementTo],
                            lineCount = row[BankStatementImportTable.lineCount],
                            duplicateCount = row[BankStatementImportTable.duplicateCount],
                            autoPostedCount = row[BankStatementImportTable.autoPostedCount],
                            uploadedByDisplayName = row[MemberTable.displayName],
                            uploadedAt = row[BankStatementImportTable.uploadedAt],
                        )
                    }
            BankStatementImportPageDto(rows = rows, totalCount = total.toInt(), limit = cappedLimit, offset = offset)
        }

    fun listLines(query: BankStatementLineQuery): BankStatementLinePageDto =
        transaction {
            val cappedLimit = query.limit.coerceIn(1, MAX_PAGE_SIZE)
            val resolver = MemberTable.alias("resolver")
            var condition: Op<Boolean> = Op.TRUE
            query.importId?.let { id ->
                condition =
                    condition and
                    (
                        BankStatementLineTable.importId eq
                            runCatching { Uuid.parse(id) }.getOrElse { throw BadRequestException("Invalid importId") }
                    )
            }
            query.status?.let { status -> condition = condition and (BankStatementLineTable.status eq status) }
            if (!query.includeNonPositiveAmounts) {
                condition = condition and (BankStatementLineTable.amount greater BigDecimal.ZERO)
            }

            val baseQuery =
                BankStatementLineTable
                    .join(resolver, JoinType.LEFT, BankStatementLineTable.resolvedBy, resolver[MemberTable.id])
                    .selectAll()
                    .where { condition }
            val total = baseQuery.count()
            val rows =
                baseQuery
                    .orderBy(BankStatementLineTable.bookingDate, SortOrder.DESC)
                    .limit(cappedLimit)
                    .offset(query.offset.toLong())
                    .map { row -> row.toLineDto(resolverAlias = resolver) }
            BankStatementLinePageDto(rows = rows, totalCount = total.toInt(), limit = cappedLimit, offset = query.offset)
        }

    fun suggestMatches(
        lineId: Uuid,
        secretBox: SecretBox?,
    ): List<BankStatementMatchCandidateDto> =
        transaction {
            val row =
                BankStatementLineTable.selectAll().where { BankStatementLineTable.id eq lineId }.singleOrNull()
                    ?: throw NotFoundException("Bank statement line $lineId not found")
            val counterpartyIbanRaw = decryptCounterpartyIban(row = row, secretBox = secretBox)
            val outcome =
                BankStatementMatcher.match(
                    line =
                        NormalizedLine(
                            amount = row[BankStatementLineTable.amount],
                            currency = row[BankStatementLineTable.currency],
                            purpose = row[BankStatementLineTable.purpose],
                            counterpartyName = row[BankStatementLineTable.counterpartyName],
                            counterpartyIbanRaw = counterpartyIbanRaw,
                        ),
                    secretBox = secretBox,
                )
            val contributionId = outcome.contributionId ?: return@transaction emptyList()
            val contributionRow =
                ContributionTable
                    .innerJoin(MemberTable)
                    .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                    .selectAll()
                    .where { ContributionTable.id eq contributionId }
                    .singleOrNull() ?: return@transaction emptyList()
            listOf(contributionRow.toCandidateDto(explanation = outcome.explanation ?: ""))
        }

    fun searchAssignmentTargets(
        term: String,
        limit: Int,
    ): List<BankStatementMatchCandidateDto> =
        transaction {
            val cappedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
            val trimmedTerm = term.trim()
            if (trimmedTerm.isEmpty()) return@transaction emptyList()
            ContributionTable
                .innerJoin(MemberTable)
                .join(MembershipTierTable, JoinType.INNER, ContributionTable.membershipTierId, MembershipTierTable.id)
                .selectAll()
                .where { ContributionTable.status inList ContributionStatusSets.OUTSTANDING.toList() }
                .filter { row -> row[MemberTable.displayName].contains(trimmedTerm, ignoreCase = true) }
                .take(cappedLimit)
                .map { row -> row.toCandidateDto(explanation = "manuelle Suche") }
        }

    fun assignLineToContribution(
        lineId: Uuid,
        contributionId: Uuid,
        note: String?,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): BankStatementLineDto =
        transaction {
            val lineRow = requireAssignableLine(lineId)
            val amount = lineRow[BankStatementLineTable.amount]
            val currency = lineRow[BankStatementLineTable.currency]
            val now = DbClock.nowLocalDateTime()

            val contributionRow =
                ContributionTable.selectAll().where { ContributionTable.id eq contributionId }.singleOrNull()
                    ?: throw NotFoundException("Contribution $contributionId not found")
            val memberId = contributionRow[ContributionTable.memberId]

            val fingerprint = "manual-assign:$lineId"
            val paymentTransactionId = Uuid.random()
            val inserted =
                runCatching {
                    PaymentTransactionTable.insert {
                        it[id] = paymentTransactionId
                        it[provider] = PaymentProvider.MANUAL
                        it[providerEventId] = fingerprint
                        it[providerPaymentId] = fingerprint
                        it[status] = PaymentTransactionStatus.CAPTURED
                        it[PaymentTransactionTable.amount] = amount
                        it[PaymentTransactionTable.currency] = currency
                        it[feeAmount] = null
                        it[intent] = PaymentIntent.CONTRIBUTION
                        it[PaymentTransactionTable.contributionId] = contributionId
                        it[PaymentTransactionTable.memberId] = memberId
                        it[payerReference] = null
                        it[receivedAt] = now
                        it[reconciledAt] = now
                        it[reconciledBy] = actorMemberId
                        it[reconciliationNote] = note?.take(2000)
                        it[rawPayloadDigest] = fingerprint
                        it[checkoutSessionId] = null
                        it[donorCategory] = null
                    }
                }
            if (inserted.isFailure) {
                if (inserted.exceptionOrNull() is ExposedSQLException) {
                    throw ConflictException("Diese Zeile wurde bereits gebucht.")
                }
                throw inserted.exceptionOrNull() ?: IllegalStateException("payment_transaction insert failed")
            }

            val updated =
                ContributionTable.update({
                    (ContributionTable.id eq contributionId) and
                        (
                            ContributionTable.status notInList
                                (ContributionStatusSets.SETTLED + ContributionStatusSets.DEBIT_IN_FLIGHT).toList()
                        )
                }) {
                    it[status] = ContributionStatus.PAID
                    it[paidAt] = now
                    it[paidAmount] = amount
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                }
            if (updated == 0) {
                throw ConflictException("Beitrag ist bereits ausgeglichen oder in einem laufenden SEPA-Lastschrifteinzug.")
            }

            val journalEntryId =
                ContributionPostingBridge.postContributionPayment(
                    contributionId = contributionId,
                    paidAmount = amount,
                    paidAt = now,
                    source = ContributionPaymentMethod.MANUAL,
                    providerFee = null,
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    voucherReference = note?.take(140) ?: "KA-MANUAL-$lineId",
                )
            // Review fix (MAJOR): must throw, not silently degrade -- see the sibling
            // assignLineToDonation below, which already gets this right. A `null` result means the
            // ledger-account mapping is incomplete/inactive (see ContributionPostingBridge KDoc
            // "Verhaelt sich degradierend statt scheiternd") -- no journal entry exists. Marking the
            // line POSTED anyway here would leave the contribution PAID with no booking, and
            // requireAssignableLine's "a POSTED line can never be reassigned" rule would then make
            // that state permanent (the exact "unheilbar" bug class postOneLine's own PostOneLineHalt
            // fix addresses for the auto-post path). Throwing here rolls back the payment_transaction
            // insert and the contribution.status = PAID update together with it.
            if (journalEntryId == null) {
                throw ConflictException(
                    "Beitrag konnte nicht gebucht werden (Kontenzuordnung unvollstaendig oder Konto deaktiviert). " +
                        "Bitte Kontenzuordnung in den Organisationseinstellungen pruefen, bevor erneut zugeordnet wird.",
                )
            }

            BankStatementLineTable.update({ BankStatementLineTable.id eq lineId }) {
                it[status] = BankStatementLineStatus.POSTED
                it[matchedContributionId] = contributionId
                it[BankStatementLineTable.paymentTransactionId] = paymentTransactionId
                it[resolvedBy] = actorMemberId
                it[resolvedAt] = now
                it[resolutionNote] = note?.take(500)
            }
            ContributionPaymentEvents.publishPaid(
                contributionId = contributionId,
                paidAt = now,
                amount = amount,
                transactionId = paymentTransactionId.toString(),
            )

            requireLineDto(lineId)
        }

    fun assignLineToDonation(
        lineId: Uuid,
        input: BankStatementDonationAssignmentInput,
        actorMemberId: Uuid,
        actorRole: AccountRole,
    ): BankStatementLineDto =
        transaction {
            val donorMemberUuid =
                input.donorMemberId?.let {
                    runCatching { Uuid.parse(it) }.getOrElse { throw BadRequestException("Invalid donorMemberId") }
                }
            val externalDonorUuid =
                input.externalDonorId?.let {
                    runCatching { Uuid.parse(it) }.getOrElse { throw BadRequestException("Invalid externalDonorId") }
                }
            if ((donorMemberUuid == null) == (externalDonorUuid == null)) {
                throw BadRequestException("Exactly one of donorMemberId/externalDonorId must be set")
            }

            val lineRow = requireAssignableLine(lineId)
            val amount = lineRow[BankStatementLineTable.amount]
            val currency = lineRow[BankStatementLineTable.currency]
            val now = DbClock.nowLocalDateTime()

            val fingerprint = "manual-donation:$lineId"
            val paymentTransactionId = Uuid.random()
            val inserted =
                runCatching {
                    PaymentTransactionTable.insert {
                        it[id] = paymentTransactionId
                        it[provider] = PaymentProvider.MANUAL
                        it[providerEventId] = fingerprint
                        it[providerPaymentId] = fingerprint
                        it[status] = PaymentTransactionStatus.CAPTURED
                        it[PaymentTransactionTable.amount] = amount
                        it[PaymentTransactionTable.currency] = currency
                        it[feeAmount] = null
                        it[intent] = PaymentIntent.DONATION
                        it[PaymentTransactionTable.contributionId] = null
                        it[PaymentTransactionTable.memberId] = donorMemberUuid
                        it[payerReference] = null
                        it[receivedAt] = now
                        it[reconciledAt] = now
                        it[reconciledBy] = actorMemberId
                        it[reconciliationNote] = input.note?.take(2000)
                        it[rawPayloadDigest] = fingerprint
                        it[checkoutSessionId] = null
                        it[donorCategory] = input.donorCategory
                    }
                }
            if (inserted.isFailure) {
                if (inserted.exceptionOrNull() is ExposedSQLException) throw ConflictException("Diese Zeile wurde bereits gebucht.")
                throw inserted.exceptionOrNull() ?: IllegalStateException("payment_transaction insert failed")
            }

            val journalEntryId =
                DonationPostingBridge.postDonationPayment(
                    paymentTransactionId = paymentTransactionId,
                    paidAmount = amount,
                    paidAt = now,
                    providerFee = null,
                    donorMemberId = donorMemberUuid,
                    externalDonorId = externalDonorUuid,
                    donorCategory = input.donorCategory,
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    voucherReference = input.note?.take(140) ?: "SP-MANUAL-$lineId",
                )
            if (journalEntryId == null) {
                throw ConflictException(
                    "Spende konnte nicht gebucht werden (Kontenzuordnung unvollstaendig oder Spendengrenze ueberschritten).",
                )
            }

            BankStatementLineTable.update({ BankStatementLineTable.id eq lineId }) {
                it[status] = BankStatementLineStatus.POSTED
                it[BankStatementLineTable.paymentTransactionId] = paymentTransactionId
                it[resolvedBy] = actorMemberId
                it[resolvedAt] = now
                it[resolutionNote] = input.note?.take(500)
            }

            requireLineDto(lineId)
        }

    fun ignoreLine(
        lineId: Uuid,
        reason: String,
        actorMemberId: Uuid,
    ): BankStatementLineDto =
        transaction {
            if (reason.isBlank()) throw BadRequestException("A reason is required to ignore a line")
            requireAssignableLine(lineId)
            val now = DbClock.nowLocalDateTime()
            BankStatementLineTable.update({ BankStatementLineTable.id eq lineId }) {
                it[status] = BankStatementLineStatus.IGNORED
                it[resolvedBy] = actorMemberId
                it[resolvedAt] = now
                it[resolutionNote] = reason.take(500)
            }
            requireLineDto(lineId)
        }

    /**
     * A line already `POSTED` can never be reassigned via this RPC surface -- "Korrektur nur per
     * Storno in der Buchhaltung" (see `IBankStatementService` KDoc).
     *
     * Security finding fix (Review MAJOR): `.forUpdate()` row-locks the line for the rest of the
     * caller's transaction -- without it, this was a plain check-then-act: two concurrent calls
     * (`assignLineToContribution` racing `assignLineToDonation`, or either racing
     * `BankStatementImportService.postOneLine`'s own Phase 2 auto-post, which takes the SAME lock,
     * see that function's KDoc) could both read a non-`POSTED` status and both proceed to book --
     * each path uses a DIFFERENT `payment_transaction.provider_event_id` fingerprint
     * (`"manual-assign:$lineId"`/`"manual-donation:$lineId"`/the auto-post candidate's own
     * fingerprint), so `uq_payment_transaction_provider_event` never fires across paths and the same
     * real bank transaction ends up booked twice.
     */
    private fun requireAssignableLine(lineId: Uuid): ResultRow {
        val row =
            BankStatementLineTable
                .selectAll()
                .where { BankStatementLineTable.id eq lineId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Bank statement line $lineId not found")
        if (row[BankStatementLineTable.status] == BankStatementLineStatus.POSTED) {
            throw ConflictException("Diese Zeile ist bereits gebucht -- Korrektur nur per Storno in der Buchhaltung.")
        }
        return row
    }

    private fun requireLineDto(lineId: Uuid): BankStatementLineDto {
        val resolver = MemberTable.alias("resolver")
        val row =
            BankStatementLineTable
                .join(resolver, JoinType.LEFT, BankStatementLineTable.resolvedBy, resolver[MemberTable.id])
                .selectAll()
                .where { BankStatementLineTable.id eq lineId }
                .single()
        return row.toLineDto(resolverAlias = resolver)
    }

    private fun decryptCounterpartyIban(
        row: ResultRow,
        secretBox: SecretBox?,
    ): String? {
        val ciphertext = row[BankStatementLineTable.counterpartyIbanCiphertext] ?: return null
        if (secretBox == null) return null
        return try {
            secretBox.open(sealed = ciphertext, aad = row[BankStatementLineTable.id].toString())
        } catch (e: SecretBoxException) {
            null
        }
    }

    private fun ResultRow.toLineDto(resolverAlias: Alias<MemberTable>): BankStatementLineDto =
        BankStatementLineDto(
            id = this[BankStatementLineTable.id].toString(),
            importId = this[BankStatementLineTable.importId].toString(),
            bookingDate = this[BankStatementLineTable.bookingDate],
            valueDate = this[BankStatementLineTable.valueDate],
            amount = this[BankStatementLineTable.amount],
            currency = this[BankStatementLineTable.currency],
            counterpartyName = this[BankStatementLineTable.counterpartyName],
            // No country code available without decrypting -- last4 is the only plaintext fragment
            // ever stored, so the line-level mask is deliberately shorter than the account-level
            // "DE...4711" form BankStatementImportService.maskIban produces.
            counterpartyIbanMasked = this[BankStatementLineTable.counterpartyIbanLast4]?.let { "...$it" },
            purpose = this[BankStatementLineTable.purpose],
            endToEndReference = this[BankStatementLineTable.endToEndReference],
            bookingText = this[BankStatementLineTable.bookingText],
            status = this[BankStatementLineTable.status],
            matchExplanation = this[BankStatementLineTable.matchExplanation],
            matchedContributionId = this[BankStatementLineTable.matchedContributionId]?.toString(),
            paymentTransactionId = this[BankStatementLineTable.paymentTransactionId]?.toString(),
            resolvedByDisplayName = this.getOrNull(resolverAlias[MemberTable.displayName]),
            resolvedAt = this[BankStatementLineTable.resolvedAt],
            resolutionNote = this[BankStatementLineTable.resolutionNote],
        )

    private fun ResultRow.toCandidateDto(explanation: String): BankStatementMatchCandidateDto =
        BankStatementMatchCandidateDto(
            contributionId = this[ContributionTable.id].toString(),
            memberDisplayName = this[MemberTable.displayName],
            membershipTierName = this[MembershipTierTable.name],
            periodStart = this[ContributionTable.periodStart],
            periodEnd = this[ContributionTable.periodEnd],
            amountDue = this[ContributionTable.amountDue],
            explanation = explanation,
        )

    private fun String.toBankCsvDialect(): BankCsvDialect =
        runCatching { BankCsvDialect.valueOf(this) }.getOrDefault(BankCsvDialect.GENERIC)
}
