package network.lapis.cloud.server.payment.bankstatement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.db.generated.BankStatementLineTable
import network.lapis.cloud.server.db.generated.DonationTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PaymentTransactionTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentIntent
import network.lapis.cloud.shared.domain.PaymentProvider
import network.lapis.cloud.shared.domain.PaymentTransactionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** [BankPaymentReceivedAtRepair]: legacy bank payments get the booking day as `received_at`; everything else is left alone. All data is synthetic. */
class BankPaymentReceivedAtRepairTest :
    FunSpec({
        val memberId = Uuid.random()
        val importId = Uuid.random()
        val paymentIds = mutableListOf<Uuid>()
        val bookingDate = LocalDate(2026, 9, 16)
        var ordinal = 0
        val assignedAt = LocalDateTime(2026, 11, 23, 10, 0, 0)

        beforeSpec {
            DatabaseConfig.connect()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Repair-Test"
                    it[email] = "repair-$memberId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                BankStatementImportTable.insert {
                    it[id] = importId
                    it[format] = BankStatementFormat.CSV
                    it[dialect] = "SPARKASSE"
                    it[fileName] = "repair.csv"
                    it[fileSizeBytes] = 1L
                    it[fileDigest] = "d-$importId".take(64)
                    it[lineCount] = 0
                    it[duplicateCount] = 0
                    it[autoPostedCount] = 0
                    it[uploadedBy] = memberId
                    it[uploadedAt] = assignedAt
                }
            }
        }

        afterSpec {
            transaction {
                BankStatementLineTable.deleteWhere { BankStatementLineTable.importId eq importId }
                DonationTable.deleteWhere { DonationTable.paymentTransactionId inList paymentIds }
                PaymentTransactionTable.deleteWhere { PaymentTransactionTable.id inList paymentIds }
                BankStatementImportTable.deleteWhere { BankStatementImportTable.id eq importId }
                MemberTable.deleteWhere { MemberTable.id eq memberId }
            }
        }

        fun payment(
            provider: PaymentProvider,
            receivedAt: LocalDateTime,
            reconciledAt: LocalDateTime?,
            eventId: (Uuid) -> String,
            lineStatus: BankStatementLineStatus = BankStatementLineStatus.POSTED,
            lines: Int = 1,
        ): Uuid {
            val ptId = Uuid.random()
            val firstLine = Uuid.random()
            val event = eventId(firstLine)
            paymentIds += ptId
            transaction {
                PaymentTransactionTable.insert {
                    it[id] = ptId
                    it[PaymentTransactionTable.provider] = provider
                    it[providerEventId] = event
                    it[providerPaymentId] = event
                    it[status] = PaymentTransactionStatus.CAPTURED
                    it[amount] = BigDecimal("30.00")
                    it[currency] = "EUR"
                    it[intent] = PaymentIntent.DONATION
                    it[PaymentTransactionTable.receivedAt] = receivedAt
                    it[PaymentTransactionTable.reconciledAt] = reconciledAt
                    it[rawPayloadDigest] = event.take(64)
                }
                for (n in 0 until lines) {
                    val lineId = if (n == 0) firstLine else Uuid.random()
                    BankStatementLineTable.insert {
                        it[id] = lineId
                        it[BankStatementLineTable.importId] = importId
                        it[fingerprint] = "fp-$lineId"
                        it[lineOrdinal] = ordinal++
                        it[BankStatementLineTable.bookingDate] = bookingDate
                        it[amount] = BigDecimal("30.00")
                        it[currency] = "EUR"
                        it[status] = lineStatus
                        it[paymentTransactionId] = ptId
                    }
                }
            }
            return ptId
        }

        fun receivedAt(id: Uuid): LocalDateTime =
            transaction {
                PaymentTransactionTable.selectAll().where { PaymentTransactionTable.id eq id }.single()[PaymentTransactionTable.receivedAt]
            }

        test("a legacy payment gets the booking day, everything else stays; a second run finds nothing") {
            val legacy = payment(PaymentProvider.MANUAL, assignedAt, assignedAt, { "manual-donation:$it" })
            val alreadyCorrect =
                payment(
                    PaymentProvider.MANUAL,
                    OrganizationTimeZone.systemStartOfDay(bookingDate),
                    assignedAt,
                    { "manual-donation:$it" },
                )
            val psp = payment(PaymentProvider.STRIPE, assignedAt, assignedAt, { "manual-donation:$it" })
            val twoLines = payment(PaymentProvider.MANUAL, assignedAt, assignedAt, { "manual-donation:$it" }, lines = 2)
            val unposted =
                payment(
                    PaymentProvider.MANUAL,
                    assignedAt,
                    assignedAt,
                    { "manual-donation:$it" },
                    lineStatus = BankStatementLineStatus.UNMATCHED,
                )
            val foreignEvent = payment(PaymentProvider.MANUAL, assignedAt, assignedAt, { "something-else:$it" })

            (BankPaymentReceivedAtRepair.run() >= 1) shouldBe true

            receivedAt(legacy) shouldBe OrganizationTimeZone.systemStartOfDay(bookingDate)
            receivedAt(alreadyCorrect) shouldBe OrganizationTimeZone.systemStartOfDay(bookingDate)
            for (untouched in listOf(psp, twoLines, unposted, foreignEvent)) receivedAt(untouched) shouldBe assignedAt

            BankPaymentReceivedAtRepair.run() shouldBe 0
            receivedAt(legacy) shouldBe OrganizationTimeZone.systemStartOfDay(bookingDate)
        }
    })
