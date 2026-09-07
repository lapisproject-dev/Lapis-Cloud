package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.BillingInterval
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
 * Welle V1.4.4.5 "Sterbefall-Workflow" -- VERIFICATION test, not a code change: pins the plan's
 * §3.4 finding that `ContributionService.generateContributionsForPeriod`'s existing
 * `status eq MemberStatus.ACTIVE` filter already structurally excludes a DECEASED member (and
 * every other non-ACTIVE status) from ever generating a new contribution line, with no code
 * change needed in that method. Same throwaway-plain-Ktor-route posture [ServiceIntegrationTest]
 * already establishes for this exact service.
 */
class ContributionServiceDeceasedFilterTest :
    FunSpec({
        // Regression guard (found live): without tracking + cleanup here, the ACTIVE member this
        // spec creates leaked into the shared H2 DB and broke ServiceIntegrationTest's hardcoded
        // "listMembers returns exactly 4 rows" assertion -- same "every target member ... freshly
        // created by this file, cleaned up afterward" discipline MemberFamilyConstraintTest/
        // MemberDateOfDeathConstraintTest/FoundationPersonalDataTest already establish.
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
            }
        }

        fun newTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Deceased-Filter-Testtarif"
                    it[description] = "Nur fuer ContributionServiceDeceasedFilterTest"
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
            status: MemberStatus,
            tierId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Deceased-Filter Testmitglied"
                    it[email] = "deceased-filter-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = tierId
                }
            }
            createdMemberIds += id
            return id
        }

        fun contributionCountFor(memberId: Uuid): Long =
            transaction { ContributionTable.selectAll().where { ContributionTable.memberId eq memberId }.count() }

        test("generateContributionsForPeriod produces NO line for a DECEASED member assigned to the tier") {
            testApplication {
                application {
                    routing {
                        post("/test/generate") {
                            val service = ContributionService(call)
                            val count =
                                service.generateContributionsForPeriod(
                                    membershipTierId = call.request.queryParameters["tierId"]!!,
                                    periodStart = LocalDate(2026, 10, 1),
                                    periodEnd = LocalDate(2026, 10, 31),
                                )
                            call.respondText(count.toString())
                        }
                    }
                }
                val tierId = newTier()
                val deceased = newMember(MemberStatus.DECEASED, tierId)

                client
                    .post("/test/generate?tierId=$tierId") { header("X-Member-Id", TREASURER_ID) }
                    .bodyAsText()
                    .toInt() shouldBe 0
                contributionCountFor(deceased) shouldBe 0L
            }
        }

        test("generateContributionsForPeriod produces exactly one line for an ACTIVE member assigned to the same tier") {
            testApplication {
                application {
                    routing {
                        post("/test/generate") {
                            val service = ContributionService(call)
                            val count =
                                service.generateContributionsForPeriod(
                                    membershipTierId = call.request.queryParameters["tierId"]!!,
                                    periodStart = LocalDate(2026, 11, 1),
                                    periodEnd = LocalDate(2026, 11, 30),
                                )
                            call.respondText(count.toString())
                        }
                    }
                }
                val tierId = newTier()
                val active = newMember(MemberStatus.ACTIVE, tierId)

                client
                    .post("/test/generate?tierId=$tierId") { header("X-Member-Id", TREASURER_ID) }
                    .bodyAsText()
                    .toInt() shouldBe 1
                contributionCountFor(active) shouldBe 1L
            }
        }
    })
