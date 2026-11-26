package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.economy.oracle.DonationStore
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.328 -- test support for every scenario that converts in mode REFERENCE_DATE: since V174 a conversion needs a donation, which in turn is backed by a
 * captured donation payment with exactly one POSTED bank line. [bankedDonation] creates such a payment (and the bank line); [cleanup] removes everything
 * created through this object, in FK order (call it after the conversions of the test were deleted and before its members are deleted).
 */
object DonationFixtures {
    private val payments = mutableListOf<Uuid>()
    private var importId: Uuid? = null
    private var lineOrdinal = 0

    private fun ensureImport(uploader: Uuid): Uuid {
        importId?.let { return it }
        val id = Uuid.random()
        transaction {
            BankStatementImportTable.insert {
                it[BankStatementImportTable.id] = id
                it[format] = BankStatementFormat.CSV
                it[dialect] = "SPARKASSE_CSV"
                it[fileName] = "donation-fixtures.csv"
                it[fileSizeBytes] = 1
                it[fileDigest] = "digest-$id"
                it[accountIban] = null
                it[statementFrom] = LocalDate(2026, 11, 1)
                it[statementTo] = LocalDate(2026, 11, 25)
                it[openingBalance] = null
                it[closingBalance] = null
                it[lineCount] = 1
                it[duplicateCount] = 0
                it[autoPostedCount] = 0
                it[uploadedBy] = uploader
                it[uploadedAt] = LocalDateTime(2026, 11, 25, 0, 0)
                it[bankAccountId] = null
            }
        }
        importId = id
        return id
    }

    /** Adds a bank statement line for [payment] booked on [booking] (a second POSTED line makes the payment ambiguous). [uploader] must be an existing member (first call only). */
    fun addLine(
        payment: Uuid,
        booking: LocalDate,
        status: BankStatementLineStatus = BankStatementLineStatus.POSTED,
        uploader: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
    ) {
        val imp = ensureImport(uploader)
        lineOrdinal++
        val ordinal = lineOrdinal
        transaction {
            BankStatementLineTable.insert {
                it[id] = Uuid.random()
                it[BankStatementLineTable.importId] = imp
                it[fingerprint] = "fp-${Uuid.random()}"
                it[lineOrdinal] = ordinal
                it[bookingDate] = booking
                it[valueDate] = booking
                it[amount] = BigDecimal("25.00")
                it[currency] = "EUR"
                it[counterpartyName] = null
                it[counterpartyIbanLast4] = null
                it[counterpartyIbanCiphertext] = null
                it[purpose] = "Spende"
                it[endToEndReference] = null
                it[bookingText] = null
                it[BankStatementLineTable.status] = status
                it[matchExplanation] = null
                it[matchedContributionId] = null
                it[paymentTransactionId] = payment
                it[resolvedBy] = null
                it[resolvedAt] = null
                it[resolutionNote] = null
            }
        }
    }

    /** A captured EUR donation payment of [member] with one POSTED bank line on [bookingDate] (no line when [bookingDate] is null). */
    fun bankedDonation(
        member: Uuid,
        amount: String = "25.00",
        bookingDate: LocalDate? = LocalDate(2026, 11, 20),
        status: PaymentTransactionStatus = PaymentTransactionStatus.CAPTURED,
        intent: PaymentIntent = PaymentIntent.DONATION,
        currency: String = "EUR",
        receivedAt: LocalDateTime = LocalDateTime(2026, 11, 20, 0, 0),
        provider: PaymentProvider = PaymentProvider.MANUAL,
        withDonation: Boolean = true,
        memberOrNull: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            PaymentTransactionTable.insert {
                it[PaymentTransactionTable.id] = id
                it[PaymentTransactionTable.provider] = provider
                it[providerEventId] = "evt-$id"
                it[providerPaymentId] = "pay-$id"
                it[PaymentTransactionTable.status] = status
                it[PaymentTransactionTable.amount] = BigDecimal(amount)
                it[PaymentTransactionTable.currency] = currency
                it[feeAmount] = null
                it[PaymentTransactionTable.intent] = intent
                it[contributionId] = null
                it[memberId] = if (memberOrNull) null else member
                it[payerReference] = "payer-secret-$id"
                it[PaymentTransactionTable.receivedAt] = receivedAt
                it[rawPayloadDigest] = "0".repeat(64)
            }
        }
        payments += id
        if (bookingDate != null) addLine(payment = id, booking = bookingDate, uploader = member)
        // V1.9.329: the application creates the donation where the payment comes into being; the fixture mirrors that (after the bank line).
        if (withDonation) ensureDonation(id)
        return id
    }

    /** The donation row of [payment], created the way the entry points of the application do it (after the bank line, if any). */
    fun ensureDonation(payment: Uuid) {
        transaction { DonationStore.ensureForPayment(paymentId = payment, createdBy = null, now = LocalDateTime(2026, 11, 20, 0, 0)) }
    }

    /** The payments created so far (for assertions and for the legacy cleanup of a test). */
    fun createdPayments(): List<Uuid> = payments.toList()

    /** Removes the donation rows, bank lines, payments and the bank import created through this object. Conversions must already be gone. */
    fun cleanup() {
        val ids = payments.toList()
        val imp = importId
        transaction {
            DonationTable.deleteWhere { DonationTable.paymentTransactionId inList ids }
            BankStatementLineTable.deleteWhere { BankStatementLineTable.paymentTransactionId inList ids }
            if (imp != null) BankStatementLineTable.deleteWhere { BankStatementLineTable.importId eq imp }
            PaymentTransactionTable.deleteWhere { PaymentTransactionTable.id inList ids }
            if (imp != null) BankStatementImportTable.deleteWhere { BankStatementImportTable.id eq imp }
        }
        payments.clear()
        importId = null
    }
}
