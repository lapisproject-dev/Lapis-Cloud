package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionExemptionRules
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- T-9: `ContributionService
 * .generateContributionsForPeriod`'s new exemption filter, plus an equivalence check against
 * [ContributionExemptionRules.isExemptForPeriod] to guard the two formulations (SQL `WHERE` vs.
 * Kotlin) against drift.
 */
class ContributionReliefExemptionFilterTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                ContributionTable.deleteWhere { memberId inList createdMemberIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
                MembershipTierTable.deleteWhere { id inList createdTierIds }
            }
        }

        fun newTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Exemption-Filter-Testtarif-$id"
                    it[description] = "Nur fuer ContributionReliefExemptionFilterTest"
                    it[contributionAmount] = BigDecimal("10.00")
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        fun newMember(
            tierId: Uuid,
            exemptFrom: LocalDate?,
            exemptUntil: LocalDate?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Exemption-Filter Testmitglied"
                    it[email] = "exemption-filter-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = tierId
                    it[contributionExemptFrom] = exemptFrom
                    it[contributionExemptUntil] = exemptUntil
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun contributionCountFor(memberId: Uuid): Long =
            transaction { ContributionTable.selectAll().where { ContributionTable.memberId eq memberId }.count() }

        fun generate(
            tierId: Uuid,
            periodStart: LocalDate,
            periodEnd: LocalDate,
        ) {
            testApplication {
                application {
                    routing {
                        post("/test/generate") {
                            val service = ContributionService(call)
                            val count =
                                service.generateContributionsForPeriod(
                                    membershipTierId = tierId.toString(),
                                    periodStart = periodStart,
                                    periodEnd = periodEnd,
                                )
                            call.respondText(count.toString())
                        }
                    }
                }
                client.post("/test/generate") { header("X-Member-Id", TREASURER_ID) }
            }
        }

        test("a member exempt for the FULL period generates no contribution line") {
            val tierId = newTier()
            val periodStart = LocalDate(2027, 10, 1)
            val periodEnd = LocalDate(2027, 10, 31)
            val exemptMember = newMember(tierId, exemptFrom = LocalDate(2027, 1, 1), exemptUntil = LocalDate(2027, 12, 31))
            generate(tierId, periodStart, periodEnd)
            contributionCountFor(exemptMember) shouldBe 0L
        }

        test("exemptFrom AFTER periodStart still generates (exemption not yet in effect)") {
            val tierId = newTier()
            val periodStart = LocalDate(2027, 11, 1)
            val periodEnd = LocalDate(2027, 11, 30)
            val notYetExempt = newMember(tierId, exemptFrom = LocalDate(2027, 12, 1), exemptUntil = null)
            generate(tierId, periodStart, periodEnd)
            contributionCountFor(notYetExempt) shouldBe 1L
        }

        test("exemptUntil BEFORE periodEnd still generates (exemption already ended)") {
            val tierId = newTier()
            val periodStart = LocalDate(2027, 12, 1)
            val periodEnd = LocalDate(2027, 12, 31)
            val alreadyEnded = newMember(tierId, exemptFrom = LocalDate(2027, 1, 1), exemptUntil = LocalDate(2027, 11, 1))
            generate(tierId, periodStart, periodEnd)
            contributionCountFor(alreadyEnded) shouldBe 1L
        }

        test("exemptUntil = null (unbefristet) with exemptFrom on/before periodStart generates NO contribution") {
            val tierId = newTier()
            val periodStart = LocalDate(2028, 1, 1)
            val periodEnd = LocalDate(2028, 1, 31)
            val exemptOpenEnded = newMember(tierId, exemptFrom = LocalDate(2027, 1, 1), exemptUntil = null)
            generate(tierId, periodStart, periodEnd)
            contributionCountFor(exemptOpenEnded) shouldBe 0L
        }

        test("equivalence: SQL exclusion and ContributionExemptionRules.isExemptForPeriod agree over a matrix of combinations") {
            val periodStart = LocalDate(2028, 3, 1)
            val periodEnd = LocalDate(2028, 3, 31)
            val combinations =
                listOf(
                    null to null,
                    LocalDate(2028, 1, 1) to LocalDate(2028, 12, 31), // fully covers period
                    LocalDate(2028, 1, 1) to null, // open-ended, covers
                    LocalDate(2028, 4, 1) to null, // starts after period
                    LocalDate(2027, 1, 1) to LocalDate(2028, 2, 1), // ends before period
                    LocalDate(2028, 3, 1) to LocalDate(2028, 3, 31), // exact match
                )
            combinations.forEach { (from, until) ->
                val tierId = newTier()
                val memberId = newMember(tierId, exemptFrom = from, exemptUntil = until)
                generate(tierId, periodStart, periodEnd)
                val generated = contributionCountFor(memberId) == 1L
                val expectedExempt =
                    ContributionExemptionRules.isExemptForPeriod(
                        from = from,
                        until = until,
                        periodStart = periodStart,
                        periodEnd = periodEnd,
                    )
                generated shouldBe !expectedExempt
            }
        }
    })
