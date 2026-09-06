package network.lapis.cloud.server.payment.bankstatement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PaymentReferenceCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Review fix (MEDIUM, "Fehlende Testabdeckung"): [PaymentReferenceAllocator] previously had zero
 * direct test coverage. Note the per-attempt SAVEPOINT fix (see that object's own KDoc) is a
 * PostgreSQL-only behavior difference -- H2 (`MODE=PostgreSQL`, this test's own environment, see
 * `DatabaseConfig`) does not abort the whole transaction on a failed statement the way PostgreSQL
 * does, so a collision-retry regression test cannot actually observe the bug this file's tests run
 * against; these tests instead pin the allocator's basic, environment-independent contract.
 */
class PaymentReferenceAllocatorTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        fun createContribution(): Uuid {
            val memberId = Uuid.random()
            val tierId = Uuid.random()
            val contributionId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "Referenz-Test Mitglied"
                    it[email] = "referenz-test-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                MembershipTierTable.insert {
                    it[id] = tierId
                    it[name] = "Standard"
                    it[description] = "Standard"
                    it[contributionAmount] = BigDecimal("10.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
                ContributionTable.insert {
                    it[id] = contributionId
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("10.00")
                    it[status] = ContributionStatus.OPEN
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[dueDate] = LocalDate(2026, 1, 15)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[paymentReference] = null
                }
            }
            createdMemberIds += memberId
            createdTierIds += tierId
            createdContributionIds += contributionId
            return contributionId
        }

        afterTest {
            transaction {
                createdContributionIds.forEach { ContributionTable.deleteWhere { ContributionTable.id eq it } }
                createdTierIds.forEach { MembershipTierTable.deleteWhere { MembershipTierTable.id eq it } }
                if (createdMemberIds.isNotEmpty()) {
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            createdMemberIds.clear()
            createdTierIds.clear()
            createdContributionIds.clear()
        }

        test("allocate persists a well-formed reference and two contributions never collide with each other") {
            val a = createContribution()
            val b = createContribution()
            val referenceA = transaction { PaymentReferenceAllocator.allocate(a) }
            val referenceB = transaction { PaymentReferenceAllocator.allocate(b) }

            referenceA shouldStartWith PaymentReferenceCode.PREFIX
            referenceA.length shouldBe PaymentReferenceCode.PREFIX.length + PaymentReferenceCode.CANONICAL_LENGTH
            referenceA shouldNotBe referenceB

            transaction {
                ContributionTable.selectAll().where { ContributionTable.id eq a }.single()[ContributionTable.paymentReference] shouldBe
                    referenceA
                ContributionTable.selectAll().where { ContributionTable.id eq b }.single()[ContributionTable.paymentReference] shouldBe
                    referenceB
            }
        }

        test("ensureReference is idempotent -- a second call returns the SAME reference, does not reallocate") {
            val contributionId = createContribution()
            val first = transaction { PaymentReferenceAllocator.ensureReference(contributionId) }
            val second = transaction { PaymentReferenceAllocator.ensureReference(contributionId) }
            first shouldBe second
        }
    })
